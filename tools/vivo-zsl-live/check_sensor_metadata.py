#!/usr/bin/env python3
import struct
from analyze_sensor_metadata import analyze_metadata


def packed(fmt, *values):
    data = struct.pack(fmt, *values)
    return dict(size=len(data), hex=data.hex())


read = dict(event='metadata_read', pid=1, object='0xa', metadataId=1,
            tag=0xe0010, count=1, enteredMs=10, observedMs=11,
            value=packed('<q', 2**53 + 1))
delivery = dict(event='delivery_leave', pid=1, id=2, queue='0xb', timeMs=12,
                route='future', returnBits=1,
                requestedIds=dict(count=1, stride=4, data=packed('<I', 4)),
                future=dict(count=1, stride=16, data=packed('<QQ', 16, 32)),
                futureIdentity=dict(rows=[dict(index=0, object='0x10', metadata=dict(object='0xa'))]))
result = analyze_metadata([delivery, read])
assert result['deliveries'][0]['timestampValues'] == [2**53 + 1]
assert not result['sensorFrameIdentityEstablished'] and not result['errors']
assert not analyze_metadata([dict(delivery, pid=2), read])['deliveries'][0]['timestampValues']
reused = dict(read, metadataId=3, value=packed('<q', 123))
assert analyze_metadata([read, delivery, reused])['deliveries'][0]['multipleTimestampValues']
assert analyze_metadata([dict(read, value=dict(size=8, hex='00'))])['errors']
assert not analyze_metadata([dict(delivery, returnBits=0), read])['deliveries']
assert analyze_metadata([dict(read, value=packed('<q', -1))])['errors']
assert not analyze_metadata([dict(read, count=0)])['metadataObservations']
assert not analyze_metadata([dict(read, tag=0xc0000, value=packed('<i', 0))])['errors']
print('PASS: exact 64-bit timestamps, process isolation, metadata reuse, malformed reads and failed delivery')

from analyze_sensor_metadata import VENDOR_AEC
values=[0.0]*35
values[2],values[6],values[13],values[14]=27.20884,1.620393,1.0,3601474.0
vendor=dict(read,tag=VENDOR_AEC,count=35,value=packed('<35f',*values))
standard=dict(read,tag=0xe0000,value=packed('<q',9999996))
observed=analyze_metadata([delivery,standard,vendor])['deliveries'][0]['observations']
assert observed[0]['value']==9999996
assert observed[1]['value']['exposureTimeNs']==3601474.0
assert observed[1]['value']['rawHex']==vendor['value']['hex']
assert analyze_metadata([dict(vendor,count=1)])['errors']
values[2]=float('nan')
assert analyze_metadata([dict(vendor,value=packed('<35f',*values))])['errors']
print('PASS: vendor AE preserved separately from differing standard exposure; invalid count/gain rejected')

vas=dict(event='vas_raw_metadata',pid=1,vasId=1,source='0xa',field='vendorAec',tag=0x80001234,observedMs=12,success=True,size=140,callSite='0xdcd2c',value=vendor['value'])
parsed=analyze_metadata([vas])
assert parsed['vasRawMetadata'][0]['value']['exposureTimeNs']==3601474
assert not parsed['sensorFrameIdentityEstablished']
assert not analyze_metadata([dict(vas,success=False)])['vasRawMetadata']
assert analyze_metadata([dict(vas,callSite='0xdcd30')])['errors']
print('PASS: VAS vendor copies retain native source; failed copies and wrong call sites cannot supply AE')

from analyze_sensor_metadata import compare_vas_values
long_ae=dict(exposureTimeNs=25578068.0, totalGain=2.7455897331237793,
             digitalGain=1.0, drcGain=1.0)
long_read=dict(parsed['vasRawMetadata'][0], value=long_ae)
image=dict(pid=2, inputId=7, sourceIndex=4,
           ae=dict(exposureMs=25.578067779541016,
                   shortGain=long_ae['totalGain'], analogGain=long_ae['totalGain'],
                   digitalGain=1.0, drcGain=2.5589845180511475))
rows=compare_vas_values([long_read,dict(long_read,vasId=2,source='0xb')],[image])
assert len(rows[0]['numericCandidates'])==2
assert not rows[0]['associationEstablished']
assert all(not c['drcMatches'] for c in rows[0]['numericCandidates'])
assert rows[0]['numericCandidates'][0]['vendorDrc']==1.0
assert not compare_vas_values([long_read],[dict(image,ae=dict(image['ae'],shortGain=1.0))])[0]['numericCandidates']
assert analyze_metadata(iter([vas]))['vasRawMetadata']==parsed['vasRawMetadata']
print('PASS: float32 AE conversion, independent DRC, ambiguous equal values and iterator input')
