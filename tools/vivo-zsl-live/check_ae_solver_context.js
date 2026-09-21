'use strict';
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const source=fs.readFileSync(__dirname+'/ae-solver-context.js','utf8');
function fixture(wrong=false) {
 const memory=new Map(),hooks=new Map(),events=[],timers=[];
 class P {
  constructor(n){this.n=Number(n);}add(n){return new P(this.n+n);}isNull(){return !this.n;}
  equals(p){return p.n===this.n;}toString(){return '0x'+this.n.toString(16);}toInt32(){return this.n|0;}
  readByteArray(n){for(const [a,b] of memory)if(this.n>=a&&this.n+n<=a+b.length)return Uint8Array.from(b.subarray(this.n-a,this.n-a+n)).buffer;throw Error('unreadable');}
  readPointer(){return new P(Buffer.from(this.readByteArray(8)).readBigUInt64LE());}
  readU32(){return Buffer.from(this.readByteArray(4)).readUInt32LE();}
 }
 vm.runInNewContext(source,{Uint8Array,console:{log(line){events.push(JSON.parse(line.slice('SCAMERA_AE_CONTEXT '.length)));}},
  Process:{id:9,attachModuleObserver(cb){cb.onAdded({name:'com.vivo.stats.aec.so',path:'/vendor/lib64/camera/components/com.vivo.stats.aec.so',base:new P(0x30000000),findExportByName(){return new P(0x30000000+(wrong?0:0x178808));}});return {detach(){}};}},
  Interceptor:{attach(p,c){hooks.set(p.n,c);return {detach(){hooks.delete(p.n);}};}},setTimeout(fn){timers.push(fn);}});
 function invoke(address,args,thread=1){const h=hooks.get(address),ctx={threadId:thread};h.onEnter.call(ctx,args.map(x=>new P(x)));return result=>h.onLeave.call(ctx,new P(result));}
 function put(a,n){const b=Buffer.alloc(n);memory.set(a,b);return b;}
 return {memory,hooks,events,timers,invoke,put};
}
const f=fixture(),entry=0x30178808;
const obj=f.put(0x100000,0x700),input=f.put(0x110000,0xe8),output=f.put(0x120000,0x84);
const context=f.put(0x130000,8),vtable=f.put(0x140000,0x400),data=f.put(0x150000,0x1000);
const motion=f.put(0x160000,0x65c),common=f.put(0x170000,0xb8),bank=f.put(0x180000,0x48);
const table=f.put(0x190000,32),rows=f.put(0x1a0000,48);
obj.writeBigUInt64LE(0x130000n,0x550);context.writeBigUInt64LE(0x140000n);
input.writeBigUInt64LE(0x160000n,0xa0);input.writeBigUInt64LE(0x170000n,0xa8);
input.writeBigUInt64LE(0x180000n,0xb0);input.writeBigUInt64LE(0x180000n,0xb8);
bank.writeUInt32LE(1,0x28);bank.writeBigUInt64LE(0x190000n,0x30);
table.writeUInt32LE(2,4);table.writeBigUInt64LE(0x1a0000n,16);
for(const offset of [0x10,0xc0,0x168,0x298,0x2a0,0x2c0,0x300,0x1a0])vtable.writeBigUInt64LE(BigInt(0x200000+offset),offset);
const original=Buffer.concat([...f.memory.values()]);
const end=f.invoke(entry,[0x100000,0x110000,0x120000]);
assert.equal(f.events.filter(e=>e.event==='enter').length,1);
assert.equal(f.events.find(e=>e.event==='enter').input.length,0xe8*2);
const inner=f.invoke(entry,[0x100000,0x110000,0x120000]);
f.invoke(0x200298,[0x130000])(0x150000);inner(0);
assert.equal(f.events.filter(e=>e.event==='accessor').length,0,'unsampled nested call leaked into outer scope');
f.invoke(0x200298,[0x130000],2)(0x150000);
f.invoke(0x200298,[0x130008])(0x150000);
assert.equal(f.events.filter(e=>e.event==='accessor').length,0);
f.invoke(0x200298,[0x130000])(0x150000);end(0);
assert.equal(f.events.filter(e=>e.event==='accessor').length,1);
assert.equal(f.events.find(e=>e.event==='leave').output.length,0x84*2);
assert.deepEqual(Buffer.concat([...f.memory.values()]),original);
for(let i=0;i<28;i++)f.invoke(entry,[0x100000,0x110000,0x120000])(0);
bank.writeUInt32LE(17,0x28);f.invoke(entry,[0x100000,0x110000,0x120000])(0);
assert(f.events.some(e=>e.event==='error'&&e.error.includes('extent')));
f.timers[0]();assert.equal(f.hooks.size,0);
const wrong=fixture(true);assert.equal(wrong.hooks.size,0);assert(wrong.events.some(e=>e.reason==='export_mismatch'));
console.log('PASS: complete extents, read-only copies, scoped accessors, nested/thread isolation, bounds and detach');
