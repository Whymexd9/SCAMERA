#!/usr/bin/env python3
"""Decode the bounded trace; pointers are observations, never replay inputs."""
import hashlib, json, struct, sys
from pathlib import Path

def analyze(path):
    records=[]
    payloads={}
    errors=[]
    current=None
    with Path(path).open(errors='replace') as stream:
        for number,line in enumerate(stream,1):
            if not line.startswith('SCAMERA_TCE '): continue
            try:
                record=json.loads(line[12:])
                event=record['event']
                if event=='payload_begin':
                    if current: raise ValueError('overlapping payloads')
                    name,size=record['name'],record['size']
                    if name in payloads or type(size) is not int or not 0<size<=192*1024*1024:
                        raise ValueError('invalid or duplicate payload')
                    current=dict(name=name,size=size,offset=0,digest=hashlib.sha256())
                elif event in ('payload_chunk','payload_end'):
                    if not current or record['name']!=current['name']:
                        raise ValueError('unpaired payload record')
                    if event=='payload_chunk':
                        if record['offset']!=current['offset'] or len(record['hex'])>32768:
                            raise ValueError('invalid chunk offset/extent')
                        data=bytes.fromhex(record['hex'])
                        if not data or current['offset']+len(data)>current['size']:
                            raise ValueError('invalid chunk size')
                        current['digest'].update(data)
                        current['offset']+=len(data)
                    else:
                        if record['size']!=current['size'] or current['offset']!=current['size']:
                            raise ValueError('truncated payload')
                        payloads[current['name']]=dict(size=current['size'],sha256=current['digest'].hexdigest())
                        current=None
                elif event=='payload_error':
                    raise ValueError('collector payload error: '+str(record))
                if event!='payload_chunk': records.append(record)
            except (ValueError,KeyError,TypeError) as error:
                errors.append(dict(line=number,error=str(error)))
                break
    if current: errors.append(dict(error='incomplete payload',name=current['name'],
                                   received=current['offset'],expected=current['size']))
    def latest(event): return next((r for r in reversed(records) if r['event']==event),None)
    enter,leave=latest('process_enter'),latest('process_leave')
    result={'records':len(records),'finished':latest('finished'),'process_observed':enter is not None,
            'process_returned':leave is not None,'replayable':False,
            'verified_payloads':payloads,'capture_errors':errors,
            'rgb_pair_complete':not errors and {'input-rgb16','output-rgb16'}<=payloads.keys(),
            'capture_finished':not errors and latest('finished') is not None}
    if not enter: return result
    create=next((r for r in records if r['event']=='create_enter' and r['id']==enter['createId']),None)
    result.update(create_observed=create is not None,status=leave['status'] if leave else None,
                  paths=create['paths'] if create else [])
    result['process_duration_ms']=leave['timeMs']-(latest('native_call_start') or enter)['timeMs'] if leave else None
    result['setparams']=[{'key':r['key'],'block':r.get('block')} for r in records
                        if r['event']=='setparam' and r['handle']==enter['handle']
                        and r['sequence']<enter['sequence']
                        and (not create or r['sequence']>create['sequence'])]
    def block(record,key,size):
        b=record[key]
        if 'error' in b: return None
        data=bytes.fromhex(b['hex'])
        if b['size']!=size or len(data)!=size: raise ValueError('Invalid block extent')
        return data
    def image(data,offset):
        return dict(zip(('format','width','height','reserved'),struct.unpack_from('<4i',data,offset)),
                    planes=[hex(v) for v in struct.unpack_from('<4Q',data,offset+16)],
                    stride=struct.unpack_from('<4i',data,offset+0x30))
    if create: result['create_bytes_valid']=block(create,'argument',0x4c8) is not None
    a=block(enter,'argument',0x6d0)
    if a:
        result['input_image']=image(a,0)
        edge,count,address=struct.unpack_from('<IIQ',a,0x370)
        result['color_lut']={'edge':edge,'element_count':count,'address':hex(address),
                             'element_type':'uint16','payload_captured':payloads.get('color-lut',{}).get('size')==count*2,
                             'extent_consistent':count==3*edge**3,
                             'expected_bytes':6*edge**3}
        result['known_input_fields']={name:struct.unpack_from('<'+fmt,a,offset)[0] for name,offset,fmt in [
            ('luxIndex',0xc0,'i'),('digitalZoom',0x104,'f'),('exposureVal',0x128,'f'),
            ('shortGain',0x134,'f'),('expTime',0x138,'f'),('digitalGain',0x2f0,'f'),
            ('adrcGainSerializerName',0x2f4,'f'),('analogGain',0x2f8,'f'),('sensorMode',0x384,'i')]}
    if leave:
        o=block(leave,'outputPrefix',0x2e0)
        if o: result['output']={name:image(o,offset) for name,offset in [('rgb',0),('rgbDeRaw',0x78),
            ('sky',0xf8),('portrait0',0x170),('portrait1',0x1e8),('portrait2',0x260)]}
    result['payloads']=[r for r in records if r['event'] in ('payload_begin','payload_end','payload_error')]
    result['limitations']='v4 payloads require separate extraction and completeness checks. Opaque scene objects and masks are not fully captured; native context still needs reconstruction.'
    return result
if __name__=='__main__': print(json.dumps(analyze(sys.argv[1]),ensure_ascii=False,indent=2))
