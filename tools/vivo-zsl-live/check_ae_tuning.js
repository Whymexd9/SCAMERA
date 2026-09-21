'use strict';
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const source=fs.readFileSync(__dirname+'/ae-tuning.js','utf8');
function fixture(wrong=false) {
 const memory=new Map(),hooks=new Map(),events=[],timers=[];
 class P {
  constructor(n){this.n=Number(n);}add(n){return new P(this.n+n);}isNull(){return !this.n;}
  equals(p){return p.n===this.n;}toString(){return '0x'+this.n.toString(16);}toInt32(){return this.n|0;}
  readByteArray(n){for(const [a,b] of memory)if(this.n>=a&&this.n+n<=a+b.length)return Uint8Array.from(b.subarray(this.n-a,this.n-a+n)).buffer;throw Error('unreadable');}
  readPointer(){return new P(Buffer.from(this.readByteArray(8)).readBigUInt64LE());}
 }
 vm.runInNewContext(source,{Uint8Array,console:{log(line){events.push(JSON.parse(line.slice(12)));}},
  Process:{id:9,attachModuleObserver(callback){callback.onAdded({name:'com.vivo.stats.aec.so',path:'/vendor/lib64/camera/components/com.vivo.stats.aec.so',base:new P(0x30000000),findExportByName(s){return new P(0x30000000+(wrong?0:s.includes('vivoCalculateExpInfo')?0x178808:s.includes('LookUp')?0x1774d4:0x17a5fc));}});return {detach(){}};}},
  Interceptor:{attach(p,c){hooks.set(p.n,c);return {detach(){hooks.delete(p.n);}};}},setTimeout(fn){timers.push(fn);}});
 function invoke(address,args,thread=1){const h=hooks.get(0x30000000+address),ctx={threadId:thread};h.onEnter.call(ctx,args.map(x=>new P(x)));return result=>h.onLeave.call(ctx,new P(result));}
 return {memory,hooks,events,timers,invoke};
}
let f=fixture();assert.equal(f.hooks.size,3);
function put(a,n){const b=Buffer.alloc(n);f.memory.set(a,b);return b;}
const obj=put(0x100000,0x700),input=put(0x110000,0xe0),output=put(0x120000,0x7c);
const table=put(0x130000,32),rows=put(0x140000,48),exp=put(0x150000,24),factor=put(0x160000,4);
const blur=put(0x170000,16),flags=put(0x180000,0x30),motion=put(0x190000,8);
obj.writeBigUInt64LE(0x180000n,0x600);obj.writeBigUInt64LE(0x190000n,0x5f8);
table.writeUInt32LE(2,4);table.writeBigUInt64LE(0x140000n,16);
const original=Buffer.concat([...f.memory.values()]);
f.invoke(0x1774d4,[0,0x150000,0x130000])(0);
assert(!f.events.some(x=>x.event==='ae_tuning_lookup'));
const end=f.invoke(0x178808,[0x100000,0x110000,0x120000]);
f.invoke(0x1774d4,[0,0x150000,0x130000],2)(0);
assert(!f.events.some(x=>x.event==='ae_tuning_lookup'));
f.invoke(0x1774d4,[0,0x150000,0x130000])(0);
f.invoke(0x17a5fc,[0x100000,0x150000,0x160000,0x130000,0x170000,1])(0);
assert.equal(f.events.filter(x=>x.event==='ae_tuning_table').length,1);
assert.equal(f.events.find(x=>x.event==='ae_tuning_adjust').tableId,1);
const nested=f.invoke(0x178808,[0x100000,0x110000,0x120000]);
f.invoke(0x1774d4,[0,0x150000,0x130000])(0);nested(0);
assert.equal(f.events.filter(x=>x.event==='ae_tuning_lookup').at(-1).solverId,2);
f.invoke(0x1774d4,[0,0x150000,0x130000])(0);end(0);
assert.equal(f.events.filter(x=>x.event==='ae_tuning_lookup').at(-1).solverId,1);
assert.deepEqual(Buffer.concat([...f.memory.values()]),original);
const end2=f.invoke(0x178808,[0x100000,0x110000,0x120000]);
rows[0]=1;f.invoke(0x1774d4,[0,0x150000,0x130000])(0);
assert.equal(f.events.filter(x=>x.event==='ae_tuning_table').length,2);
table.writeUInt32LE(1025,4);f.invoke(0x1774d4,[0,0x150000,0x130000])(0);
assert(f.events.some(x=>x.event==='ae_tuning_error'&&x.error.includes('count')));end2(0);
f.timers[0]();assert.equal(f.hooks.size,0);
const w=fixture(true);assert.equal(w.hooks.size,0);assert(w.events.some(x=>x.reason==='unsupported_binary'));
console.log('PASS: scoped table/adjustment copies, nested/thread isolation, dedup/reuse, bounds, unchanged memory, binary gate and shutdown');
