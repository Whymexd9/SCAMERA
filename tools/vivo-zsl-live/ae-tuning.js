'use strict';
(() => {
  const hooks=[], stacks=new Map(), tables=new Map();
  let observer, stopped=false, calls=0, samples=0, emittedBytes=0;
  const name='com.vivo.stats.aec.so';
  function emit(event,fields={}) {
    const line='SCAMERA_ZSL '+JSON.stringify({version:21,event,pid:Process.id,
      timeMs:Date.now(),...fields});
    const size=unescape(encodeURIComponent(line)).length+1;
    if(event!=='ae_tuning_finished' && emittedBytes+size>8*1024*1024) {stop('output_size_limit');return;}
    emittedBytes+=size;console.log(line);
  }
  function stop(reason) {
    if(stopped)return;stopped=true;
    for(const h of hooks)try{h.detach();}catch(_){}
    if(observer)try{observer.detach();}catch(_){}
    emit('ae_tuning_finished',{reason,calls,samples,tables:tables.size});
  }
  function bytes(p,n) {
    if(p.isNull() || n<0 || n>24576)throw Error('invalid bounded read');
    return Array.from(new Uint8Array(p.readByteArray(n)),b=>b.toString(16).padStart(2,'0')).join('');
  }
  function word(p) {
    const b=new Uint8Array(p.readByteArray(4));
    return (b[0]|b[1]<<8|b[2]<<16|b[3]<<24)>>>0;
  }
  function active(thread) {const s=stacks.get(thread);return s && s[s.length-1];}
  function table(p) {
    const count=word(p.add(4)), data=p.add(16).readPointer();
    if(count<2 || count>1024)throw Error('table count outside 2..1024');
    // Header contains native pointers; identity also includes the copied rows.
    const header=bytes(p,32),rows=bytes(data,count*24),key=header+rows;
    if(tables.has(key))return tables.get(key);
    if(tables.size>=128)throw Error('unique table limit');
    const id=tables.size+1;tables.set(key,id);
    emit('ae_tuning_table',{tableId:id,address:p.toString(),header,rows,count,stride:24});
    return id;
  }
  function attach(p,callbacks) {
    const wrapped={};
    for(const [key,fn] of Object.entries(callbacks))wrapped[key]=function(...args) {
      if(stopped)return;
      try{fn.apply(this,args);}catch(error){emit('ae_tuning_error',{thread:this.threadId,error:String(error)});}
    };
    hooks.push(Interceptor.attach(p,wrapped));
  }
  function install(module) {
    if(stopped || module.name!==name || hooks.length)return;
    const exports=[
      ['_ZN17VivoRawHdrExpCalc20vivoCalculateExpInfoEP23VivoRawhdrProcessParamsPN4IVAE23VivoRawhdrProcessResultE',0x178808],
      ['_ZN9CExpTable27VivoExpTableEntryLiteLookUpEP15ExposureSetTypePN18vivoAECExpTable_V320VivoExpTableLiteTypeE',0x1774d4],
      ['_ZN17VivoRawHdrExpCalc21VivoNormalEVExpAdjustEPN4IVAE22VivoRawHdrExposureInfoEPfPN18vivoAECExpTable_V320VivoExpTableLiteTypeEPNS4_23vivoBlurPixelsTableTypeEi',0x17a5fc]
    ];
    if(module.path!=='/vendor/lib64/camera/components/'+name)return stop('unsupported_path');
    const entry=exports.map(([symbol,offset])=>{
      const p=module.findExportByName(symbol);
      if(!p || !p.equals(module.base.add(offset)))throw Error('unsupported AE export '+symbol);
      return p;
    });
    attach(entry[0],{
      onEnter(args) {
        if(calls>=4096)return;
        this.scope={id:++calls,thread:this.threadId,object:args[0],output:args[2],input:bytes(args[1],0xe0)};
        const s=stacks.get(this.threadId)||[];s.push(this.scope);stacks.set(this.threadId,s);
      },
      onLeave(result) {
        if(!this.scope)return;
        const s=stacks.get(this.threadId);
        if(!s || s.pop()!==this.scope) {stacks.delete(this.threadId);throw Error('AE scope mismatch');}
        if(!s.length)stacks.delete(this.threadId);
        emit('ae_tuning_solver',{solverId:this.scope.id,thread:this.threadId,
          input:this.scope.input,output:bytes(this.scope.output,0x7c),returnBits:result.toInt32()});
      }
    });
    attach(entry[1],{
      onEnter(args) {
        const scope=active(this.threadId);if(!scope || samples>=4096)return;
        this.sample={solverId:scope.id,thread:this.threadId,callId:++samples,
          tableId:table(args[2]),before:bytes(args[1],24)};
        this.output=args[1];
      },
      onLeave(result) {
        if(this.sample)emit('ae_tuning_lookup',{...this.sample,after:bytes(this.output,24),returnBits:result.toInt32()});
      }
    });
    attach(entry[2],{
      onEnter(args) {
        const scope=active(this.threadId);if(!scope || !scope.object.equals(args[0]) || samples>=4096)return;
        const blur=args[4],count=word(blur),data=blur.add(8).readPointer();
        if(count>1024)throw Error('blur table exceeds limit');
        const object=args[0],flags=object.add(0x600).readPointer(),motion=object.add(0x5f8).readPointer();
        this.output=args[1];this.factor=args[2];
        this.sample={solverId:scope.id,thread:this.threadId,callId:++samples,
          tableId:table(args[3]),before:bytes(args[1],16),factorBefore:bytes(args[2],4),
          blurCount:count,blurRows:count&&!data.isNull()?bytes(data,count*12):null,
          period:bytes(object.add(0x5f0),4),motion:bytes(motion,8),
          flags:bytes(flags.add(0x28),2),blurDisabled:word(object.add(0x61c)),mode:args[5].toInt32()};
      },
      onLeave(result) {
        if(this.sample)emit('ae_tuning_adjust',{...this.sample,after:bytes(this.output,16),
          factorAfter:bytes(this.factor,4),returnBits:result.toInt32()});
      }
    });
    emit('ae_tuning_ready',{hooks:hooks.length});
  }
  observer=Process.attachModuleObserver({onAdded(module){try{install(module);}catch(error){emit('ae_tuning_error',{error:String(error)});stop('unsupported_binary');}}});
  setTimeout(()=>stop('60_second_limit'),60000);
})();
