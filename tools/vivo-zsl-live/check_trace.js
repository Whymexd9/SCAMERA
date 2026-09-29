'use strict';
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const source=fs.readFileSync(__dirname+'/trace.js','utf8');
function fixture(wrong=false, partial=false, attachFails=false, missingDelivery=false, role=undefined) {
  let observer;
  const memory=new Map(),hooks=new Map(),events=[],timers=[],files=new Map(),closed=[];
  class P {
    constructor(n){this.n=Number(n);}
    add(n){return new P(this.n+n);}sub(p){return new P(this.n-p.n);}
    compare(p){return Math.sign(this.n-p.n);}equals(p){return this.n===p.n;}
    isNull(){return this.n===0;}toString(){return '0x'+this.n.toString(16);}
    toInt32(){return this.n|0;}toUInt32(){return this.n>>>0;}
    readByteArray(n){for(const [a,b] of memory)if(this.n>=a&&this.n+n<=a+b.length)return Uint8Array.from(b.subarray(this.n-a,this.n-a+n)).buffer;throw Error('unreadable');}
    readPointer(){return new P(Buffer.from(this.readByteArray(8)).readBigUInt64LE());}
  }
  const mods=[['libvivo.vas.adapter.vcf.so',0x10000000,0x101f48],['libvcf_session.so',0x20000000,0x12b6f8],['com.vivo.stats.aec.so',0x30000000,0x178808],['libvivo.vaf.algo.nice.so',0x40000000,0x10a24],['libvcf_platform_utils.so',0x50000000,0xa7a88]];
  vm.runInNewContext(source,{SCAMERA_TRACE_ROLE:role,Uint8Array,
    File:class {constructor(path,mode){assert.equal(mode,'r');if(!files.has(path))throw Error('missing fdinfo');this.path=path;}
      readText(n){return files.get(this.path).slice(0,n);}close(){closed.push(this.path);}},
    Process:{arch:'arm64',pointerSize:8,id:7,
    findModuleByName(){return {path:'/vendor/lib64/libvcf_platform_utils.so',base:new P(0x50000000),findExportByName(){return new P(0x50235788);}};},
    attachModuleObserver(o){observer=o;for(const [name,base,offset] of (partial?mods.slice(0,1):mods))o.onAdded({name,path:'/vendor/lib64/'+(name==='com.vivo.stats.aec.so'?'camera/components/':'')+name,base:new P(base),findExportByName(symbol){if(missingDelivery&&symbol.includes('getPastAndNextBuffers'))return null;return new P(base+(symbol.includes('convertRawshotMetadataIn')?0xdbaac:symbol.includes('VASAdapterMetadata11getMetadataEPvjPS0_')?0x12884c:symbol.includes('getMetaDataEPvjS0_m')?0x6155c:symbol.includes('EPKcS2_b')?0xa7cc0:symbol.includes('getPastAndNextBuffers')?0x12bb14:symbol.includes('getPastBuffers')?0x128734:symbol.includes('getNextBuffers')?0x129f50:offset)+(wrong?4:0));}});return{detach(){}};}},
    Interceptor:{attach(p,c){if(attachFails)throw Error('fixture attach failure');hooks.set(p.n,c);return{detach(){}};}},
    console:{log(s){events.push(JSON.parse(s.slice('SCAMERA_ZSL '.length)));}},
    setTimeout(f,ms){timers.push({f,ms});}});
  function invoke(address,args,context={}) {
    const self={threadId:10,...context},h=hooks.get(address);
    h.onEnter.call(self,args.map(a=>new P(a)));
    return r=>h.onLeave?.call(self,new P(r));
  }
  return{memory,events,timers,invoke,P,files,closed,addPlatform(){observer.onAdded({name:'libvcf_platform_utils.so',path:'/vendor/lib64/libvcf_platform_utils.so',base:new P(0x50000000),findExportByName(symbol){return new P(symbol.includes('EPKcS2_b')?0x500a7cc0:0x500a7a88);}});},addNice(){observer.onAdded({name:'libvivo.vaf.algo.nice.so',path:'/vendor/lib64/libvivo.vaf.algo.nice.so',base:new P(0x40000000),findExportByName(){return new P(0x40010a24);}});},addAe(){observer.onAdded({name:'com.vivo.stats.aec.so',path:'/vendor/lib64/camera/components/com.vivo.stats.aec.so',base:new P(0x30000000),findExportByName(){return new P(0x30178808);}});},addQueue(){observer.onAdded({name:'libvcf_session.so',path:'/vendor/lib64/libvcf_session.so',base:new P(0x20000000),findExportByName(symbol){return new P(symbol.includes('getPastAndNextBuffers')?0x2012bb14:symbol.includes('getPastBuffers')?0x20128734:symbol.includes('getNextBuffers')?0x20129f50:0x2012b6f8);}});}};
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
assert.deepEqual(f.events.filter(e=>e.event==='hook_installed').map(e=>e.address).sort(),['0x1006155c','0x100dbaac','0x10101f48','0x1012884c','0x20128734','0x20129f50','0x2012b6f8','0x2012bb14','0x30178808','0x40010a24','0x500a7a88','0x500a7cc0']);
queue.writeBigUInt64LE(0x80000n+48n*257n,0x1c8);queue.writeBigUInt64LE(0x80000n+48n*257n,0x1d0);
f.invoke(0x2012b6f8,[0x60000,4,2,1,0x70000,84,123457,4])(1);
assert(f.events.filter(e=>e.event==='queue_enter').at(-1).ready.error);
const past=Buffer.alloc(24),future=Buffer.alloc(24);
f.memory.set(0x300000,past);f.memory.set(0xa0000,future);
const delivery=f.invoke(0x2012bb14,[0x60000,0x300000,0xa0000,0x70000]);
assert.equal(f.events.at(-1).event,'delivery_enter');
past.writeBigUInt64LE(0x80000n);past.writeBigUInt64LE(0x80020n,8);past.writeBigUInt64LE(0x80020n,16);
future.writeBigUInt64LE(0x80020n);future.writeBigUInt64LE(0x80030n,8);future.writeBigUInt64LE(0x80030n,16);
delivery(1);
assert.equal(f.events.at(-1).past.count,2);assert.equal(f.events.at(-1).future.count,1);
assert.equal(f.events.at(-1).returnBits,1);
const before=f.events.length;f.invoke(0x2012bb14,[0xdead,0x300000,0xa0000,0x70000])(0);
assert.equal(f.events.length,before+2);assert.equal(f.events.at(-1).knownQueue,false);
f.invoke(0x20128734,[0x60000,0x300000,0x70000,83])(1);assert.equal(f.events.at(-1).route,'past');assert.equal(f.events.at(-1).past.count,2);assert.equal(f.events.at(-1).future.count,0);
f.invoke(0x20129f50,[0x60000,0xa0000,0x70000,83])(1);assert.equal(f.events.at(-1).route,'future');assert.equal(f.events.at(-1).future.count,1);assert.equal(f.events.at(-1).past.count,0);
f.timers.find(t=>t.ms===30000).f();assert.equal(f.events.at(-1).reason,'observation_window_complete');
f=fixture(true);assert.equal(f.events.at(-1).reason,'unsupported_binary');assert(!f.events.some(e=>e.event==='ready'));
console.log('PASS: module refusal, paired query/queue, bounded vectors and capture-window completion.');

f=fixture(false,true);
assert(f.events.some(e=>e.event==='nice_ready'));
assert(!f.events.some(e=>e.event==='ready'));
f.timers.find(t=>t.ms===15000).f();
assert.deepEqual(Array.from(f.events.at(-1).missing),['libvcf_platform_utils.so','com.vivo.stats.aec.so','libvivo.vaf.algo.nice.so','libvcf_session.so']);
f.addQueue();assert(!f.events.some(e=>e.event==='ready'));f.addAe();assert(!f.events.some(e=>e.event==='ready'));f.addNice();assert(!f.events.some(e=>e.event==='ready'));f.addPlatform();assert(f.events.some(e=>e.event==='ready'));
f=fixture(false,true);
f.timers.find(t=>t.ms===60000).f();
assert.equal(f.events.at(-1).niceSeen,false);
assert.deepEqual(Array.from(f.events.at(-1).missing),['libvcf_platform_utils.so','com.vivo.stats.aec.so','libvivo.vaf.algo.nice.so','libvcf_session.so']);
f=fixture(false,false,true);
assert.equal(f.events.at(-1).reason,'install_failed');
assert(!f.events.some(e=>e.event==='nice_ready'));
console.log('PASS: partial readiness, delayed queue load, missing-module timeout and attach failure.');

f=fixture(false,false,false,true);assert.equal(f.events.at(-1).reason,'unsupported_binary');assert(!f.events.some(e=>e.event==='ready'));
console.log('PASS: missing delivery export refused before full readiness; unknown queues reported without fabricated association.');

f=fixture();
const aeObject=Buffer.alloc(0x660),aeInput=Buffer.alloc(0xe0),aeOutput=Buffer.alloc(0x7c,0x5a),common=Buffer.alloc(0xb8);
aeInput.writeBigUInt64LE(0x140000n,0xa8);
f.memory.set(0x100000,aeObject);f.memory.set(0x110000,aeInput);
f.memory.set(0x120000,aeOutput);f.memory.set(0x140000,common);
for(let i=0;i<12;i++)f.invoke(0x30178808,[0x100000,0x110000,0x120000])(0);
assert(!f.events.some(e=>e.event==='ae_result'));
f.memory.set(0x10000,Buffer.alloc(0x5000));f.memory.set(0x20000,Buffer.alloc(0xb34));
f.memory.set(0x30000,Buffer.alloc(0x4000));f.memory.set(0x40000,Buffer.alloc(0x48));f.memory.set(0x50000,Buffer.alloc(0x3e48));
f.invoke(0x10101f48,[0x10000,0x20000,0,0x30000,0x40000,0x50000])(0);
let samples=f.events.filter(e=>e.event==='ae_result');
assert.equal(samples.length,8);assert.equal(samples[0].aeId,5);assert(samples.every(e=>e.beforeShutter));
for(let i=0;i<125;i++)f.invoke(0x30178808,[0x100000,0x110000,0x120000])(0);
samples=f.events.filter(e=>e.event==='ae_result');assert.equal(samples.length,128);
assert(samples.every(e=>e.association==='solver_call_only' && e.input.size===0xe0 && e.output.size===0x7c));
assert(samples.every(e=>e.output.hex==='5a'.repeat(0x7c)));
assert(aeOutput.equals(Buffer.alloc(0x7c,0x5a)));
console.log('PASS: AE history capped at 8, post-shutter results capped at 120, native memory unchanged.');

const proc=Buffer.alloc(0x3758,0x3c),cre=Buffer.alloc(20*0x198,0x5a);
f.memory.set(0x200000,proc);f.memory.set(0x210000,cre);
f.invoke(0x40010a24,[0,0x200000,19,2,0x210000])(0);
let mapped=f.events.filter(e=>e.event==='nice_input_leave');
assert.equal(mapped.length,1);assert.equal(mapped[0].source,19);assert.equal(mapped[0].destination,2);
assert.equal(mapped[0].image.address,'0x210330');assert.equal(mapped[0].image.hex,'5a'.repeat(0x198));
assert.equal(f.events.find(e=>e.event==='nice_input_enter').sourceImage.address,'0x2008e8');
f.invoke(0x40010a24,[0,0x200000,20,0,0x210000])(0);
assert(f.events.some(e=>e.event==='nice_input_rejected'));
for(let i=0;i<30;i++)f.invoke(0x40010a24,[0,0x200000,0,0,0x210000])(0);
assert.equal(f.events.filter(e=>e.event==='nice_input_leave').length,24);
assert(proc.equals(Buffer.alloc(0x3758,0x3c)));assert(cre.equals(Buffer.alloc(20*0x198,0x5a)));
console.log('PASS: NICE source/destination association, bounds, 24-call limit, native memory unchanged.');

f=fixture(false,false,false,false,'nice_inputs');
assert.deepEqual(f.events.filter(e=>e.event==='hook_installed').map(e=>e.address),['0x40010a24']);
assert(f.events.some(e=>e.event==='ready'));
f.memory.set(0x200000,proc);f.memory.set(0x210000,cre);
f.invoke(0x40010a24,[0,0x200000,0,0,0x210000])(0);
assert(f.events.some(e=>e.event==='nice_input_leave' && e.role==='nice_inputs'));
assert(!f.events.some(e=>e.event==='nice_enter'));
assert(f.events.some(e=>e.association==='independent_process_not_capture_identity'));
f=fixture(false,false,false,false,'capture');
assert.equal(f.events.filter(e=>e.event==='hook_installed').length,11);
assert(!f.events.some(e=>e.event==='module_ready' && e.name==='libvivo.vaf.algo.nice.so'));
assert(f.events.some(e=>e.event==='ready'));
console.log('PASS: independent input observer records without a local shutter; capture observer excludes NICE algorithm.');

// Native-handle FD numbers differ across processes; preserve fdinfo, never join by FD.
f=fixture(false,false,false,false,'nice_inputs');
const identityProc=Buffer.alloc(0x3758),identityCre=Buffer.alloc(0x198);
identityProc.writeUInt32LE(51,0x68);
f.memory.set(0x200000,identityProc);f.memory.set(0x210000,identityCre);
f.files.set('/proc/self/fdinfo/51','ino: 800123\nsize: 15728640\nexp_name: qcom_dma_heaps\n');
f.invoke(0x40010a24,[0,0x200000,0,0,0x210000])(0);
let identity=f.events.find(e=>e.event==='nice_input_enter').sourceFd;
assert.equal(identity.fd,51);assert(identity.fdinfo.includes('800123'));
assert.equal(identity.association,'buffer_object_only_not_sensor_frame');
assert.deepEqual(f.closed,['/proc/self/fdinfo/51']);
f.files.set('/proc/self/fdinfo/51','x'.repeat(8193));
f.invoke(0x40010a24,[0,0x200000,0,0,0x210000])(0);
assert(f.events.filter(e=>e.event==='nice_input_enter').at(-1).sourceFd.error);
assert.equal(f.closed.length,2);
f=fixture();
f.memory.set(0x10000,Buffer.alloc(0x5000));f.memory.set(0x20000,Buffer.alloc(0xb34));
f.memory.set(0x30000,Buffer.alloc(0x4000));f.memory.set(0x40000,Buffer.alloc(0x48));f.memory.set(0x50000,Buffer.alloc(0x3e48));
f.invoke(0x10101f48,[0x10000,0x20000,0,0x30000,0x40000,0x50000])(0);
const iq=Buffer.alloc(0x330),ir=Buffer.alloc(48),ih=Buffer.alloc(20),iv=Buffer.alloc(24);
iq.writeBigUInt64LE(0x80000n,0x1c0);iq.writeBigUInt64LE(0x80030n,0x1c8);iq.writeBigUInt64LE(0x80030n,0x1d0);
ir.writeBigUInt64LE(0x90000n,0x10);ir.writeUInt32LE(63,0x24);
ih.writeUInt32LE(12);ih.writeUInt32LE(1,4);ih.writeUInt32LE(1,8);ih.writeUInt32LE(91,12);
f.memory.set(0x60000,iq);f.memory.set(0x70000,iv);f.memory.set(0x80000,ir);f.memory.set(0x90000,ih);
f.files.set('/proc/self/fdinfo/91','ino: 800123\nsize: 15728640\nexp_name: qcom_dma_heaps\n');
f.invoke(0x2012b6f8,[0x60000,4,3,1,0x70000,83,123456,4])(1);
let row=f.events.find(e=>e.event==='queue_enter').readyIdentity.rows[0];
assert.equal(row.id,63);assert.equal(row.fds[0].fd,91);assert(row.fds[0].fdinfo.includes('800123'));
ih.writeUInt32LE(9,4);
f.invoke(0x2012b6f8,[0x60000,4,3,1,0x70000,83,123456,4])(1);
row=f.events.filter(e=>e.event==='queue_enter').at(-1).readyIdentity.rows[0];
assert(row.error);assert.equal(row.fds.length,0);
console.log('PASS: bounded fdinfo, closed files, native handle validation and queue record identity.');

f=fixture();
f.memory.set(0x60000,Buffer.alloc(0x330));f.memory.set(0x70000,Buffer.alloc(24));
f.invoke(0x2012b6f8,[0x60000,4,3,1,0x70000,83,123456,4])(1);
assert(f.events.some(e=>e.event==='queue_enter'));
assert(f.events.some(e=>e.event==='queue_observation_started'));
f.memory.set(0x10000,Buffer.alloc(0x5000));f.memory.set(0x20000,Buffer.alloc(0xb34));
f.memory.set(0x30000,Buffer.alloc(0x4000));f.memory.set(0x40000,Buffer.alloc(0x48));f.memory.set(0x50000,Buffer.alloc(0x3e48));
f.invoke(0x10101f48,[0x10000,0x20000,0,0x30000,0x40000,0x50000])(0);
assert.equal(f.events.filter(e=>e.event==='nice_enter').length,1);
f.timers.find(t=>t.ms===60000).f();
assert.equal(f.events.at(-1).reason,'60_second_timeout');assert.equal(f.events.at(-1).niceSeen,true);
f=fixture();
f.memory.set(0x60000,Buffer.alloc(0x330));f.memory.set(0x70000,Buffer.alloc(24));
f.memory.set(0x300000,Buffer.alloc(24));f.memory.set(0xa0000,Buffer.alloc(24));
f.invoke(0x2012bb14,[0x60000,0x300000,0xa0000,0x70000])(1);
assert(f.events.some(e=>e.event==='delivery_leave' && !e.knownQueue));
f.timers.find(t=>t.ms===60000).f();assert.equal(f.events.at(-1).niceSeen,false);
console.log('PASS: prepare and delivery observed without HDR plan; later HDR plan retained; 60-second limit');

f=fixture();
const dq=Buffer.alloc(0x330),dv=Buffer.alloc(24),dr=Buffer.alloc(16),di=Buffer.alloc(24);
const dobj=Buffer.alloc(0xe8),dh=Buffer.alloc(16),slot=Buffer.alloc(8);
dobj.writeBigUInt64LE(0x50235798n);dobj.writeBigUInt64LE(0x530000n,0xd8);
dh.writeUInt32LE(12);dh.writeUInt32LE(1,4);dh.writeUInt32LE(101,12);
f.memory.set(0x500000,dq);f.memory.set(0x510000,dv);f.memory.set(0x520000,dr);
f.memory.set(0x530000,dh);f.memory.set(0x540000,dobj);f.memory.set(0x550000,di);f.memory.set(0x560000,slot);
f.files.set('/proc/self/fdinfo/101','ino: 900999\nsize: 15728640\nexp_name: qcom,system\n');
let done=f.invoke(0x20129f50,[0x500000,0x510000,0x550000,110]);
dv.writeBigUInt64LE(0x520000n);dv.writeBigUInt64LE(0x520010n,8);dv.writeBigUInt64LE(0x520010n,16);
dr.writeBigUInt64LE(0x540000n);done(1);
let returned=f.events.at(-1).futureIdentity.rows[0];
assert.equal(returned.object,'0x540000');assert.equal(returned.fds[0].fd,101);
assert(returned.fds[0].fdinfo.includes('900999'));
dobj.writeBigUInt64LE(0x560000n,0xe0);slot.writeBigUInt64LE(0x530000n);dobj.writeBigUInt64LE(0n,0xd8);
f.invoke(0x20129f50,[0x500000,0x510000,0x550000,111])(1);
assert.equal(f.events.at(-1).futureIdentity.rows[0].fds[0].fd,101);
dobj.writeBigUInt64LE(0x1234n);
f.invoke(0x20129f50,[0x500000,0x510000,0x550000,112])(1);
assert(f.events.at(-1).futureIdentity.rows[0].error.includes('dynamic type'));
console.log('PASS: actual returned buffer, embedded/external handle slots, wrong dynamic type rejected');

const metadataNode=Buffer.alloc(40), sensorValue=Buffer.alloc(8);
metadataNode.writeBigUInt64LE(0x530000n,0x10);
metadataNode.writeBigUInt64LE(0x580000n,0x18);
dq.writeBigUInt64LE(0x570000n,0x300);
f.memory.set(0x570000,metadataNode);
dobj.writeBigUInt64LE(0x50235798n);
f.invoke(0x20129f50,[0x500000,0x510000,0x550000,113])(1);
assert.equal(f.events.at(-1).futureIdentity.rows[0].metadata.object,'0x580000');
metadataNode.writeBigUInt64LE(0x570000n);
f.invoke(0x20129f50,[0x500000,0x510000,0x550000,114])(1);
assert(f.events.at(-1).futureIdentity.rows[0].metadata.error.includes('cycle'));
metadataNode.writeBigUInt64LE(0n);
sensorValue.writeBigInt64LE(16670001234567n);f.memory.set(0x590000,sensorValue);
for(let i=0;i<70;i++)f.invoke(0x500a7a88,[0x580000,0xe0010,0],{context:{x1:new f.P(0x590000)}})(1);
assert(!f.events.some(e=>e.event==='metadata_read'));
f.invoke(0x2012b6f8,[0x500000,4,1,1,0x550000,115,1,4])(1);
let reads=f.events.filter(e=>e.event==='metadata_read');assert.equal(reads.length,64);
assert(reads.every(e=>e.beforeSelection && e.value.hex===sensorValue.toString('hex')));
f.invoke(0x500a7a88,[0x580000,0xe0010,0],{context:{x1:new f.P(0x590000)}})(0);
assert.equal(f.events.at(-1).value,null);
f.invoke(0x500a7a88,[0x580000,0xe0010,0],{context:{x1:new f.P(0)}})(1);
assert(f.events.at(-1).value.error);
f.invoke(0x500a7a88,[0x580000,0xe0002,0],{context:{x1:new f.P(0x590000)}})(1);
assert.equal(f.events.at(-1).value.size,4);
const count=f.events.length;
f.invoke(0x500a7a88,[0x580000,0xdead,0],{context:{x1:new f.P(0x590000)}})(1);
assert.equal(f.events.length,count);
for(let i=0;i<520;i++){sensorValue.writeBigInt64LE(16670001234567n+BigInt(i));f.invoke(0x500a7a88,[0x580000,0xe0010,0],{context:{x1:new f.P(0x590000)}})(1);}
assert.equal(f.events.filter(e=>e.event==='metadata_read' && !e.beforeSelection).length,512);
assert.equal(sensorValue.readBigInt64LE(),16670001234567n+519n);
console.log('PASS: handle/metadata map, cycle refusal, scalar ABI, absent fields, history/live bounds and unchanged native memory');

f.invoke(0x2012b6f8,[0x500000,4,3,1,0x550000,116,2,4])(1);
const beforeSecond=f.events.filter(e=>e.event==='metadata_read').length;
for(let i=0;i<10;i++)f.invoke(0x500a7a88,[0x580000,0xe0010,0],{context:{x1:new f.P(0x590000)}})(1);
assert.equal(f.events.filter(e=>e.event==='metadata_read').length,beforeSecond+1);
f.memory.set(0x5a0000,Buffer.from('vivo.control\0'));
f.memory.set(0x5b0000,Buffer.from('Vivo3rdAlgoAECFrameControl\0'));
const vendorAe=Buffer.alloc(140);vendorAe.writeFloatLE(3601474,14*4);vendorAe.writeFloatLE(27.20884,2*4);
f.memory.set(0x5c0000,vendorAe);
f.invoke(0x500a7cc0,[0x580000,0x5a0000,0x5b0000,0],{context:{x1:new f.P(0x5c0000)}})(35);
assert.equal(f.events.at(-1).tag,'vivo.control.Vivo3rdAlgoAECFrameControl');
assert.equal(f.events.at(-1).value.hex,vendorAe.toString('hex'));
f.invoke(0x500a7cc0,[0x580000,0x5a0000,0x5b0000,0],{context:{x1:new f.P(0)}})(1);
assert.equal(f.events.at(-1).value,null);
const afterVendor=f.events.length;
f.invoke(0x500a7cc0,[0x580000,0,0,0],{context:{x1:new f.P(0)}})(35);
assert.equal(f.events.length,afterVendor);
console.log('PASS: repeated preview reads deduplicated, next preparation restores quota, named vendor AE bounded and copied');

const vasContext={returnAddress:new f.P(0x100dcd2c)};
f.invoke(0x1006155c,[0x600000,0x580000,0x80001234,0x5c0000,140],vasContext)(1);
assert.equal(f.events.at(-1).event,'vas_raw_metadata');
assert.equal(f.events.at(-1).field,'vendorAec');
assert.equal(f.events.at(-1).value.hex,vendorAe.toString('hex'));
f.invoke(0x1006155c,[0x600000,0x580000,0x80001234,0,140],vasContext)(0);
assert.equal(f.events.at(-1).success,false);assert.equal(f.events.at(-1).value,null);
const vasCount=f.events.length;
f.invoke(0x1006155c,[0x600000,0x580000,0x80001234,0,141],vasContext)(1);
f.invoke(0x1006155c,[0x600000,0x580000,0x80001234,0,140],{returnAddress:new f.P(0x100dcd30)})(1);
assert.equal(f.events.length,vasCount);
f.invoke(0x1006155c,[0x600000,0x580000,0xe0010,0x590000,8],{returnAddress:new f.P(0x100dcb00)})(1);
assert.equal(f.events.at(-1).field,'sensorScalar');assert.equal(f.events.at(-1).value.size,8);
for(let i=0;i<260;i++)f.invoke(0x1006155c,[0x600000,0x580000,0x80001234,0x5c0000,140],vasContext)(1);
assert.equal(f.events.filter(e=>e.event==='vas_raw_metadata').length,256);
console.log('PASS: actual VAS call site, successful copy only, extent/caller rejection and 256-call bound');

f=fixture();
f.memory.set(0x60000,Buffer.alloc(0x330));f.memory.set(0x70000,Buffer.alloc(24));
f.invoke(0x2012b6f8,[0x60000,4,3,1,0x70000,1,1,4])(1);
const request=Buffer.alloc(0xb0),rawShot=Buffer.alloc(0x4000),tsSlot=Buffer.alloc(8);
request.writeBigUInt64LE(0x580000n,0xa8);tsSlot.writeBigUInt64LE(0x590000n);
f.memory.set(0x600000,request);f.memory.set(0x610000,rawShot);
f.memory.set(0x620000,tsSlot);f.memory.set(0x590000,sensorValue);f.memory.set(0x5c0000,vendorAe);
const convertArgs=[0,0x600000,0,0,0x610000];
const endConvert=f.invoke(0x100dbaac,convertArgs);
f.invoke(0x1006155c,[0,0x580000,0x81220072,0x5c0000,140],{returnAddress:new f.P(0x100dcd2c)})(1);
assert.equal(f.events.at(-1).conversionId,1);
const tsArgs=[0,0x580000,0xe0010,0x620000];
f.invoke(0x1012884c,tsArgs,{returnAddress:new f.P(0x100e1950)})(1);
assert.equal(f.events.at(-1).event,'vas_conversion_timestamp');assert.equal(f.events.at(-1).conversionId,1);
assert.equal(f.events.at(-1).value.hex,sensorValue.toString('hex'));
const beforeForeign=f.events.length;
f.invoke(0x1012884c,tsArgs,{threadId:11,returnAddress:new f.P(0x100e1950)})(1);
f.invoke(0x1012884c,[0,0x580001,0xe0010,0x620000],{returnAddress:new f.P(0x100e1950)})(1);
f.invoke(0x1012884c,tsArgs,{returnAddress:new f.P(0x100e1954)})(1);
assert.equal(f.events.length,beforeForeign);
const endNested=f.invoke(0x100dbaac,convertArgs);
f.invoke(0x1012884c,tsArgs,{returnAddress:new f.P(0x100e1950)})(0);
assert.equal(f.events.at(-1).conversionId,2);assert.equal(f.events.at(-1).value,null);
endNested(0);endConvert(0);
assert(f.events.at(-1).sourceUnchanged);
const afterConvert=f.events.length;
f.invoke(0x1012884c,tsArgs,{returnAddress:new f.P(0x100e1950)})(1);
assert.equal(f.events.length,afterConvert);
const endChanged=f.invoke(0x100dbaac,convertArgs);request.writeBigUInt64LE(0x580010n,0xa8);endChanged(0);
assert.equal(f.events.at(-1).sourceUnchanged,false);
for(let i=0;i<29;i++)f.invoke(0x100dbaac,convertArgs)(0);
f.invoke(0x100dbaac,convertArgs)(0);
assert.equal(f.events.at(-1).reason,'conversion_limit');
console.log('PASS: scoped RAW AE/timestamp reads, nested calls, thread/source/callsite isolation, failed reads, source replacement and 32-conversion bound');
