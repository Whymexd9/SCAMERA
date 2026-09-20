'use strict';
const fs=require('fs'), vm=require('vm'), assert=require('assert');
const source=fs.readFileSync(__dirname+'/trace.js','utf8');
function fixture(options={}) {
  const events=[], hooks=new Map(), timers=[], memory=new Map(); let detached=0;
  class P {
    constructor(n){this.n=Number(n);}
    add(n){return new P(this.n+n);} sub(p){return new P(this.n-p.n);}
    toUInt32(){return this.n>>>0;} toInt32(){return this.n|0;}
    isNull(){return !this.n;} equals(p){return this.n===p.n;} toString(){return '0x'+this.n.toString(16);}
    readByteArray(n){for(const [base,b] of memory)if(this.n>=base && this.n+n<=base+b.length)return Uint8Array.from(b.subarray(this.n-base,this.n-base+n)).buffer;throw Error('unreadable');}
    readU32(){return Buffer.from(this.readByteArray(4)).readUInt32LE();}
    readS32(){return Buffer.from(this.readByteArray(4)).readInt32LE();}
    readPointer(){if(options.path)return new P(0x5000);return new P(Number(Buffer.from(this.readByteArray(8)).readBigUInt64LE()));}
    readU8(){const i=this.n-0x5000;if(i<0||i>=options.path.length)throw Error('unreadable');return options.path[i];}
    readCString(n){return options.path.subarray(0,n).toString('utf8');}
  }
  const offsets={vivoNiceTceCreate:0x38424c,vivoNiceTceProcess:0x391340,
    vivoNiceTceDestroy:0x385988,vivoNiceTceSetParam:0x39751c};
  const module={name:'libvivo_nicetce.so',path:'/vendor/lib64/libvivo_nicetce.so',base:new P(0x10000000),
    findExportByName(name){return options.wrong?new P(1):this.base.add(offsets[name]);}};
  vm.runInNewContext(source,{Process:{id:7,arch:options.arch||'arm64',pointerSize:8,
      attachModuleObserver(o){o.onAdded(module);return {detach(){detached++;}};}},
    Frida:{version:'mock'},Script:{runtime:'QJS'},Uint8Array,
    Interceptor:{attach(p,c){hooks.set(p.n,c);return {detach(){detached++;}};}},
    console:{log(s){assert(s.startsWith('SCAMERA_TCE '));events.push(JSON.parse(s.slice(12)));}},
    setTimeout(f,ms){timers.push({f,ms});}});
  function invoke(name,args){const h=hooks.get(module.base.n+offsets[name]);const context={threadId:17};h.onEnter?.call(context,args.map(x=>new P(x)));return value=>h.onLeave?.call(context,new P(value));}
  return {events,memory,invoke,timers,detached:()=>detached};
}
let f=fixture();
f.memory.set(0x2000,Buffer.alloc(0x4c8,0x42));
f.memory.set(0x4000,Buffer.alloc(0x6d0,0x51));
f.memory.set(0x6000,Buffer.alloc(0x2e0,0x63));
f.invoke('vivoNiceTceCreate',[0x2000])(0x3000);
const leave=f.invoke('vivoNiceTceProcess',[0x4000,0x6000,0x3000]);
// Another thread enters the same function: no second capture or confused output.
f.invoke('vivoNiceTceProcess',[0x4000,0x6000,0x3000])(0);
leave(0);f.timers.find(t=>t.ms===0).f();
assert.equal(f.events.filter(e=>e.event==='process_enter').length,1);
assert.equal(f.events.find(e=>e.event==='process_enter').createId,1);
assert.equal(f.events.find(e=>e.event==='create_enter').argument.hex.length,0x4c8*2);
assert.equal(f.events.find(e=>e.event==='process_leave').argument.hex.length,0x6d0*2);
assert.equal(f.events.at(-1).reason,'one_process_observed');assert.equal(f.detached(),5);
assert(f.memory.get(0x4000).every(v=>v===0x51));
f=fixture();f.invoke('vivoNiceTceCreate',[0x2000])(0x3000);
assert(f.events.find(e=>e.event==='create_enter').argument.error);
f.invoke('vivoNiceTceDestroy',[0x3000]);
f.invoke('vivoNiceTceProcess',[0x4000,0x6000,0x3000])(3);
assert.equal(f.events.find(e=>e.event==='process_enter').createId,null);
assert.equal(f.events.find(e=>e.event==='process_leave').status,3);
f=fixture({wrong:true});assert.equal(f.events.at(-1).reason,'wrong_binary_layout');
f=fixture({arch:'x64'});assert.equal(f.events.at(-1).reason,'unsupported_architecture');
f=fixture();f.timers.find(t=>t.ms===90000).f();assert.equal(f.events.at(-1).reason,'90_second_timeout');
f=fixture();for(let i=0;i<12;i++)f.invoke('vivoNiceTceCreate',[0x2000])(i+1);
assert.equal(f.events.filter(e=>e.event==='create_enter').length,8);
for(let i=0;i<100;i++)f.invoke('vivoNiceTceSetParam',[1,0,0x4000]);
assert.equal(f.events.filter(e=>e.event==='setparam').length,32);
console.log('PASS: exact extents, call/handle association, concurrent Process, unreadable memory, destroy/reuse, limits, timeout, layout and architecture refusal; mock API only.');
for(const path of [Buffer.from('/vendor/camera3rd/nti/nice_tce\0'),Buffer.from([0])]) {
  f=fixture({path});f.invoke('vivoNiceTceCreate',[0x2000])(0x3000);
  const paths=f.events.find(e=>e.event==='create_enter').paths;
  assert(paths.every(p=>p.value===path.subarray(0,-1).toString()));
}
for(const path of [Buffer.from('no terminator'),Buffer.alloc(1024,65)]) {
  f=fixture({path});f.invoke('vivoNiceTceCreate',[0x2000])(0x3000);
  assert(f.events.find(e=>e.event==='create_enter').paths.every(p=>p.error));
}
console.log('PASS: bounded paths without mapping lookup, empty strings, unreadable boundary and missing terminator.');
f=fixture();f.memory.set(0x8000,Buffer.from([2,0,0,0]));f.memory.set(0x9000,Buffer.alloc(85,65));
f.invoke('vivoNiceTceSetParam',[1,4,0x8000]);
f.invoke('vivoNiceTceSetParam',[1,8,0x9000]);
f.invoke('vivoNiceTceSetParam',[1,5,0xa000]);
let params=f.events.filter(e=>e.event==='setparam');
assert.equal(params[0].block.hex,'02000000');assert.equal(params[1].block.size,85);assert.equal(params[2].block,null);
console.log('PASS: SetParam keys 4/8 exact payload bounds, unknown key not dereferenced.');

