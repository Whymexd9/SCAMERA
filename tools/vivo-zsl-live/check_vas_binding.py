#!/usr/bin/env python3
import copy
import struct
from analyze_vas_binding import analyze_bindings


def packed(data):
    return dict(size=len(data), hex=data.hex())


def conversion(identity, timestamp):
    common = dict(pid=1, thread=10, conversionId=identity, source='0x1234')
    header = bytearray(0x78)
    struct.pack_into('<I', header, 0, identity)
    struct.pack_into('<Q', header, 0x10, 123456789)
    request = dict(request='0x5678', requestHeader=packed(header))
    aec = [0.] * 35
    aec[2], aec[6], aec[13], aec[14] = 2., 1., 1., 9995059.
    descriptor = bytearray(0x98)
    struct.pack_into('<i', descriptor, 0x44, 100)
    struct.pack_into('<i', descriptor, 0x54, 15728640)
    return [dict(common, **request, event='vas_conversion_enter',
                 inputFrames=dict(count=1, stride=0x98, data=packed(descriptor))),
            dict(common, event='vas_raw_metadata', success=True, field='vendorAec',
                 callSite='0xdcd2c', size=140, value=packed(struct.pack('<35f', *aec))),
            dict(common, event='vas_conversion_timestamp', success=True,
                 callSite='0xe1950', value=packed(struct.pack('<q', timestamp))),
            dict(common, **request, event='vas_conversion_leave', sourceUnchanged=True)]


first, second = conversion(1, 2**53 + 1), conversion(2, 2**53 + 9)
result = analyze_bindings(first + second)
assert not result['errors'] and not result['incompleteConversions']
assert [r['sensorTimestampNs'] for r in result['bindings']] == [2**53 + 1, 2**53 + 9]
assert [r['vcfFrameNumber'] for r in result['bindings']] == [1, 2]
assert result['bindings'][0]['inputDescriptors'] == [dict(index=0, fd=100, mappingBytes=15728640)]
assert all(r['conversionAssociationEstablished'] and not r['sensorFrameIdentityEstablished']
           for r in result['bindings'])
assert not analyze_bindings(first[:2] + second + first[2:])['errors']
for index, change in [(1, dict(thread=11)), (1, dict(source='0x9999')),
                      (1, dict(success=False)), (2, dict(success=False)),
                      (2, dict(callSite='0xe1954')), (3, dict(sourceUnchanged=False))]:
    broken = copy.deepcopy(first)
    broken[index].update(change)
    result = analyze_bindings(broken)
    assert result['errors'] and not result['bindings'], (index, change)
assert not analyze_bindings(first[:-1])['bindings']
assert analyze_bindings(first[:-1])['incompleteConversions']
assert not analyze_bindings([first[0], first[1], first[3]])['bindings']
assert not analyze_bindings(first[:2] + [first[1]] + first[2:])['bindings']
assert analyze_bindings(first + first)['errors']
from analyze_vas_binding import input_descriptors
for fd, size in [(-1, 1), (1048576, 1), (1, 0), (1, 256 * 1024 * 1024 + 1)]:
    raw = bytearray(0x98)
    struct.pack_into('<i', raw, 0x44, fd)
    struct.pack_into('<i', raw, 0x54, size)
    try:
        input_descriptors(raw, 1)
    except ValueError:
        pass
    else:
        raise AssertionError('Invalid descriptor accepted')
print('PASS: exact timestamps, metadata reuse, nested conversions, wrong thread/source/site, missing/duplicate reads and request replacement')

def delivery(pid, inode, identity):
    return dict(event='delivery_leave', pid=pid, id=identity, queue='0x10', route='future',
                returnBits=1, requestedIds=dict(count=1, stride=4, data=packed(struct.pack('<I', identity))),
                future=dict(count=1, stride=16, data=packed(struct.pack('<QQ', 32, 64))),
                futureIdentity=dict(rows=[dict(index=0, object='0x20', fds=[dict(fd=100,
                    fdinfo=f'ino: {inode}\nsize: 15728640\nexp_name: qcom,system\n')])]))


d1, d2 = delivery(1, 200, 1), delivery(1, 201, 2)
nice = dict(event='nice_input_enter', pid=2, inputId=1, source=6,
            sourceFd=dict(d2['futureIdentity']['rows'][0]['fds'][0], fd=50))
result = analyze_bindings([d1, delivery(2, 999, 3), d2] + first + [nice])
candidates = result['descriptorObservationCandidates'][0]['candidates']
assert len(candidates) == 2
assert not candidates[0]['niceInputs']
assert candidates[1]['niceInputs'][0]['sourceIndex'] == 6
assert not result['descriptorObservationCandidates'][0]['associationEstablished']
print('PASS: same-PID FD observations, cross-PID DMA identity, FD reuse retains all candidates')
