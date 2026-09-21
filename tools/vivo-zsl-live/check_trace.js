'use strict';
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const source=fs.readFileSync(__dirname+'/trace.js','utf8');
function fixture(wrong=false, partial=false, attachFails=false, missingDelivery=false) {
  let observer;
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
    attachModuleObserver(o){observer=o;for(const [name,base,offset] of (partial?mods.slice(0,1):mods))o.onAdded({name,path:'/vendor/lib64/'+name,base:new P(base),findExportByName(symbol){if(missingDelivery&&symbol.includes('getPastAndNextBuffers'))return null;return new P(base+(symbol.includes('getPastAndNextBuffers')?0x12bb14:symbol.includes('getPastBuffers')?0x128734:symbol.includes('getNextBuffers')?0x129f50:offset)+(wrong?4:0));}});return{detach(){}};}},
    Interceptor:{attach(p,c){if(attachFails)throw Error('fixture attach failure');hooks.set(p.n,c);return{detach(){}};}},
    console:{log(s){events.push(JSON.parse(s.slice('SCAMERA_ZSL '.length)));}},
    setTimeout(f,ms){timers.push({f,ms});}});
  function invoke(address,args,context={}) {
    const self={threadId:10,...context},h=hooks.get(address);
    h.onEnter.call(self,args.map(a=>new P(a)));
    return r=>h.onLeave?.call(self,new P(r));
  }
  return{memory,events,timers,invoke,P,addQueue(){observer.onAdded({name:'libvcf_session.so',path:'/vendor/lib64/libvcf_session.so',base:new P(0x20000000),findExportByName(symbol){return new P(symbol.includes('getPastAndNextBuffers')?0x2012bb14:symbol.includes('getPastBuffers')?0x20128734:symbol.includes('getNextBuffers')?0x20129f50:0x2012b6f8);}});}};
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
assert.deepEqual(f.events.filter(e=>e.event==='hook_installed').map(e=>e.address).sort(),['0x10101f48','0x20128734','0x20129f50','0x2012b6f8','0x2012bb14']);
queue.writeBigUInt64LE(0x80000n+48n*257n,0x1c8);queue.writeBigUInt64LE(0x80000n+48n*257n,0x1d0);
f.invoke(0x2012b6f8,[0x60000,4,2,1,0x70000,84,123457,4])(1);
assert(f.events.filter(e=>e.event==='queue_enter').at(-1).ready.error);
const past=Buffer.alloc(24),future=Buffer.alloc(24);
f.memory.set(0x90000,past);f.memory.set(0xa0000,future);
const delivery=f.invoke(0x2012bb14,[0x60000,0x90000,0xa0000,0x70000]);
assert.equal(f.events.at(-1).event,'delivery_enter');
past.writeBigUInt64LE(0x80000n);past.writeBigUInt64LE(0x80020n,8);past.writeBigUInt64LE(0x80020n,16);
future.writeBigUInt64LE(0x80020n);future.writeBigUInt64LE(0x80030n,8);future.writeBigUInt64LE(0x80030n,16);
delivery(1);
assert.equal(f.events.at(-1).past.count,2);assert.equal(f.events.at(-1).future.count,1);
assert.equal(f.events.at(-1).returnBits,1);
const before=f.events.length;f.invoke(0x2012bb14,[0xdead,0x90000,0xa0000,0x70000])(0);
assert.equal(f.events.length,before+2);assert.equal(f.events.at(-1).knownQueue,false);
f.invoke(0x20128734,[0x60000,0x90000,0x70000,83])(1);assert.equal(f.events.at(-1).route,'past');assert.equal(f.events.at(-1).past.count,2);assert.equal(f.events.at(-1).future.count,0);
f.invoke(0x20129f50,[0x60000,0xa0000,0x70000,83])(1);assert.equal(f.events.at(-1).route,'future');assert.equal(f.events.at(-1).future.count,1);assert.equal(f.events.at(-1).past.count,0);
f.timers.find(t=>t.ms===30000).f();assert.equal(f.events.at(-1).reason,'observation_window_complete');
f=fixture(true);assert.equal(f.events.at(-1).reason,'unsupported_binary');assert(!f.events.some(e=>e.event==='ready'));
console.log('PASS: module refusal, paired query/queue, bounded vectors and capture-window completion.');

f=fixture(false,true);
assert(f.events.some(e=>e.event==='nice_ready'));
assert(!f.events.some(e=>e.event==='ready'));
f.timers.find(t=>t.ms===15000).f();
assert.deepEqual(Array.from(f.events.at(-1).missing),['libvcf_session.so']);
f.addQueue();assert(f.events.some(e=>e.event==='ready'));
f=fixture(false,true);
f.timers.find(t=>t.ms===90000).f();
assert.equal(f.events.at(-1).niceSeen,false);
assert.deepEqual(Array.from(f.events.at(-1).missing),['libvcf_session.so']);
f=fixture(false,false,true);
assert.equal(f.events.at(-1).reason,'install_failed');
assert(!f.events.some(e=>e.event==='nice_ready'));
console.log('PASS: partial readiness, delayed queue load, missing-module timeout and attach failure.');

f=fixture(false,false,false,true);assert.equal(f.events.at(-1).reason,'unsupported_binary');assert(!f.events.some(e=>e.event==='ready'));
console.log('PASS: missing delivery export refused before full readiness; unknown queues reported without fabricated association.');
