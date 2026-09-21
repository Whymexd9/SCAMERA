'use strict';
(() => {
  const inputOnly=globalThis.SCAMERA_TRACE_ROLE==='nice_inputs';
  const captureOnly=globalThis.SCAMERA_TRACE_ROLE==='capture';
  const hooks=[], installed=new Set(), queues=new Set(), aeHistory=[], metadataHistory=[];
  let aeCalls=0, aeEmitted=0, niceInputs=0;
  let identityReads=0, queueCalls=0, deliveryCalls=0, metadataCalls=0, metadataEmitted=0, metadataTotal=0;
  const metadataSeen=new Map();
  let vasMetadataCalls=0;
  let conversionCalls=0;
  const conversions=new Map();
  let observer=null, stopped=false, sequence=0, calls=0, selected=false, planSeen=false;
  const libraries={
    'libvcf_platform_utils.so': {
      symbol:'_ZN3vcf12MetadataImpl16getMetadataByTagEjb',offset:0xa7a88
    },
    'libvivo.vas.adapter.vcf.so': {
      symbol:'_ZN28VASAdapterMetadataConvertVCF28getNiceHdrCaptureControlInfoER30VcfVivoCaptureFrameControlInfoP15AdapterMetadataR17QueryToShotParamsR11QueryParamsR20PreviewToQueryParams',
      offset:0x101f48
    },
    'com.vivo.stats.aec.so': {
      symbol:'_ZN17VivoRawHdrExpCalc20vivoCalculateExpInfoEP23VivoRawhdrProcessParamsPN4IVAE23VivoRawhdrProcessResultE',
      offset:0x178808,
      path:'/vendor/lib64/camera/components/com.vivo.stats.aec.so'
    },
    'libvivo.vaf.algo.nice.so': {
      symbol:'_ZN15NICEIntegration15fillInputParamsEP13NICEProcParamiiP15_VNiceCREInArg_',
      offset:0x10a24
    },
    'libvcf_session.so': {
      symbol:'_ZN3vcf11BufferQueue25preparePastAndNextBuffersEjjNS_21CaptureControlUseCaseERNSt3__16vectorIjNS2_9allocatorIjEEEEjmiRNS_20CaptureFrameSyncInfoERNS_18CaptureFrameStatusE',
      offset:0x12b6f8,
      deliverySymbol:'_ZN3vcf11BufferQueue21getPastAndNextBuffersERNSt3__16vectorINS1_10shared_ptrINS_11ImageBufferEEENS1_9allocatorIS5_EEEES9_RNS2_IjNS6_IjEEEE',
      deliveryOffset:0x12bb14
    }
  };
  if(inputOnly) {
    for(const name of Object.keys(libraries))if(name!=='libvivo.vaf.algo.nice.so')delete libraries[name];
  } else if(captureOnly)delete libraries['libvivo.vaf.algo.nice.so'];
  function emit(event,fields={}) {
    console.log('SCAMERA_ZSL '+JSON.stringify({version:20,event,sequence:++sequence,
      timeMs:Date.now(),pid:Process.id,role:inputOnly?'nice_inputs':'capture',...fields}));
  }
  function stop(reason) {
    if(stopped)return;
    stopped=true;
    for(const hook of hooks)try{hook.detach();}catch(_){}
    if(observer)try{observer.detach();}catch(_){}
    emit('finished',{reason,installed:Array.from(installed),
      missing:Object.keys(libraries).filter(name=>!installed.has(name)),niceSeen:planSeen});
  }
  function flushMetadataHistory() {
    for(const sample of metadataHistory)emit('metadata_read',{...sample,beforeSelection:true});
    metadataHistory.length=0;
  }
  function recordMetadata(sample) {
    if(!selected) {
      metadataHistory.push(sample);
      if(metadataHistory.length>64)metadataHistory.shift();
      return;
    }
    const key=sample.object+':'+sample.tag;
    const signature=JSON.stringify([sample.count,sample.value && sample.value.hex,sample.value && sample.value.error]);
    if(metadataSeen.get(key)===signature)return;
    metadataSeen.delete(key);metadataSeen.set(key,signature);
    if(metadataSeen.size>256)metadataSeen.delete(metadataSeen.keys().next().value);
    if(metadataEmitted<512 && metadataTotal<4096) { metadataEmitted++;metadataTotal++;emit('metadata_read',sample); }
  }
  function asciiName(address) {
    let name='';
    for(let i=0;i<96;i++) {
      const value=new Uint8Array(address.add(i).readByteArray(1))[0];
      if(value===0)return name;
      if(value>127)throw Error('non-ASCII metadata name');
      name+=String.fromCharCode(value);
    }
    throw Error('metadata name exceeds limit');
  }
  function block(address,size) {
    try {
      if(address.isNull() || size<0 || size>65536)throw Error('invalid block');
      const bytes=new Uint8Array(address.readByteArray(size));
      if(bytes.length!==size)throw Error('short read');
      return {address:address.toString(),size,hex:Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('')};
    }catch(error){return {address:address.toString(),size,error:String(error)};}
  }
  function vector(address,stride,maxCount=256) {
    try {
      const begin=address.readPointer(),end=address.add(8).readPointer(),capacity=address.add(16).readPointer();
      if(end.compare(begin)<0 || capacity.compare(end)<0)throw Error('reversed vector');
      const length=end.sub(begin).toUInt32();
      if(length>stride*maxCount || !begin.add(length).equals(end) || length%stride)throw Error('unsupported vector extent');
      return {count:length/stride,stride,data:length?block(begin,length):null};
    }catch(error){return {error:String(error)};}
  }
  function word(address) {
    const b=new Uint8Array(address.readByteArray(4));
    return (b[0]|b[1]<<8|b[2]<<16|b[3]<<24)>>>0;
  }
  function fdIdentity(fd) {
    if(!Number.isInteger(fd) || fd<0 || fd>1048575)return {fd,error:'invalid fd'};
    if(identityReads>=1024)return {fd,error:'identity read limit'};
    identityReads++;
    let file=null;
    try {
      file=new File('/proc/self/fdinfo/'+fd,'r');
      const text=file.readText(8193);
      if(text.length>8192)throw Error('fdinfo exceeds limit');
      return {fd,fdinfo:text,association:'buffer_object_only_not_sensor_frame'};
    }catch(error){return {fd,error:String(error)};}
    finally {if(file)try{file.close();}catch(_){}}
  }
  function nativeHandleIdentity(handle) {
    const row={handle:handle.toString(),fds:[]};
    try {
      if(handle.isNull())throw Error('null native handle');
      const version=word(handle),fds=word(handle.add(4)),ints=word(handle.add(8));
      if(version!==12 || fds>8 || ints>128)throw Error('unsupported native handle');
      row.nativeHandle=block(handle,12+4*(fds+ints));
      for(let j=0;j<fds;j++)row.fds.push(fdIdentity(word(handle.add(12+4*j))));
    }catch(error){row.error=String(error);}
    return row;
  }
  function metadataForHandle(queue, handle) {
    try {
      // holdMetadataLocked stores native_handle -> shared_ptr<Metadata>.
      let node=queue.add(0x300).readPointer();
      const visited=new Set(),matches=[];
      while(!node.isNull()) {
        const key=node.toString();
        if(visited.has(key) || visited.size>=256)throw Error('metadata map extent/cycle');
        visited.add(key);
        if(node.add(0x10).readPointer().equals(handle))
          matches.push(node.add(0x18).readPointer().toString());
        node=node.readPointer();
      }
      if(matches.length!==1 || matches[0]==='0x0')throw Error('metadata mapping absent/ambiguous');
      return {object:matches[0],association:'handle_map_at_delivery_only'};
    }catch(error){return {error:String(error)};}
  }
  function deliveredIdentities(address,queue) {
    if(!address)return {rows:[]};
    try {
      const v=vector(address,16);
      if(v.error || v.count>32)throw Error(v.error || 'delivery identity limit');
      if(!v.count)return {rows:[]};
      const module=Process.findModuleByName('libvcf_platform_utils.so');
      const table=module && module.findExportByName('_ZTVN3vcf19PlatformImageBufferE');
      if(!module || module.path!=='/vendor/lib64/libvcf_platform_utils.so' ||
         !table || !table.equals(module.base.add(0x235788)))throw Error('unsupported buffer module');
      const begin=address.readPointer(),rows=[];
      for(let i=0;i<v.count;i++) {
        const object=begin.add(i*16).readPointer();
        const row={index:i,object:object.toString()};
        try {
          if(object.isNull() || !object.readPointer().equals(table.add(16)))
            throw Error('unsupported ImageBuffer dynamic type');
          // getBufferHandle 0x18b890 returns a pointer to the handle slot.
          const external=object.add(0xe0).readPointer();
          const slot=external.isNull()?object.add(0xd8):external;
          const handle=slot.readPointer();
          Object.assign(row,nativeHandleIdentity(handle));
          row.metadata=metadataForHandle(queue,handle);
        }catch(error){row.error=String(error);}
        rows.push(row);
      }
      return {rows,association:'returned_buffer_only_not_sensor_timestamp'};
    }catch(error){return {error:String(error)};}
  }
  function queueIdentities(address) {
    try {
      const v=vector(address,48);
      if(v.error)throw Error(v.error);
      if(v.count>128)throw Error('identity vector exceeds limit');
      const begin=address.readPointer(), rows=[];
      for(let i=0;i<v.count;i++) {
        const record=begin.add(i*48), id=word(record.add(0x24));
        const handle=record.add(0x10).readPointer();
        const row={id,record:block(record,48),...nativeHandleIdentity(handle)};
        rows.push(row);
      }
      return {rows,association:'queue_record_only_not_nice_source'};
    }catch(error){return {error:String(error)};}
  }
  function attach(address,callbacks) {
    const guarded={};
    for(const [key,fn] of Object.entries(callbacks))guarded[key]=function(...args){
      if(stopped)return;
      try{fn.apply(this,args);}catch(error){emit('observer_error',{error:String(error)});}
    };
    emit('hook_installing',{address:address.toString()});
    hooks.push(Interceptor.attach(address,guarded));
    emit('hook_installed',{address:address.toString()});
  }
  function install(module) {
    const spec=libraries[module.name];
    if(!spec || stopped || installed.has(module.name))return;
    emit('module_seen',{name:module.name,path:module.path,base:module.base.toString()});
    const entry=module.findExportByName(spec.symbol);
    emit('symbol_resolved',{name:module.name,symbol:spec.symbol,
      address:entry?entry.toString():null,expected:module.base.add(spec.offset).toString()});
    if(module.path!==(spec.path || '/vendor/lib64/'+module.name) || !entry || !entry.equals(module.base.add(spec.offset))) {
      emit('wrong_module',{name:module.name,path:module.path});stop('unsupported_binary');return;
    }
    if(module.name==='libvcf_platform_utils.so') {
      const fields=new Map([[0xe0010,8],[0xe0000,8],[0xe0002,4],[0xc0000,4]]);
      attach(entry,{
        onEnter(args) {
          const tag=args[1].toUInt32();
          if(!fields.has(tag) || metadataEmitted>=512 || metadataTotal>=4096)return;
          this.metadataId=++metadataCalls;this.metadata=args[0];this.tag=tag;this.enteredMs=Date.now();
        },
        onLeave(result) {
          if(!this.metadataId)return;
          // MetadataImpl returns {count, data} in x0/x1 after validating type.
          const count=result.toUInt32(),data=this.context.x1;
          const sample={metadataId:this.metadataId,thread:this.threadId,enteredMs:this.enteredMs,observedMs:Date.now(),
            object:this.metadata.toString(),tag:this.tag,count,
            value:count===1?block(data,fields.get(this.tag)):null};
          recordMetadata(sample);
        }
      });
      const named=module.findExportByName('_ZN3vcf12MetadataImpl16getMetadataByTagEPKcS2_b');
      if(!named || !named.equals(module.base.add(0xa7cc0))) {
        emit('wrong_metadata_symbol');stop('unsupported_binary');return;
      }
      attach(named,{
        onEnter(args) {
          if(metadataEmitted>=512 || metadataTotal>=4096)return;
          try {
            if(asciiName(args[1])!=='vivo.control' || asciiName(args[2])!=='Vivo3rdAlgoAECFrameControl')return;
          }catch(_){return;}
          this.metadataId=++metadataCalls;this.metadata=args[0];this.enteredMs=Date.now();
        },
        onLeave(result) {
          if(!this.metadataId)return;
          const count=result.toUInt32();
          recordMetadata({metadataId:this.metadataId,thread:this.threadId,
            enteredMs:this.enteredMs,observedMs:Date.now(),object:this.metadata.toString(),
            tag:'vivo.control.Vivo3rdAlgoAECFrameControl',count,
            value:count===35?block(this.context.x1,140):null});
        }
      });
    } else if(module.name==='libvivo.vaf.algo.nice.so') {
      attach(entry,{
        onEnter(args) {
          if((!selected && !inputOnly) || niceInputs>=24)return;
          if(inputOnly && niceInputs===0)emit('input_observation_started',{association:'independent_process_not_capture_identity'});
          const source=args[2].toInt32(),destination=args[3].toInt32();
          if(source<0 || source>=20 || destination<0 || destination>=20) {
            emit('nice_input_rejected',{source,destination});return;
          }
          this.inputId=++niceInputs;this.proc=args[1];this.output=args[4];
          this.source=source;this.destination=destination;
          emit('nice_input_enter',{inputId:this.inputId,thread:this.threadId,
            source,destination,proc:this.proc.toString(),output:this.output.toString(),
            sourceImage:block(this.proc.add(source*0x78),0x78),
            sourceFd:fdIdentity(word(this.proc.add(source*0x78+0x68))),
            sourceAe:block(this.proc.add(0x1b08),0x230),
            parameterHeader:this.inputId===1?block(this.proc,0x3758):null});
        },
        onLeave() {
          if(!this.inputId)return;
          emit('nice_input_leave',{inputId:this.inputId,thread:this.threadId,
            source:this.source,destination:this.destination,proc:this.proc.toString(),
            output:this.output.toString(),image:block(this.output.add(this.destination*0x198),0x198)});
        }
      });
    } else if(module.name==='com.vivo.stats.aec.so') {
      attach(entry,{
        onEnter(args) {
          if(aeEmitted>=120)return;
          this.aeId=++aeCalls;this.output=args[2];this.enterMs=Date.now();
          this.input=block(args[1],0xe0);
          this.flags=block(args[0].add(0x640),0x1c);
          this.common=block(args[1].add(0xa8).readPointer(),0xb8);
        },
        onLeave(result) {
          if(!this.aeId)return;
          const sample={aeId:this.aeId,thread:this.threadId,enterMs:this.enterMs,
            returnBits:result.toInt32(),input:this.input,flags:this.flags,common:this.common,
            output:block(this.output,0x7c),association:'solver_call_only'};
          if(selected){emit('ae_result',sample);aeEmitted++;}
          else {aeHistory.push(sample);if(aeHistory.length>8)aeHistory.shift();}
        }
      });
    } else if(module.name==='libvivo.vas.adapter.vcf.so') {
      const convert=module.findExportByName('_ZN28VASAdapterMetadataConvertVCF24convertRawshotMetadataInEPvR12MetadataInfoRN11VAS_ADAPTER16CameraAlgoParamsER17QueryToShotParams');
      const get=module.findExportByName('_ZN18VASAdapterMetadata11getMetadataEPvjPS0_');
      if(!convert || !convert.equals(module.base.add(0xdbaac)) ||
         !get || !get.equals(module.base.add(0x12884c))) {
        emit('wrong_vas_conversion_symbol');stop('unsupported_binary');return;
      }
      const active=(thread)=>{const stack=conversions.get(thread);return stack && stack[stack.length-1];};
      attach(convert,{
        onEnter(args) {
          if(!selected)return;
          if(conversionCalls>=32){stop('conversion_limit');return;}
          const frame={id:++conversionCalls,request:args[1],source:args[1].add(0xa8).readPointer()};
          let stack=conversions.get(this.threadId);
          if(!stack){stack=[];conversions.set(this.threadId,stack);}
          stack.push(frame);this.rawConversion=frame;
          const input=vector(args[1].add(0x78),0x98,20);
          emit('vas_conversion_enter',{conversionId:frame.id,thread:this.threadId,
            request:frame.request.toString(),source:frame.source.toString(),
            requestHeader:block(frame.request,0x78),inputFrames:input,
            rawDescriptors:block(args[4].add(0x3890),320)});
        },
        onLeave() {
          if(!this.rawConversion)return;
          const frame=this.rawConversion,stack=conversions.get(this.threadId);
          if(!stack || stack.pop()!==frame)throw Error('VAS conversion stack mismatch');
          if(!stack.length)conversions.delete(this.threadId);
          emit('vas_conversion_leave',{conversionId:frame.id,thread:this.threadId,
            request:frame.request.toString(),source:frame.source.toString(),
            sourceUnchanged:frame.source.equals(frame.request.add(0xa8).readPointer()),
            requestHeader:block(frame.request,0x78)});
        }
      });
      attach(get,{
        onEnter(args) {
          const frame=active(this.threadId);
          if(!frame || args[2].toUInt32()!==0xe0010 ||
             !this.returnAddress.equals(module.base.add(0xe1950)) ||
             !args[1].equals(frame.source))return;
          this.rawConversion=frame;this.timestampOutput=args[3];
        },
        onLeave(result) {
          if(!this.rawConversion)return;
          const success=result.toInt32()===1;
          emit('vas_conversion_timestamp',{conversionId:this.rawConversion.id,thread:this.threadId,
            source:this.rawConversion.source.toString(),success,callSite:'0xe1950',
            value:success?block(this.timestampOutput.readPointer(),8):null});
        }
      });
      const copy=module.findExportByName('_ZN28VASAdapterMetadataConvertVCF11getMetaDataEPvjS0_m');
      if(!copy || !copy.equals(module.base.add(0x6155c))) {
        emit('wrong_vas_metadata_symbol');stop('unsupported_binary');return;
      }
      attach(copy,{
        onEnter(args) {
          if(!selected || vasMetadataCalls>=256)return;
          const site=this.returnAddress;
          if(site.compare(module.base.add(0xdbaac))<0 || site.compare(module.base.add(0xe7c40))>=0)return;
          const size=args[4].toUInt32(),tag=args[2].toUInt32();
          if(args[4].toString()!=='0x'+size.toString(16))return;
          const vendor=site.equals(module.base.add(0xdcd2c)) && size===140;
          const scalar=(tag===0xe0010 || tag===0xe0000)?size===8:(tag===0xe0002 && size===4);
          if(!vendor && !scalar)return;
          this.vasId=++vasMetadataCalls;this.vasSource=args[1];this.vasOutput=args[3];
          this.vasTag=tag;this.vasSize=size;this.vasVendor=vendor;
          const frame=active(this.threadId);
          this.conversionId=frame && args[1].equals(frame.source)?frame.id:null;
          this.vasSite=site.sub(module.base).toString();this.vasEntered=Date.now();
        },
        onLeave(result) {
          if(!this.vasId)return;
          const success=result.toInt32()===1;
          emit('vas_raw_metadata',{vasId:this.vasId,thread:this.threadId,conversionId:this.conversionId,
            enteredMs:this.vasEntered,observedMs:Date.now(),source:this.vasSource.toString(),
            tag:this.vasTag,field:this.vasVendor?'vendorAec':'sensorScalar',
            callSite:this.vasSite,size:this.vasSize,success,
            value:success?block(this.vasOutput,this.vasSize):null});
        }
      });
      attach(entry,{
        onEnter(args) {
          if(planSeen)return;
          planSeen=true;selected=true;this.id=++calls;this.control=args[1];this.shot=args[3];this.query=args[4];
          for(const sample of aeHistory)emit('ae_result',{...sample,beforeShutter:true});
          aeHistory.length=0;
          flushMetadataHistory();
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
          setTimeout(()=>stop('observation_window_complete'),30000);
        }
      });
    } else {
      attach(entry,{
        onEnter(args) {
          if(queueCalls>=64)return;
          queueCalls++;
          metadataEmitted=0;metadataSeen.clear();
          if(!selected) {
            selected=true;
            flushMetadataHistory();
            emit('queue_observation_started',{association:'queue_call_without_hdr_plan'});
            for(const sample of aeHistory)emit('ae_result',{...sample,beforeQueue:true});
            aeHistory.length=0;
          }
          this.id=++calls;this.queue=args[0];this.ids=args[4];
          queues.add(this.queue.toString());
          emit('queue_enter',{id:this.id,thread:this.threadId,queue:this.queue.toString(),
            past:args[1].toUInt32(),future:args[2].toUInt32(),useCase:args[3].toUInt32(),
            request:args[5].toUInt32(),timestamp:args[6].toString(),catchMode:args[7].toInt32(),
            requestedIds:vector(this.ids,4),ready:vector(this.queue.add(0x1c0),48),
            pending:vector(this.queue.add(0x1d8),48),
            readyIdentity:queueIdentities(this.queue.add(0x1c0)),
            pendingIdentity:queueIdentities(this.queue.add(0x1d8)),
            preparations:vector(this.queue.add(0x270),144)});
        },
        onLeave(result) {
          if(!this.id)return;
          emit('queue_leave',{id:this.id,thread:this.threadId,returnBits:result.toInt32(),
            requestedIds:vector(this.ids,4),ready:vector(this.queue.add(0x1c0),48),
            pending:vector(this.queue.add(0x1d8),48),
            readyIdentity:queueIdentities(this.queue.add(0x1c0)),
            pendingIdentity:queueIdentities(this.queue.add(0x1d8)),
            preparations:vector(this.queue.add(0x270),144)});
        }
      });

      const routes=[
        {route:'combined',symbol:spec.deliverySymbol,offset:spec.deliveryOffset},
        {route:'past',symbol:'_ZN3vcf11BufferQueue14getPastBuffersERNSt3__16vectorINS1_10shared_ptrINS_11ImageBufferEEENS1_9allocatorIS5_EEEERNS2_IjNS6_IjEEEEj',offset:0x128734},
        {route:'future',symbol:'_ZN3vcf11BufferQueue14getNextBuffersERNSt3__16vectorINS1_10shared_ptrINS_11ImageBufferEEENS1_9allocatorIS5_EEEERNS2_IjNS6_IjEEEEj',offset:0x129f50}
      ];
      const snapshot=address=>address?vector(address,16):{count:0,stride:16,data:null};
      for(const route of routes) {
        const delivery=module.findExportByName(route.symbol);
        if(!delivery || !delivery.equals(module.base.add(route.offset))) {
          emit('wrong_delivery_symbol',{name:module.name,route:route.route});stop('unsupported_binary');return;
        }
        attach(delivery,{
          onEnter(args) {
            if(deliveryCalls>=192)return;
            deliveryCalls++;
            this.id=++calls;this.queue=args[0];
            this.past=route.route==='future'?null:args[1];
            this.future=route.route==='past'?null:args[route.route==='combined'?2:1];
            this.ids=args[route.route==='combined'?3:2];
            this.knownQueue=queues.has(this.queue.toString());
            emit('delivery_enter',{id:this.id,thread:this.threadId,queue:this.queue.toString(),
              route:route.route,knownQueue:this.knownQueue,
              requestArgument:route.route==='combined'?null:args[3].toUInt32(),
              past:snapshot(this.past),future:snapshot(this.future),requestedIds:vector(this.ids,4),
              readyIdentity:queueIdentities(this.queue.add(0x1c0)),
              pendingIdentity:queueIdentities(this.queue.add(0x1d8)),
              preparations:vector(this.queue.add(0x270),144)});
          },
          onLeave(result) {
            if(!this.id)return;
            emit('delivery_leave',{id:this.id,thread:this.threadId,queue:this.queue.toString(),
              route:route.route,knownQueue:this.knownQueue,
              returnBits:result.toInt32(),past:snapshot(this.past),future:snapshot(this.future),
              pastIdentity:deliveredIdentities(this.past,this.queue),futureIdentity:deliveredIdentities(this.future,this.queue),
              requestedIds:vector(this.ids,4)});
          }
        });
      }
    }
    installed.add(module.name);
    emit('module_ready',{name:module.name});
    if(module.name==='libvivo.vas.adapter.vcf.so')emit('nice_ready');
    if(installed.size===Object.keys(libraries).length)emit('ready');
  }
  if(Process.arch!=='arm64' || Process.pointerSize!==8){stop('unsupported_architecture');return;}
  emit('started',{arch:Process.arch,pointerSize:Process.pointerSize});
  setTimeout(()=>{
    if(!stopped)emit('waiting_status',{installed:Array.from(installed),
      missing:Object.keys(libraries).filter(name=>!installed.has(name)),niceSeen:planSeen});
  },15000);
  setTimeout(()=>stop('60_second_timeout'),60000);
  observer=Process.attachModuleObserver({onAdded(module){
    try{install(module);}catch(error){emit('observer_error',{error:String(error)});stop('install_failed');}
  },onRemoved(module){if(installed.has(module.name))stop('module_unloaded');}});
  if(stopped)observer.detach();
})();
