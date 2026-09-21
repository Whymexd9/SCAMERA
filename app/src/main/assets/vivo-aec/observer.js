'use strict';
// Read-only, bounded follow-up to v21. No NativeFunction calls or camera writes.
// Completes the truncated params/output snapshots and records only accessors
// actually called within this invocation of the pinned solver.
(() => {
  const hooks=[], stacks=new Map(), accessors=new Set();
  let observer, stopped=false, seen=0, samples=0, emitted=0;
  function emit(event, fields={}) {
    const line='SCAMERA_AE_CONTEXT '+JSON.stringify({event,pid:Process.id,timeMs:Date.now(),...fields});
    if (emitted+line.length>256*1024*1024) { stop('byte_limit'); return; }
    emitted+=line.length;console.log(line);
  }
  function stop(reason) {
    if(stopped)return;stopped=true;
    for(const h of hooks)try{h.detach();}catch(_){}
    if(observer)try{observer.detach();}catch(_){}
    console.log('SCAMERA_AE_CONTEXT '+JSON.stringify({event:'finished',reason,seen,samples}));
  }
  function bytes(p,n) {
    if(p.isNull() || n<0 || n>32768)throw Error('invalid bounded read');
    return Array.from(new Uint8Array(p.readByteArray(n)),b=>b.toString(16).padStart(2,'0')).join('');
  }
  function active(thread) {const s=stacks.get(thread),top=s&&s[s.length-1];return top&&top.selected?top:null;}
  function bank(p) {
    const count=p.add(0x28).readU32(), tables=p.add(0x30).readPointer();
    const blurCount=p.add(0x38).readU32(), blur=p.add(0x40).readPointer();
    if(count<1 || count>16 || blurCount>1024)throw Error('tuning bank extent');
    const copied=[];
    for(let i=0;i<count;i++) {
      const t=tables.add(32*i),n=t.add(4).readU32();
      if(n<2 || n>1024)throw Error('table extent');
      copied.push({index:i,header:bytes(t,32),rows:bytes(t.add(16).readPointer(),24*n)});
    }
    return {header:bytes(p,0x48),tables:copied,blurRows:blurCount?bytes(blur,blurCount*12):''};
  }
  function installAccessors(context) {
    const vt=context.readPointer();
    // Sizes follow the largest load in the recovered EV orchestration.
    for(const [offset,size] of [[0x10,8],[0xc0,12],[0x168,0],[0x298,0x930],
        [0x2a0,0xb0],[0x2c0,0x44],[0x300,0x440],[0x1a0,0]]) {
      const target=vt.add(offset).readPointer(),key=target.toString()+':'+offset;
      if(accessors.has(key))continue;
      if(accessors.size>=64)throw Error('accessor limit');
      accessors.add(key);
      hooks.push(Interceptor.attach(target,{
        onEnter(args) {const s=active(this.threadId);if(s&&s.context.equals(args[0]))this.scope=s;},
        onLeave(ret) {
          if(stopped||!this.scope)return;
          try {emit('accessor',{sample:this.scope.id,thread:this.threadId,offset,
              data:size?bytes(ret,size):null,scalar:size?null:ret.toInt32()});}
          catch(error){emit('error',{sample:this.scope.id,error:String(error)});}
        }
      }));
    }
  }
  function install(m) {
    if(stopped||m.name!=='com.vivo.stats.aec.so'||hooks.length)return;
    if(m.path!=='/vendor/lib64/camera/components/com.vivo.stats.aec.so')return stop('path_mismatch');
    const entry=m.findExportByName('_ZN17VivoRawHdrExpCalc20vivoCalculateExpInfoEP23VivoRawhdrProcessParamsPN4IVAE23VivoRawhdrProcessResultE');
    if(!entry||!entry.equals(m.base.add(0x178808)))return stop('export_mismatch');
    hooks.push(Interceptor.attach(entry,{
      onEnter(args) {
        if(stopped)return;
        const object=args[0],input=args[1];
        const selected=++seen%3===1&&samples<36000;
        const s={selected,id:selected?++samples:0,input,output:args[2]};
        this.scope=s;
        const stack=stacks.get(this.threadId)||[];stack.push(s);stacks.set(this.threadId,stack);
        if(!selected)return;
        try {
          s.context=object.add(0x550).readPointer();
          installAccessors(s.context);
          emit('enter',{sample:s.id,thread:this.threadId,input:bytes(input,0xe8),vtableOffset:s.context.readPointer().sub(m.base).toString(),frameId:s.context.readPointer().equals(m.base.add(0x1f67a0))?s.context.add(0x24f0).readU64().toString():null,
            common:bytes(input.add(0xa8).readPointer(),0xb8),
            motion:bytes(input.add(0xa0).readPointer(),0x65c),
            bank:bank(input.add(0xb0).readPointer()),alternateBank:bank(input.add(0xb8).readPointer()),
            calculator:bytes(object.add(0x5f0),0x74)});
        }catch(error){emit('error',{sample:s.id,error:String(error)});}
      },
      onLeave(ret) {
        if(stopped||!this.scope)return;
        const s=this.scope,stack=stacks.get(this.threadId);
        if(!stack||stack.pop()!==s){stacks.delete(this.threadId);emit('error',{sample:s.id,error:'scope mismatch'});return;}
        if(!stack.length)stacks.delete(this.threadId);
        if(!s.selected)return;
        try {emit('leave',{sample:s.id,thread:this.threadId,returnBits:ret.toInt32(),
            input:bytes(s.input,0xe8),output:bytes(s.output,0x84)});}
        catch(error){emit('error',{sample:s.id,error:String(error)});}
        if(samples>=36000)stop('sample_limit');
      }
    }));
    emit('ready');
  }
  observer=Process.attachModuleObserver({onAdded(m){try{install(m);}catch(error){emit('error',{error:String(error)});stop('install_error');}}});
  setTimeout(()=>stop('session_limit'),1800000);
})();
