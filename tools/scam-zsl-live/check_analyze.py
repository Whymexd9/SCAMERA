#!/usr/bin/env python3
import copy
import struct
import sys
from pathlib import Path
from analyze import analyze, events

trace = events(Path(sys.argv[1]))
report = analyze(trace)
assert report['query'] == dict(past=4, future=1, alternateExposureMode=True)
assert report['producerCatchMode'] == 4
assert [q['catchMode'] for q in report['queues']] == [6, 6]
assert [q['appendedIds'] for q in report['queues']] == [[114,115,116,117]]*2
assert not report['futureDeliveryObserved']

def refs(*addresses):
    data = b''.join(struct.pack('<QQ', a, 0) for a in addresses)
    return dict(count=len(addresses), stride=16, data=dict(size=len(data),hex=data.hex()))

empty_ids = dict(count=0,stride=4,data=None)
a = dict(event='delivery_enter',id=10,queue=report['queues'][0]['queue'],
         past=refs(),future=refs(0x100000000),requestedIds=empty_ids)
b = dict(event='delivery_leave',id=10,queue=a['queue'],returnBits=1,
         past=refs(1,2,3,4),future=refs(0x100000000,0x200000000),requestedIds=empty_ids)
assert analyze(trace+[a,b])['futureDeliveryObserved']
for field,value in [('returnBits',0),('future',refs(0x100000000)),
                    ('future',refs(0x100000000,0)),('future',refs(0x300000000,0x200000000))]:
    failed=copy.deepcopy(b);failed[field]=value
    assert not analyze(trace+[a,failed])['futureDeliveryObserved'],(field,value)
assert not analyze(trace+[a])['futureDeliveryObserved']
print('PASS: real v6 trace decoded; prepare/timeout, failed return, null future, unchanged or replaced vectors do not prove future delivery')

unknown_a=copy.deepcopy(a); unknown_b=copy.deepcopy(b)
unknown_a.update(queue="0xunknown", knownQueue=False, route="future")
unknown_b.update(queue="0xunknown", knownQueue=False, route="future")
assert not analyze(trace+[unknown_a,unknown_b])["futureDeliveryObserved"]
split_a=copy.deepcopy(a); split_b=copy.deepcopy(b)
split_a.update(route="future",knownQueue=True,past=refs())
split_b.update(route="future",knownQueue=True,past=refs())
assert analyze(trace+[split_a,split_b])["futureDeliveryObserved"]
assert analyze(trace+[split_a,split_b])["deliveries"][0]["route"] == "future"
print("PASS: split future delivery recognized; unrelated queue excluded")
