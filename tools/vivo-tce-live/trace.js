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
      'skyConfig','sunConfig','dumpDir','dumpPrefix','speConfig','faceConfig','outlineConfig'];
    return names.map((name, index) => {
      try {
        const address = argument.add(8 + index * 8).readPointer();
        if (address.isNull()) return {name, address: '0x0', value: null};
        const range = Process.findRangeByAddress(address);
        if (!range || !range.protection.includes('r')) throw new Error('unreadable range');
        const count = Math.min(1024, range.base.add(range.size).sub(address).toUInt32());
        return {name, address: address.toString(), value: address.readCString(count), limit: count};
      } catch (e) { return {name, error: String(e)}; }
    });
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
        emit('setparam', {handle: args[0].toString(), key: args[1].toInt32(),
          payload: args[2].toString(), note: 'payload not dereferenced'});
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
      },
      onLeave(result) {
        if (!this.selected) return;
        emit('process_leave', {status: result.toInt32(),
          argument: bytes(this.argument, 0x6d0), outputPrefix: bytes(this.output, 0x2e0)});
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
  setTimeout(() => stop('90_second_timeout'), 90000);
  try {
    observer = Process.attachModuleObserver({onAdded(module) {
      try { install(module); }
      catch (e) { emit('observer_error', {error: String(e)}); stop('hook_install_failed'); }
    },
      onRemoved(module) { if (installed && module.name === 'libvivo_nicetce.so') stop('module_unloaded'); }});
    if (stopped) observer.detach();
  } catch (e) { emit('observer_error', {error: String(e)}); stop('attach_failed'); }
})();
