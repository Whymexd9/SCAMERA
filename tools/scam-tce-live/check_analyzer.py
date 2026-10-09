#!/usr/bin/env python3
"""Payload integrity regressions; no phone execution required."""
import hashlib
import json
from pathlib import Path
import tempfile
from analyze_trace import analyze

with tempfile.TemporaryDirectory() as directory:
    source=Path(directory)/'trace.log'
    events=[]
    for name in ('color-lut','input-rgb16','output-rgb16'):
        events.extend([dict(event='payload_begin',name=name,size=6),
                       dict(event='payload_chunk',name=name,offset=0,hex='010002000300'),
                       dict(event='payload_end',name=name,size=6)])
    events.append(dict(event='finished',reason='one_process_observed'))
    def run(records):
        source.write_text(''.join('SCAMERA_TCE '+json.dumps(r)+'\n' for r in records))
        return analyze(source)
    result=run(events)
    assert result['rgb_pair_complete'] and result['capture_finished']
    assert result['verified_payloads']['color-lut']['sha256']==hashlib.sha256(bytes.fromhex('010002000300')).hexdigest()
    result=run(events[:-2])
    assert not result['rgb_pair_complete'] and not result['capture_finished']
    assert result['capture_errors'][-1]['name']=='output-rgb16'
    assert 'input-rgb16' in result['verified_payloads']
    broken=[dict(r) for r in events]
    broken[7]['offset']=2
    assert not run(broken)['rgb_pair_complete']
    run(events[:7])
    with source.open('a') as f: f.write('SCAMERA_TCE {"event":"payload_chunk","hex":"abc')
    result=analyze(source)
    assert result['capture_errors'] and not result['rgb_pair_complete']
    assert 'output-rgb16' not in result['verified_payloads']
print('PASS: complete pair, digest, missing end, bad offset and truncated JSON; intact earlier payloads retained in report.')
