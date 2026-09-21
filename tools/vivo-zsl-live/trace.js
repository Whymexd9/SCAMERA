'use strict';
(() => {
  const hooks=[], installed=new Set();
  let observer=null, stopped=false, sequence=0, calls=0, selected=false;
  const libraries={
    'libvivo.vas.adapter.vcf.so': {
      symbol:'_ZN28VASAdapterMetadataConvertVCF28getNiceHdrCaptureControlInfoER30VcfVivoCaptureFrameControlInfoP15AdapterMetadataR17QueryToShotParamsR11QueryParamsR20PreviewToQueryParams',
      offset:0x101f48
    },
    'libvcf_session.so': {
      symbol:'_ZN3vcf11BufferQueue25preparePastAndNextBuffersEjjNS_21CaptureControlUseCaseERNSt3__16vectorIjNS2_9allocatorIjEEEEjmiRNS_20CaptureFrameSyncInfoERNS_18CaptureFrameStatusE',
      offset:0x12b6f8
    }
  };
  function emit(event,fields={}) {
    console.log('SCAMERA_ZSL '+JSON.stringify({version:1,event,sequence:++sequence,
      timeMs:Date.now(),pid:Process.id,...fields}));
  }
  function stop(reason) {
    if(stopped)return;
    stopped=true;
    for(const hook of hooks)try{hook.detach();}catch(_){}
    if(observer)try{observer.detach();}catch(_){}
    emit('finished',{reason});
  }
  function block(address,size) {
    try {
      if(address.isNull() || size<0 || size>65536)throw Error('invalid block');
      const bytes=new Uint8Array(address.readByteArray(size));
      if(bytes.length!==size)throw Error('short read');
      return {address:address.toString(),size,hex:Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('')};
    }catch(error){return {address:address.toString(),size,error:String(error)};}
  }
  function vector(address,stride) {
    try {
      const begin=address.readPointer(),end=address.add(8).readPointer(),capacity=address.add(16).readPointer();
      if(end.compare(begin)<0 || capacity.compare(end)<0)throw Error('reversed vector');
      const length=end.sub(begin).toUInt32();
      if(length>stride*256 || !begin.add(length).equals(end) || length%stride)throw Error('unsupported vector extent');
      return {count:length/stride,stride,data:length?block(begin,length):null};
    }catch(error){return {error:String(error)};}
  }
  function attach(address,callbacks) {
    const guarded={};
    for(const [key,fn] of Object.entries(callbacks))guarded[key]=function(...args){
      if(stopped)return;
      try{fn.apply(this,args);}catch(error){emit('observer_error',{error:String(error)});}
    };
    hooks.push(Interceptor.attach(address,guarded));
  }
  function install(module) {
    const spec=libraries[module.name];
    if(!spec || stopped || installed.has(module.name))return;
    const entry=module.findExportByName(spec.symbol);
    if(module.path!='/vendor/lib64/'+module.name || !entry || !entry.equals(module.base.add(spec.offset))) {
      emit('wrong_module',{name:module.name,path:module.path});stop('unsupported_binary');return;
    }
    if(module.name==='libvivo.vas.adapter.vcf.so') {
      attach(entry,{
        onEnter(args) {
          if(selected)return;
          selected=true;this.id=++calls;this.control=args[1];this.shot=args[3];this.query=args[4];
          emit('nice_enter',{id:this.id,thread:this.threadId,
            control:block(this.control,0xb34),preview:block(args[5],0x3e48),
            query:block(this.query,0x48),rawFrames:block(this.shot.add(0x3890),320),
            imageEchoWithPast:block(args[0].add(0x44f5),1)});
        },
        onLeave(result) {
          if(!this.id)return;
          emit('nice_leave',{id:this.id,thread:this.threadId,returnBits:result.toInt32(),
            control:block(this.control,0xb34),query:block(this.query,0x48),
            rawFrames:block(this.shot.add(0x3890),320)});
          setTimeout(()=>stop('capture_window_complete'),10000);
        }
      });
    } else {
      attach(entry,{
        onEnter(args) {
          if(!selected || calls>=64)return;
          this.id=++calls;this.queue=args[0];this.ids=args[4];
          emit('queue_enter',{id:this.id,thread:this.threadId,queue:this.queue.toString(),
            past:args[1].toUInt32(),future:args[2].toUInt32(),useCase:args[3].toUInt32(),
            request:args[5].toUInt32(),timestamp:args[6].toString(),catchMode:args[7].toInt32(),
            requestedIds:vector(this.ids,4),ready:vector(this.queue.add(0x1c0),48),
            pending:vector(this.queue.add(0x1d8),48)});
        },
        onLeave(result) {
          if(!this.id)return;
          emit('queue_leave',{id:this.id,thread:this.threadId,returnBits:result.toInt32(),
            requestedIds:vector(this.ids,4),ready:vector(this.queue.add(0x1c0),48),
            pending:vector(this.queue.add(0x1d8),48)});
        }
      });
      attach(module.base.add(0x1417e8),{
        onEnter() {
          if(!selected || calls>=64)return;
          const sp=this.context.sp;
          emit('ready_candidates',{thread:this.threadId,
            frames:vector(sp.add(0x200),40),requested:block(sp.add(0x8c),4),
            mode:block(sp.add(0x58),4),timestamp:block(sp.add(0x80),8),
            policy:block(sp.add(0x70).readPointer(),16),
            originalQueue:vector(this.context.x27,48)});
        }
      });
    }
    installed.add(module.name);
    emit('module_ready',{name:module.name});
    if(installed.size===2)emit('ready');
  }
  if(Process.arch!=='arm64' || Process.pointerSize!==8){stop('unsupported_architecture');return;}
  emit('started');
  setTimeout(()=>stop('90_second_timeout'),90000);
  observer=Process.attachModuleObserver({onAdded(module){
    try{install(module);}catch(error){emit('observer_error',{error:String(error)});stop('install_failed');}
  },onRemoved(module){if(installed.has(module.name))stop('module_unloaded');}});
  if(stopped)observer.detach();
})();
