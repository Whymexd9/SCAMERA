'use strict';
// PD2454 TCE 9f5deac3... only. Diagnostic observations; never replay these pointers.
(() => {
  const hooks = [], contexts = new Map();
  let observer = null, stopped = false, installed = false, chosen = false;
  let sequence = 0, creates = 0, parameters = 0;
  const exports = { vivoNiceTceCreate: 0x38424c, vivoNiceTceProcess: 0x391340,
    vivoNiceTceDestroy: 0x385988, vivoNiceTceSetParam: 0x39751c };
  function emit(event, fields = {}) {
    console.log('SCAMERA_TCE ' + JSON.stringify({version: 1, event,
      sequence: ++sequence, timeMs: Date.now(), pid: Process.id, ...fields}));
  }
  function stop(reason) {
    if (stopped) return;
    stopped = true;
    for (const h of hooks) { try { h.detach(); } catch (_) {} }
    if (observer) { try { observer.detach(); } catch (_) {} }
    emit('finished', {reason});
  }
  function bytes(address, size) {
    try {
      if (address.isNull()) return {address: address.toString(), error: 'null'};
      const buffer = address.readByteArray(size);
      if (buffer === null) throw new Error('unreadable');
      return {address: address.toString(), size,
        hex: Array.from(new Uint8Array(buffer), v => v.toString(16).padStart(2, '0')).join('')};
    } catch (e) { return {address: address.toString(), size, error: String(e)}; }
  }
  function paths(argument) {
    const names = ['modelDir','configXml','effectXml','segmentConfig','allInOneConfig',
      'skyConfig','sunConfig','dumpDir','dumpPrefix','speConfig','faceConfig','outlineConfig','gpuBinaryPath'];
    return names.map((name, index) => {
      try {
        const address = argument.add(index === 12 ? 0x100 : 8 + index * 8).readPointer();
        if (address.isNull()) return {name, address: '0x0', value: null};
        // Tagged Android heap pointers need not match untagged mapping ranges.
        // Read only through the terminator, retaining the original pointer tag.
        let count = 0;
        while (count < 1024 && address.add(count).readU8() !== 0) count++;
        if (count === 1024) throw new Error('unterminated path within 1024 bytes');
        return {name, address: address.toString(), value: count ? address.readCString(count) : '', limit: 1024};
      } catch (e) { return {name, error: String(e)}; }
    });
  }
  const hexDigits = Array.from({length: 256}, (_, n) => n.toString(16).padStart(2, '0'));
  let payloadBudget = 192 * 1024 * 1024;
  function payload(name, address, size) {
    if (!size) return;
    try {
      if (!Number.isSafeInteger(size) || size < 0 || size > payloadBudget || address.isNull())
        throw new Error('invalid extent or capture budget exceeded');
      payloadBudget -= size;
      emit('payload_begin', {name, address: address.toString(), size});
      for (let offset = 0; offset < size; offset += 16384) {
        const extent = Math.min(16384, size-offset);
        const data = new Uint8Array(address.add(offset).readByteArray(extent));
        if (data.length !== extent) throw new Error('incomplete memory read');
        emit('payload_chunk', {name, offset, hex: Array.from(data, x => hexDigits[x]).join('')});
      }
      emit('payload_end', {name, size});
    } catch (e) { emit('payload_error', {name, error: String(e)}); }
  }
  function rgbPayload(name, descriptor) {
    try {
      const format=descriptor.readU32(), width=descriptor.add(4).readS32();
      const height=descriptor.add(8).readS32(), stride=descriptor.add(0x30).readS32();
      const rows=descriptor.add(0x40).readS32();
      if (format !== 0x1004 || width <= 0 || height <= 0 || width > 16384 || height > 16384 ||
          stride !== width*6 || rows !== height || stride*height > 96*1024*1024)
        throw new Error('unsupported RGB16 descriptor');
      payload(name, descriptor.add(0x10).readPointer(), stride*height);
    } catch (e) { emit('payload_error', {name, error: String(e)}); }
  }
  function inputPayloads(argument) {
    try {
      const edge=argument.add(0x370).readU32(), count=argument.add(0x374).readU32();
      if (edge && edge <= 65 && count === 3*edge*edge*edge)
        payload('color-lut', argument.add(0x378).readPointer(), count*2);
      else if (edge || count) throw new Error('unsupported color LUT extent');
      // Six arrays copied by the recovered CRE face producer, maximum 40 faces.
      const countFaces=argument.add(0xe8).readS32();
      if (countFaces < 0 || countFaces > 40) throw new Error('unsupported face count');
      for (const [name, offset, unit] of [['roi-rects',0xd0,16],['roi-ids',0xe0,4],
          ['mask-rects',0xf0,16],['mask-valid',0xf8,1],['json-rects',0x280,16],['json-ids',0x290,4]])
        if (countFaces) payload(name,argument.add(offset).readPointer(),countFaces*unit);
    } catch (e) { emit('payload_error', {name:'scene-arrays',error:String(e)}); }
    rgbPayload('input-rgb16', argument);
  }
  function guarded(fn) {
    return function (...args) {
      if (stopped) return;
      try { fn.apply(this, args); } catch (e) { emit('observer_error', {error: String(e)}); }
    };
  }
  function attach(address, callbacks) {
    const wrapped = {};
    for (const [key, fn] of Object.entries(callbacks)) wrapped[key] = guarded(fn);
    hooks.push(Interceptor.attach(address, wrapped));
  }
  function install(module) {
    if (stopped || installed || module.name !== 'libvivo_nicetce.so') return;
    if (module.path !== '/vendor/lib64/libvivo_nicetce.so') {
      emit('wrong_module_path', {path: module.path}); stop('wrong_module'); return;
    }
    const addresses = {};
    for (const [name, offset] of Object.entries(exports)) {
      const address = module.findExportByName(name);
      if (address === null || !address.equals(module.base.add(offset))) {
        emit('wrong_export', {name}); stop('wrong_binary_layout'); return;
      }
      addresses[name] = address;
    }
    installed = true;
    attach(addresses.vivoNiceTceCreate, {
      onEnter(args) {
        if (creates >= 8) return;
        this.captureId = ++creates;
        emit('create_enter', {id: this.captureId, thread: this.threadId,
          argument: bytes(args[0], 0x4c8), paths: paths(args[0])});
      },
      onLeave(result) {
        if (!this.captureId) return;
        if (!result.isNull()) contexts.set(result.toString(), this.captureId);
        emit('create_leave', {id: this.captureId, handle: result.toString()});
      }
    });
    attach(addresses.vivoNiceTceDestroy, {
      onEnter(args) { contexts.delete(args[0].toString()); }
    });
    attach(addresses.vivoNiceTceSetParam, {
      onEnter(args) {
        if (++parameters > 32) return;
        const key = args[1].toInt32();
        // Pinned donor: key 4 loads a word at 3976f8; key 8 copies 0x55 bytes at 397784.
        const size = key === 4 ? 4 : key === 8 ? 0x55 : 0;
        emit('setparam', {handle: args[0].toString(), key,
          payload: args[2].toString(), block: size ? bytes(args[2], size) : null,
          note: size ? 'bounded donor-verified payload' : 'payload not dereferenced'});
      }
    });
    attach(addresses.vivoNiceTceProcess, {
      onEnter(args) {
        if (chosen) return;
        chosen = true; this.selected = true;
        this.argument = args[0]; this.output = args[1];
        const handle = args[2].toString();
        emit('process_enter', {handle, createId: contexts.get(handle) || null,
          thread: this.threadId, argument: bytes(args[0], 0x6d0),
          outputPrefix: bytes(args[1], 0x2e0)});
        inputPayloads(args[0]);
        emit('native_call_start');
      },
      onLeave(result) {
        if (!this.selected) return;
        emit('process_leave', {status: result.toInt32(),
          argument: bytes(this.argument, 0x6d0), outputPrefix: bytes(this.output, 0x2e0)});
        if (result.toInt32() === 0) rgbPayload('output-rgb16', this.output);
        // Leave this callback before removing its own interception trampoline.
        setTimeout(() => stop('one_process_observed'), 0);
      }
    });
    emit('ready', {module: module.path, base: module.base.toString(),
      note: 'Close and reopen stock camera, then take one ordinary photo.'});
  }
  if (Process.arch !== 'arm64' || Process.pointerSize !== 8) {
    stop('unsupported_architecture'); return;
  }
  emit('started', {frida: Frida.version, runtime: Script.runtime});
  setTimeout(() => { if (!chosen) stop('90_second_timeout'); }, 90000);
  setTimeout(() => stop('240_second_timeout'), 240000);
  try {
    observer = Process.attachModuleObserver({onAdded(module) {
      try { install(module); }
      catch (e) { emit('observer_error', {error: String(e)}); stop('hook_install_failed'); }
    },
      onRemoved(module) { if (installed && module.name === 'libvivo_nicetce.so') stop('module_unloaded'); }});
    if (stopped) observer.detach();
  } catch (e) { emit('observer_error', {error: String(e)}); stop('attach_failed'); }
})();
