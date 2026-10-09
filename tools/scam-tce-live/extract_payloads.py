#!/usr/bin/env python3
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile

NAMES = {'color-lut', 'input-rgb16', 'output-rgb16', 'roi-rects', 'roi-ids',
         'mask-rects', 'mask-valid', 'json-rects', 'json-ids'}

def extract(source, destination):
    destination = Path(destination)
    if destination.exists():
        raise ValueError('Destination already exists')
    staging = Path(tempfile.mkdtemp(prefix='tce-payload-', dir=destination.parent))
    current = None
    completed = {}
    total = 0
    try:
        with Path(source).open() as stream:
            for line in stream:
                if not line.startswith('SCAMERA_TCE '):
                    continue
                r = json.loads(line[12:])
                event = r['event']
                if event == 'payload_error':
                    raise ValueError('Incomplete capture: '+str(r))
                if event == 'payload_begin':
                    name, size = r['name'], r['size']
                    if current or name not in NAMES or name in completed or type(size) is not int or size <= 0:
                        raise ValueError('Invalid payload header')
                    total += size
                    if total > 192*1024*1024:
                        raise ValueError('Capture budget exceeded')
                    current = dict(name=name, size=size, offset=0, digest=hashlib.sha256(),
                                   file=(staging/(name+'.bin')).open('xb'))
                elif event in ('payload_chunk', 'payload_end'):
                    if not current or r['name'] != current['name']:
                        raise ValueError('Unpaired payload record')
                    if event == 'payload_chunk':
                        if r['offset'] != current['offset'] or len(r['hex']) > 32768:
                            raise ValueError('Invalid chunk offset/extent')
                        data = bytes.fromhex(r['hex'])
                        if not data or current['offset']+len(data) > current['size']:
                            raise ValueError('Payload size exceeded')
                        current['file'].write(data)
                        current['digest'].update(data)
                        current['offset'] += len(data)
                    else:
                        if r['size'] != current['size'] or current['offset'] != current['size']:
                            raise ValueError('Truncated payload')
                        current['file'].close()
                        completed[current['name']] = dict(size=current['size'], sha256=current['digest'].hexdigest())
                        current = None
        if current or not {'input-rgb16', 'output-rgb16'} <= completed.keys():
            raise ValueError('Missing complete RGB pair')
        (staging/'manifest.json').write_text(json.dumps(completed, indent=2)+'\n')
        staging.rename(destination)
        return completed
    except BaseException:
        if current:
            current['file'].close()
        shutil.rmtree(staging)
        raise

if __name__ == '__main__':
    print(json.dumps(extract(sys.argv[1], sys.argv[2]), indent=2))
