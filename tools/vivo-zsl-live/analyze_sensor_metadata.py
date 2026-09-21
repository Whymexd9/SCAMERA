#!/usr/bin/env python3
import argparse
import json
import math
import struct
from pathlib import Path
from analyze import block, events, references, words
from analyze_nice_inputs import analyze_inputs

FIELDS = {0xe0010: ('sensorTimestampNs', 8), 0xe0000: ('exposureTimeNs', 8),
          0xe0002: ('sensitivity', 4), 0xc0000: ('requestFrameCount', 4)}
VENDOR_AEC = 'vivo.control.Vivo3rdAlgoAECFrameControl'


def compare_vas_values(vas_reads, nice_inputs):
    """Numeric diagnostics only: equal AE is not a buffer/capture identity."""
    def f32(value):
        return struct.unpack('<f', struct.pack('<f', value))[0]

    rows = []
    for image in nice_inputs:
        matches = []
        for read in vas_reads:
            if read['field'] != 'vendorAec':
                continue
            value = read['value']
            try:
                converted = dict(exposureMs=f32(value['exposureTimeNs'] / 1000000),
                                 shortGain=value['totalGain'],
                                 analogGain=f32(value['totalGain'] / value['digitalGain']),
                                 digitalGain=value['digitalGain'])
            except (OverflowError, ZeroDivisionError):
                continue
            if not all(image['ae'][name] == v for name, v in converted.items()):
                continue
            matches.append(dict(pid=read['pid'], vasId=read['vasId'],
                                source=read['source'], observedMs=read['observedMs'],
                                vendorDrc=value['drcGain'],
                                drcMatches=value['drcGain'] == image['ae']['drcGain']))
        rows.append(dict(pid=image['pid'], inputId=image['inputId'],
                         sourceIndex=image['sourceIndex'], niceAe=image['ae'],
                         numericCandidates=matches, associationEstablished=False))
    return rows


def analyze_metadata(trace):
    trace = list(trace)
    observations, errors, deliveries, vas_reads = {}, [], [], []
    for event in trace:
        if event.get('event') == 'vas_raw_metadata':
            if not event.get('success'):
                continue
            try:
                size = event['size']
                if size not in (4, 8, 140):
                    raise ValueError('Invalid VAS metadata extent')
                data = block(event['value'], size)
                if len(data) != size:
                    raise ValueError('VAS metadata copy length mismatch')
                if event['field'] == 'vendorAec':
                    if size != 140 or event['callSite'] != '0xdcd2c':
                        raise ValueError('Unverified VAS AEC call site')
                    aec = struct.unpack('<35f', data)
                    value = {name: aec[i] for name, i in
                             {'lux': 0, 'totalGain': 2, 'drcGain': 6,
                              'digitalGain': 13, 'exposureTimeNs': 14}.items()}
                    if any(not math.isfinite(v) or (name != 'lux' and v <= 0)
                           for name, v in value.items()):
                        raise ValueError('Invalid VAS AEC values')
                else:
                    _, expected_size = FIELDS[event['tag']]
                    if size != expected_size:
                        raise ValueError('Invalid VAS scalar size')
                    value = int.from_bytes(data, 'little', signed=True)
                vas_reads.append(dict(pid=event['pid'], vasId=event['vasId'],
                                      source=event['source'], field=event['field'], tag=event['tag'],
                                      observedMs=event['observedMs'], value=value, rawHex=data.hex()))
            except (KeyError, TypeError, ValueError) as error:
                errors.append(dict(vasId=event.get('vasId'), error=str(error)))
            continue
        if event.get('event') != 'metadata_read':
            continue
        try:
            if event['tag'] == VENDOR_AEC:
                if event['count'] != 35:
                    raise ValueError('Vendor AE count is not 35 floats')
                data = block(event['value'], 140)
                if len(data) != 140:
                    raise ValueError('Invalid vendor AE extent')
                aec = struct.unpack('<35f', data)
                indices = {'lux': 0, 'totalGain': 2, 'drcGain': 6,
                           'digitalGain': 13, 'exposureTimeNs': 14}
                value = {name: aec[i] for name, i in indices.items()}
                if any(not math.isfinite(v) or (name != 'lux' and v <= 0)
                       for name, v in value.items()):
                    raise ValueError('Invalid vendor AE fields')
                value['rawHex'] = data.hex()
                field = 'vendorAec'
            else:
                field, size = FIELDS[event['tag']]
                if event['count'] != 1:
                    continue
                data = block(event['value'], size)
                value = int.from_bytes(data, 'little', signed=True)
                if len(data) != size or value < 0 or (value == 0 and field != 'requestFrameCount'):
                    raise ValueError('Invalid scalar metadata value')
            if int(event['object'], 16) == 0:
                raise ValueError('Null metadata object')
            observations.setdefault((event['pid'], event['object']), []).append(dict(
                metadataId=event['metadataId'], field=field, value=value,
                enteredMs=event['enteredMs'], observedMs=event['observedMs']))
        except (KeyError, TypeError, ValueError) as error:
            errors.append(dict(metadataId=event.get('metadataId'), error=str(error)))
    for event in trace:
        if event.get('event') != 'delivery_leave' or event.get('returnBits') != 1:
            continue
        route = event.get('route')
        if route not in ('past', 'future'):
            continue
        try:
            ids, objects = words(event['requestedIds']), references(event[route])
            identity = event.get(route + 'Identity', {})
            if identity.get('error'):
                raise ValueError(identity['error'])
            rows = identity.get('rows', [])
            if len(ids) != 1 or len(objects) != 1 or len(rows) != 1:
                continue
            row = rows[0]
            if row.get('error') or row['index'] != 0 or int(row['object'], 16) != objects[0][0]:
                raise ValueError('Returned image identity mismatch')
            metadata = row.get('metadata', {})
            if metadata.get('error') or not metadata.get('object'):
                raise ValueError(metadata.get('error', 'No metadata map snapshot'))
            observed = observations.get((event['pid'], metadata['object']), [])
            timestamps = sorted({o['value'] for o in observed if o['field'] == 'sensorTimestampNs'})
            deliveries.append(dict(pid=event['pid'], queue=event['queue'], recordId=ids[0],
                                   deliveryId=event['id'], deliveryMs=event['timeMs'],
                                   metadataObject=metadata['object'], observations=observed,
                                   timestampValues=timestamps, multipleTimestampValues=len(timestamps) > 1))
        except (KeyError, TypeError, ValueError) as error:
            errors.append(dict(deliveryId=event.get('id'), error=str(error)))
    nice = analyze_inputs(trace)
    return dict(deliveries=deliveries, errors=errors, vasRawMetadata=vas_reads,
                niceInputErrors=nice['errors'], niceMissingReturns=nice['missingReturns'],
                vasNiceValueComparisons=compare_vas_values(vas_reads, nice['inputs']),
                metadataObservations=sum(map(len, observations.values())),
                sensorFrameIdentityEstablished=False,
                limitation='Metadata objects can be modified/reused. These are observed reads and handle-map snapshots, not an atomic per-RAW metadata snapshot; no nearest-time association is applied.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('archive', type=Path)
    print(json.dumps(analyze_metadata(events(parser.parse_args().archive)), indent=2))
