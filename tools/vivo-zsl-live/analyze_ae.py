#!/usr/bin/env python3
import argparse
import json
import math
import struct
from pathlib import Path
from analyze import analyze, block, events


def decode_result(event):
    if event.get('association') != 'solver_call_only' or event.get('returnBits') != 0:
        raise ValueError('Not a successful stock solver observation')
    data = block(event.get('output'), 0x7c)
    source = block(event.get('input'), 0xe0)
    common = block(event.get('common'), 0xb8)
    records = []
    for slot in range(6):
        shutter, gain, ev, flags = struct.unpack_from('<3fI', data, slot*16)
        if not all(math.isfinite(v) for v in (shutter, gain, ev)):
            raise ValueError('Nonfinite solver record')
        active = shutter > 0 and gain > 0
        records.append(dict(slot=slot, shutterNative=shutter, gain=gain,
                            ev=ev, flags=flags, active=active))
    if not records[0]['active']:
        raise ValueError('Missing positive reference exposure')
    reference = records[0]['shutterNative'] * records[0]['gain']
    for record in records:
        if record['active']:
            record['productEv'] = math.log2(record['shutterNative']*record['gain']/reference)
    mode = struct.unpack_from('<i', source, 0xc4)[0]
    return dict(aeId=event['aeId'], enterMs=event['enterMs'], mode=mode, records=records,
                tuningGaps=list(struct.unpack_from('<3f',common,0x8c if mode==10 else 0x80)))


def analyze_ae(trace):
    capture = analyze(trace)
    shutter = next(e for e in trace if e['event']=='nice_enter' and e['pid']==capture['pid'])
    observations = [decode_result(e) for e in trace if e['event']=='ae_result' and e['pid']==capture['pid']]
    if not observations:
        raise ValueError('No AE observations for capture process')
    previous = [e for e in observations if e['enterMs']<=shutter['timeMs']]
    closest = max(previous,key=lambda e:e['enterMs']) if previous else None
    if closest:
        closest['relativeToShutterMs'] = closest['enterMs']-shutter['timeMs']
    mapping = {0.:0,-100.:1,-200.:2,100.:4}
    planned = []
    for frame in capture['frames']:
        if frame['direction'] != 1:
            continue
        code = frame['ev']
        planned.append(dict(code=code,solverSlot=mapping.get(code) if capture['query']['alternateExposureMode'] else None,
                            echo=capture['query']['alternateExposureMode'] and code==101.))
    return dict(observations=len(observations),captureQuery=capture['query'],futurePlan=planned,
                nearestPreShutterSolver=closest,futureDeliveryObserved=capture['futureDeliveryObserved'],
                measuredRawAssociation=False,
                limitations=['Solver records are candidates, not the list of captured frames.',
                             'Time proximity does not prove frame identity or the chosen solver result.',
                             'Linear gain is not Camera2 ISO; no ISO conversion is inferred.'])


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('archive',type=Path)
    print(json.dumps(analyze_ae(events(parser.parse_args().archive)),indent=2,allow_nan=False))