f=fixture();
const input=Buffer.alloc(0x6d0),output=Buffer.alloc(0x2e0);
for(const [b,addr] of [[input,0x10000],[output,0x20000]]) {
  b.writeUInt32LE(0x1004,0);b.writeInt32LE(4,4);b.writeInt32LE(3,8);
  b.writeBigUInt64LE(BigInt(addr),0x10);b.writeInt32LE(24,0x30);b.writeInt32LE(3,0x40);
  f.memory.set(addr,Buffer.alloc(72,addr===0x10000?17:29));
}
input.writeUInt32LE(33,0x370);input.writeUInt32LE(107811,0x374);
input.writeBigUInt64LE(0x30000n,0x378);
f.memory.set(0x30000,Buffer.alloc(215622,43));
f.memory.set(0x4000,input);f.memory.set(0x6000,output);
f.invoke('vivoNiceTceProcess',[0x4000,0x6000,1])(0);
assert.equal(f.events.filter(e=>e.event==='payload_end').length,3);
assert.equal(f.events.filter(e=>e.event==='payload_error').length,0);
const chunks=f.events.filter(e=>e.event==='payload_chunk'&&e.name==='color-lut');
assert.equal(Buffer.concat(chunks.map(e=>Buffer.from(e.hex,'hex'))).length,215622);
for(let i=0;i<chunks.length;i++)assert.equal(chunks[i].offset,i*16384);
assert(f.events.findIndex(e=>e.event==='native_call_start')>f.events.findIndex(e=>e.event==='payload_end'&&e.name==='input-rgb16'));
assert(f.events.findIndex(e=>e.event==='payload_begin'&&e.name==='output-rgb16')>f.events.findIndex(e=>e.event==='process_leave'));
fs.writeFileSync('/tmp/scamera-tce-v4-fixture.log',f.events.map(e=>'SCAMERA_TCE '+JSON.stringify(e)).join('\n'));
// Invalid extents cannot initiate a memory read, and a failed native call has no output dump.
f=fixture();input.writeUInt32LE(0xffffffff,0x370);input.writeInt32LE(0x7fffffff,0x30);
f.memory.set(0x4000,input);f.memory.set(0x6000,output);
f.invoke('vivoNiceTceProcess',[0x4000,0x6000,1])(5);
assert.equal(f.events.filter(e=>e.event==='payload_begin').length,0);
assert.equal(f.events.filter(e=>e.event==='payload_error').length,2);
console.log('PASS: bounded RGB/LUT streaming, chunk order, native timing boundary, extent refusal and failed call.');
