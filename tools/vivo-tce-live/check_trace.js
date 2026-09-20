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
    readByteArray(n){const b=memory.get(this.n);if(!b||b.length<n)throw Error('unreadable');return Uint8Array.from(b.subarray(0,n)).buffer;}
    readPointer(){return new P(0);} // null path fields
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
