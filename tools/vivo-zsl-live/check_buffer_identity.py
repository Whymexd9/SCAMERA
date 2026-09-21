#!/usr/bin/env python3
from analyze_buffer_identity import analyze_identity, dma_key


def fd(number, inode=100, exporter='qcom_dma_heaps'):
    return dict(fd=number, fdinfo=f'ino: {inode}\nsize: 15728640\nexp_name: {exporter}\n')


queue = dict(event='queue_enter', pid=1832, queue='0x1000', id=1,
             readyIdentity=dict(rows=[dict(id=63, fds=[fd(91)])]))
source = dict(event='nice_input_enter', pid=1934, inputId=1, source=0,
              proc='0x2000', sourceFd=fd(51))
out = analyze_identity([queue, source])
assert out['inputs'][0]['status'] == 'candidate'
assert out['inputs'][0]['queueCandidates'][0]['recordId'] == 63
assert not out['sensorFrameIdentityEstablished']
reused = dict(queue, id=2, readyIdentity=dict(rows=[dict(id=80, fds=[fd(91)])]))
assert analyze_identity([queue, reused, source])['inputs'][0]['status'] == 'ambiguous'
assert analyze_identity([queue, dict(source, sourceFd=fd(91, 101))])['inputs'][0]['status'] == 'unmatched'
assert analyze_identity([queue, dict(source, sourceFd=fd(51, exporter='other'))])['inputs'][0]['status'] == 'unmatched'
assert analyze_identity([queue, dict(source, sourceFd={'error': 'denied'})])['inputs'][0]['status'] == 'unreadable'
assert dma_key(dict(fdinfo='ino: 100\nsize: 100\n')) is None
assert dma_key(dict(fdinfo='ino: 100\nino: 101\nsize: 100\nexp_name: dma\n')) is None
assert dma_key(dict(fdinfo='x'*8193)) is None
assert dma_key(fd(51, inode=0)) is None
print('PASS: cross-process FD differences, object reuse ambiguity, unmatched and unreadable identities')

import struct
ret = dict(event='delivery_leave', pid=1832, queue='0x1000', route='future', returnBits=1,
           requestedIds=dict(count=1, stride=4, data=dict(size=4, hex=struct.pack('<I',444).hex())),
           future=dict(count=1, stride=16, data=dict(size=16, hex=struct.pack('<QQ',0x8000,0x9000).hex())),
           futureIdentity=dict(rows=[dict(index=0, object='0x8000', fds=[fd(102)])]))
r = analyze_identity([queue,ret,source])['inputs'][0]
assert r['queueCandidates'][0]['recordId']==63
assert r['returnedBufferCandidates'][0]['recordId']==444
assert not analyze_identity([dict(ret,returnBits=0),source])['inputs'][0]['returnedBufferCandidates']

# Archive file order must not create cross-process ordering. Reused DMA stays
# visible in full history, while observation windows distinguish preparations.
first_prepare = dict(queue, timeMs=10)
second_prepare = dict(queue, id=2, timeMs=40)
first_return = dict(ret, timeMs=20)
second_return = dict(ret, timeMs=50, requestedIds=dict(
    count=1, stride=4, data=dict(size=4, hex=struct.pack('<I',715).hex())))
first_input = dict(source, timeMs=30)
second_input = dict(source, inputId=2, timeMs=60)
trace = [second_return, first_input, second_prepare, first_return, second_input, first_prepare]
results = analyze_identity(trace)['inputs']
assert [x['recordId'] for x in results[0]['returnedBufferCandidates']] == [444,715]
assert [x['recordId'] for x in results[0]['observedWindowCandidates']] == [444]
assert [x['recordId'] for x in results[1]['observedWindowCandidates']] == [715]
assert results[1]['observedWindowStatus'] == 'candidate'
assert not analyze_identity([first_return, first_input])['inputs'][0]['observedWindowCandidates']
assert not analyze_identity([first_prepare, first_return, dict(source,timeMs=20)])['inputs'][0]['observedWindowCandidates']
assert not analyze_identity([first_prepare, first_return, dict(queue,timeMs=30), first_input])['inputs'][0]['observedWindowCandidates']
assert analyze_identity([queue,ret,source])['inputs'][0]['observedWindowStatus'] == 'time_unavailable'
assert analyze_identity([first_prepare, first_return, dict(second_return,timeMs=21), first_input])['inputs'][0]['observedWindowStatus'] == 'ambiguous'
print('PASS: reused DMA windows, unordered files, future returns, missing prepare/time, equal-ms ordering and retained ambiguity')
ret['futureIdentity']['rows'][0]['object']='0xdead'
assert not analyze_identity([ret,source])['inputs'][0]['returnedBufferCandidates']
print('PASS: current returned ID retained independently of old allocation ID; failed/mismatched return rejected')
