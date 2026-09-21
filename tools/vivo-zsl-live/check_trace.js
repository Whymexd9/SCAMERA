'use strict';
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const source=fs.readFileSync(__dirname+'/trace.js','utf8');
function fixture(wrong=false) {
  const memory=new Map(),hooks=new Map(),events=[],timers=[];
  class P {
    constructor(n){this.n=Number(n);}
    add(n){return new P(this.n+n);}sub(p){return new P(this.n-p.n);}
    compare(p){return Math.sign(this.n-p.n);}equals(p){return this.n===p.n;}
    isNull(){return this.n===0;}toString(){return '0x'+this.n.toString(16);}
    toInt32(){return this.n|0;}toUInt32(){return this.n>>>0;}
    readByteArray(n){for(const [a,b] of memory)if(this.n>=a&&this.n+n<=a+b.length)return Uint8Array.from(b.subarray(this.n-a,this.n-a+n)).buffer;throw Error('unreadable');}
    readPointer(){return new P(Buffer.from(this.readByteArray(8)).readBigUInt64LE());}
  }
  const mods=[['libvivo.vas.adapter.vcf.so',0x10000000,0x101f48],['libvcf_session.so',0x20000000,0x12b6f8]];
  vm.runInNewContext(source,{Uint8Array,Process:{arch:'arm64',pointerSize:8,id:7,
    attachModuleObserver(o){for(const [name,base,offset] of mods)o.onAdded({name,path:'/vendor/lib64/'+name,base:new P(base),findExportByName(){return new P(base+offset+(wrong?4:0));}});return{detach(){}};}},
    Interceptor:{attach(p,c){hooks.set(p.n,c);return{detach(){}};}},
    console:{log(s){events.push(JSON.parse(s.slice('SCAMERA_ZSL '.length)));}},
    setTimeout(f,ms){timers.push({f,ms});}});
  function invoke(address,args,context={}) {
    const self={threadId:10,...context},h=hooks.get(address);
    h.onEnter.call(self,args.map(a=>new P(a)));
    return r=>h.onLeave?.call(self,new P(r));
  }
  return{memory,events,timers,invoke,P};
}
let f=fixture();assert(f.events.some(e=>e.event==='ready'));
f.memory.set(0x10000,Buffer.alloc(0x5000));f.memory.set(0x20000,Buffer.alloc(0xb34));
f.memory.set(0x30000,Buffer.alloc(0x4000));f.memory.set(0x40000,Buffer.alloc(0x48));f.memory.set(0x50000,Buffer.alloc(0x3e48));
const finish=f.invoke(0x10101f48,[0x10000,0x20000,0,0x30000,0x40000,0x50000]);
finish(0);
let enter=f.events.find(e=>e.event==='nice_enter');assert.equal(enter.preview.size,0x3e48);assert.equal(enter.imageEchoWithPast.size,1);
const queue=Buffer.alloc(0x330),vector=Buffer.alloc(24),frames=Buffer.alloc(48);
for(const [buffer,offset] of [[queue,0x1c0],[vector,0]]){
  buffer.writeBigUInt64LE(0x80000n,offset);buffer.writeBigUInt64LE(0x80030n,offset+8);buffer.writeBigUInt64LE(0x80030n,offset+16);
}
f.memory.set(0x60000,queue);f.memory.set(0x70000,vector);f.memory.set(0x80000,frames);
f.invoke(0x2012b6f8,[0x60000,4,2,1,0x70000,83,123456,4])(1);
const event=f.events.find(e=>e.event==='queue_enter');assert.equal(event.ready.count,1);assert.equal(event.pending.count,0);assert.equal(event.request,83);
const stack=Buffer.alloc(0x300),policy=Buffer.alloc(16),readyFrame=Buffer.alloc(40);
stack.writeBigUInt64LE(0xa0000n,0x70);stack.writeInt32LE(4,0x8c);stack.writeInt32LE(4,0x58);
stack.writeBigUInt64LE(0xb0000n,0x200);stack.writeBigUInt64LE(0xb0028n,0x208);stack.writeBigUInt64LE(0xb0028n,0x210);
f.memory.set(0x90000,stack);f.memory.set(0xa0000,policy);f.memory.set(0xb0000,readyFrame);
f.invoke(0x201417e8,[],{context:{sp:new f.P(0x90000),x27:new f.P(0x601c0)}});
const candidates=f.events.find(e=>e.event==='ready_candidates');assert.equal(candidates.frames.count,1);assert.equal(candidates.policy.size,16);
queue.writeBigUInt64LE(0x80000n+48n*257n,0x1c8);queue.writeBigUInt64LE(0x80000n+48n*257n,0x1d0);
f.invoke(0x2012b6f8,[0x60000,4,2,1,0x70000,84,123457,4])(1);
assert(f.events.filter(e=>e.event==='queue_enter').at(-1).ready.error);
f.timers.find(t=>t.ms===10000).f();assert.equal(f.events.at(-1).reason,'capture_window_complete');
f=fixture(true);assert.equal(f.events.at(-1).reason,'unsupported_binary');assert(!f.events.some(e=>e.event==='ready'));
console.log('PASS: module refusal, paired query/queue, bounded vectors and capture-window completion.');
