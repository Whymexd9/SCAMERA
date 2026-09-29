#!/usr/bin/env python3
import argparse
import json
import math
import struct
from pathlib import Path
from analyze import block, events

FIELDS = {'exposureMs': (0, 0x78), 'ev': (0x50, 0x7c),
          'shortGain': (0xf0, 0xb0), 'analogGain': (0x140, 0xb4),
          'digitalGain': (0x190, 0xb8), 'drcGain': (0x1e0, 0xbc)}


def analyze_inputs(observations):
    pending, rows, errors = {}, [], []
    for event in observations:
        if event.get('event') not in ('nice_input_enter', 'nice_input_leave'):
            continue
        key = (event['pid'], event['inputId'])
        if event['event'] == 'nice_input_enter':
            if key in pending:
                raise ValueError('Duplicate NICE input entry')
            pending[key] = event
            continue
        entered = pending.pop(key, None)
        try:
            if entered is None or any(entered[k] != event[k]
                    for k in ('source', 'destination', 'proc', 'output', 'thread')):
                raise ValueError('Unmatched NICE input entry/return')
            source, destination = event['source'], event['destination']
            if not 0 <= source < 20 or not 0 <= destination < 20:
                raise ValueError('Invalid NICE image index')
            ae, image = block(entered['sourceAe'], 0x230), block(event['image'], 0x198)
            values, matches = {}, {}
            for name, (src, dst) in FIELDS.items():
                value = struct.unpack_from('<f', image, dst)[0]
                if not math.isfinite(value):
                    raise ValueError('Nonfinite NICE AE value')
                values[name] = value
                matches[name] = ae[src + 4*source:src + 4*source + 4] == image[dst:dst + 4]
            descriptor = block(entered['sourceImage'], 0x78)
            rows.append(dict(pid=key[0], inputId=key[1], proc=event['proc'], output=event['output'],
                             sourceIndex=source, destinationIndex=destination, ae=values,
                             enterMs=entered.get('timeMs'), returnMs=event.get('timeMs'),
                             captureAssociation='not_established',
                             aeCopyMatches=matches,
                             planePointerCopyMatches=descriptor[0x18:0x20] == image[0x38:0x40]))
        except (ValueError, KeyError) as failure:
            errors.append(dict(pid=key[0], inputId=key[1], error=str(failure)))
    return dict(inputs=rows, errors=errors, missingReturns=len(pending),
                rawPixelsCollected=False, sensorFrameIdentityEstablished=False)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('archive', type=Path)
    print(json.dumps(analyze_inputs(events(parser.parse_args().archive)), indent=2, allow_nan=False))
