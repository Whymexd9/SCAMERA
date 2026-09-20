#!/usr/bin/env python3
"""Decode the bounded trace; pointers are observations, never replay inputs."""
import json, struct, sys
from pathlib import Path

def analyze(path):
    records=[]
    for line in Path(path).read_text(errors='replace').splitlines():
        if line.startswith('SCAMERA_TCE '): records.append(json.loads(line[12:]))
    def latest(event): return next((r for r in reversed(records) if r['event']==event),None)
    enter,leave=latest('process_enter'),latest('process_leave')
    result={'records':len(records),'finished':latest('finished'),'process_observed':enter is not None,
            'process_returned':leave is not None,'replayable':False}
    if not enter: return result
    create=next((r for r in records if r['event']=='create_enter' and r['id']==enter['createId']),None)
    result.update(create_observed=create is not None,status=leave['status'] if leave else None,
                  paths=create['paths'] if create else [])
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
        result['known_input_fields']={name:struct.unpack_from('<'+fmt,a,offset)[0] for name,offset,fmt in [
            ('luxIndex',0xc0,'i'),('digitalZoom',0x104,'f'),('exposureVal',0x128,'f'),
            ('shortGain',0x134,'f'),('expTime',0x138,'f'),('digitalGain',0x2f0,'f'),
            ('adrcGainSerializerName',0x2f4,'f'),('analogGain',0x2f8,'f'),('sensorMode',0x384,'i')]}
    if leave:
        o=block(leave,'outputPrefix',0x2e0)
        if o: result['output']={name:image(o,offset) for name,offset in [('rgb',0),('rgbDeRaw',0x78),
            ('sky',0xf8),('portrait0',0x170),('portrait1',0x1e8),('portrait2',0x260)]}
    result['limitations']='No pixel/mask/LUT payloads, opaque SetParam payloads not copied; native context still needs reconstruction.'
    return result
if __name__=='__main__': print(json.dumps(analyze(sys.argv[1]),ensure_ascii=False,indent=2))
