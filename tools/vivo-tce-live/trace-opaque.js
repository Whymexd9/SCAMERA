'use strict';
// PD2454 TCE 9f5deac3... only. Read-only diagnostic: dumps bounded memory behind
// the Process-argument pointers whose producers are not yet recovered, for two
// Process calls, so static (config-like) blocks can be told apart from per-shot
// ones. No RGB/LUT payloads (see trace.js v4 for those). Never replay addresses.
(() => {
  const hooks = [], contexts = new Map();
  let observer = null, stopped = false, installed = false, processes = 0;
  let sequence = 0, creates = 0;
  const MAX_PROCESS = 2;
  const exports = { vivoNiceTceCreate: 0x38424c, vivoNiceTceProcess: 0x391340,
    vivoNiceTceDestroy: 0x385988, vivoNiceTceSetParam: 0x39751c };
  // Offsets inside the 0x6d0 Process argument; producers per docs/vivo-tce-boundary.md.
  const opaque = [['ref110',0x90],['ref110b',0x98],['ref120',0xa8],['unkA80',0xb0],
    ['scene1910',0x258],['ref140',0x2e0],['unkD70',0x3a0],['unkD78',0x3a8]];
  const hexDigits = Array.from({length: 256}, (_, n) => n.toString(16).padStart(2, '0'));
  function emit(event, fields = {}) {
    console.log('SCAMERA_TCE ' + JSON.stringify({version: 5, event,
      sequence: ++sequence, timeMs: Date.now(), pid: Process.id, ...fields}));
  }
  function stop(reason) {
    if (stopped) return;
    stopped = true;
    for (const h of hooks) { try { h.detach(); } catch (_) {} }
    if (observer) { try { observer.detach(); } catch (_) {} }
    emit('finished', {reason});
  }
  function hex(buffer) { return Array.from(new Uint8Array(buffer), x => hexDigits[x]).join(''); }
  function bytes(address, size) {
    try {
      if (address.isNull()) return {address: '0x0', error: 'null'};
      return {address: address.toString(), size, hex: hex(address.readByteArray(size))};
    } catch (e) { return {address: address.toString(), size, error: String(e)}; }
  }
  // Largest readable prefix among fixed sizes; sizes are collector limits,
  // not claims about the vendor structure size.
  function bounded(address, sizes) {
    if (address.isNull()) return {address: '0x0', error: 'null'};
    for (const size of sizes) {
      try { return {address: address.toString(), size, hex: hex(address.readByteArray(size))}; }
      catch (_) {}
    }
    return {address: address.toString(), error: 'unreadable at 16 bytes'};
  }
  function looksLikePointer(value) {
    const top = value.shr(56).toInt32() & 0xff;
    const low = value.and(ptr('0x00ffffffffffffff'));
    return (top === 0xb4 || top === 0) && low.compare(ptr('0x5000000000')) >= 0 &&
      low.compare(ptr('0x8000000000')) < 0;
  }
  function dumpOpaque(tag, argument, output) {
    const blocks = [];
    for (const [name, offset] of opaque) {
      const address = argument.add(offset).readPointer();
      const block = bounded(address, [4096, 2048, 1024, 512, 256, 64, 16]);
      block.name = name; block.offset = offset;
      const children = [];
      if (block.hex) {
        const base = address, limit = Math.min(block.size, 512);
        for (let at = 0; at + 8 <= limit && children.length < 24; at += 8) {
          const value = base.add(at).readPointer();
          if (!value.isNull() && looksLikePointer(value)) {
            const child = bounded(value, [256, 64, 16]);
            child.at = at; children.push(child);
          }
        }
      }
      block.children = children;
      blocks.push(block);
    }
    let lutPath = null;
    try {
      const p = argument.add(0x3d0).readPointer();
      lutPath = p.isNull() ? null : {address: p.toString(), value: p.readCString(1024)};
    } catch (e) { lutPath = {error: String(e)}; }
    let extra = null;
    try { extra = bounded(output.add(0x2d8).readPointer(), [4096, 1024, 256, 64, 16]); }
    catch (e) { extra = {error: String(e)}; }
    emit('opaque_' + tag, {blocks, lutPath, extraOutput: extra});
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
        emit('create_enter', {id: this.captureId, argument: bytes(args[0], 0x4c8)});
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
    attach(addresses.vivoNiceTceProcess, {
      onEnter(args) {
        if (processes >= MAX_PROCESS) return;
        this.index = ++processes;
        this.argument = args[0]; this.output = args[1];
        const handle = args[2].toString();
        emit('process_enter', {index: this.index, handle, createId: contexts.get(handle) || null,
          argument: bytes(args[0], 0x6d0), outputPrefix: bytes(args[1], 0x2e0)});
        dumpOpaque('enter', args[0], args[1]);
      },
      onLeave(result) {
        if (!this.index) return;
        emit('process_leave', {index: this.index, status: result.toInt32(),
          outputPrefix: bytes(this.output, 0x2e0)});
        dumpOpaque('leave', this.argument, this.output);
        if (this.index >= MAX_PROCESS) setTimeout(() => stop('processes_observed'), 0);
      }
    });
    emit('ready', {module: module.path, base: module.base.toString()});
  }
  if (Process.arch !== 'arm64' || Process.pointerSize !== 8) { stop('unsupported_architecture'); return; }
  emit('started', {frida: Frida.version, runtime: Script.runtime});
  setTimeout(() => stop('180_second_timeout'), 180000);
  try {
    observer = Process.attachModuleObserver({onAdded(module) {
      try { install(module); }
      catch (e) { emit('observer_error', {error: String(e)}); stop('hook_install_failed'); }
    },
      onRemoved(module) { if (installed && module.name === 'libvivo_nicetce.so') stop('module_unloaded'); }});
    if (stopped) observer.detach();
  } catch (e) { emit('observer_error', {error: String(e)}); stop('attach_failed'); }
})();
