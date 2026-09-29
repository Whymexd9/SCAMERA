#!/usr/bin/env python3
import argparse
import json
import math
import struct
from pathlib import Path
from analyze import block, events, words, references
from analyze_buffer_identity import dma_key


def input_descriptors(data, count):
    if len(data) != count * 0x98 or not 1 <= count <= 20:
        raise ValueError('Invalid AlgoFrameInfo extent')
    descriptors = []
    for index in range(count):
        offset = index * 0x98
        # fillSelectResult -> copyBuffer -> mmap establishes these fields.
        fd, = struct.unpack_from('<i', data, offset + 0x44)
        size, = struct.unpack_from('<i', data, offset + 0x54)
        if not 0 <= fd <= 1048575 or not 0 < size <= 256 * 1024 * 1024:
            raise ValueError('Invalid AlgoFrameInfo FD or mapping length')
        descriptors.append(dict(index=index, fd=fd, mappingBytes=size))
    return descriptors


def descriptor_candidates(bindings, trace):
    delivered, nice = {}, {}
    for event in trace:
        if event.get('event') == 'nice_input_enter':
            key = dma_key(event.get('sourceFd'))
            if key:
                nice.setdefault(key, []).append(dict(pid=event['pid'], inputId=event['inputId'],
                                                     sourceIndex=event['source']))
        if event.get('event') != 'delivery_leave' or event.get('returnBits') != 1:
            continue
        field = event.get('route')
        if field not in ('past', 'future'):
            continue
        try:
            ids, objects = words(event['requestedIds']), references(event[field])
            identity = event[field + 'Identity']
            rows = identity['rows']
            if identity.get('error') or len(ids) != 1 or len(objects) != 1 or len(rows) != 1:
                continue
            row = rows[0]
            if row.get('error') or row['index'] != 0 or int(row['object'], 16) != objects[0][0]:
                continue
            for fd in row['fds']:
                key = dma_key(fd)
                if key:
                    delivered.setdefault((event['pid'], fd['fd']), []).append(dict(
                        deliveryId=event['id'], queue=event['queue'], recordId=ids[0], dmaKey=key))
        except (KeyError, TypeError, ValueError):
            continue
    result = []
    for binding in bindings:
        for descriptor in binding['inputDescriptors']:
            candidates = []
            for observation in delivered.get((binding['pid'], descriptor['fd']), []):
                key = observation['dmaKey']
                if key[1] >= descriptor['mappingBytes']:
                    candidates.append(dict(observation, niceInputs=nice.get(key, [])))
            result.append(dict(pid=binding['pid'], conversionId=binding['conversionId'],
                               descriptorIndex=descriptor['index'], candidates=candidates,
                               associationEstablished=False))
    return result


def analyze_bindings(trace):
    trace = list(trace)
    pending, seen, stacks, rows, errors = {}, set(), {}, [], []
    for event in trace:
        kind = event.get('event')
        if kind not in ('vas_conversion_enter', 'vas_conversion_leave',
                        'vas_conversion_timestamp', 'vas_raw_metadata'):
            continue
        if event.get('conversionId') is None:
            continue
        key = (event['pid'], event['conversionId'])
        thread = (event['pid'], event['thread'])
        try:
            if kind == 'vas_conversion_enter':
                if key in seen:
                    raise ValueError('Conversion ID reused')
                seen.add(key)
                pending[key] = dict(enter=event, ae=[], timestamps=[], invalid=False)
                stacks.setdefault(thread, []).append(key)
                continue
            state = pending.get(key)
            if state is None or not stacks.get(thread) or stacks[thread][-1] != key:
                raise ValueError('Read/return outside its conversion thread scope')
            enter = state['enter']
            if event['source'] != enter['source'] or int(enter['source'], 16) == 0:
                raise ValueError('Metadata source mismatch')
            if kind == 'vas_raw_metadata':
                if event['field'] != 'vendorAec':
                    continue
                if not event['success'] or event['callSite'] != '0xdcd2c' or event['size'] != 140:
                    raise ValueError('Missing/invalid scoped vendor AE')
                data = block(event['value'], 140)
                aec = struct.unpack('<35f', data)
                if any(not math.isfinite(aec[i]) or (i != 0 and aec[i] <= 0)
                       for i in (0, 2, 6, 13, 14)):
                    raise ValueError('Invalid vendor AE values')
                state['ae'].append(data.hex())
                continue
            if kind == 'vas_conversion_timestamp':
                if not event['success'] or event['callSite'] != '0xe1950':
                    raise ValueError('Missing/invalid scoped timestamp')
                timestamp, = struct.unpack('<q', block(event['value'], 8))
                if timestamp <= 0:
                    raise ValueError('Nonpositive sensor timestamp')
                state['timestamps'].append(timestamp)
                continue
            stacks[thread].pop()
            if not event['sourceUnchanged'] or event['request'] != enter['request']:
                raise ValueError('Request metadata replaced during conversion')
            header = block(enter['requestHeader'], 0x78)
            if header != block(event['requestHeader'], 0x78):
                raise ValueError('Request header changed during conversion')
            if state['invalid'] or len(state['ae']) != 1 or len(state['timestamps']) != 1:
                raise ValueError('Conversion needs exactly one valid AE copy and timestamp read')
            frames = enter['inputFrames']
            count = frames['count']
            if frames.get('error') or frames['stride'] != 0x98 or not 1 <= count <= 20:
                raise ValueError('Invalid VCF input-frame vector')
            frame_data = block(frames['data'], count * 0x98)
            if len(frame_data) != count * 0x98:
                raise ValueError('Truncated VCF input-frame vector')
            rows.append(dict(pid=key[0], conversionId=key[1], thread=thread[1],
                             vcfRequestId=struct.unpack_from('<Q', header, 0x10)[0],
                             vcfFrameNumber=struct.unpack_from('<I', header, 0)[0],
                             metadataSource=enter['source'], sensorTimestampNs=state['timestamps'][0],
                             vendorAeHex=state['ae'][0], inputFramesHex=frame_data.hex(),
                             inputDescriptors=input_descriptors(frame_data, count),
                             inputFrameCount=count, conversionAssociationEstablished=True,
                             sensorFrameIdentityEstablished=False))
            del pending[key]
        except (KeyError, TypeError, ValueError, struct.error) as error:
            if key in pending:
                pending[key]['invalid'] = True
            errors.append(dict(pid=key[0], conversionId=key[1], error=str(error)))
    return dict(bindings=rows, errors=errors,
                descriptorObservationCandidates=descriptor_candidates(rows, trace),
                incompleteConversions=[dict(pid=k[0], conversionId=k[1]) for k in pending],
                sensorFrameIdentityEstablished=False,
                limitation='AE/timestamp are bound to the observed conversion. FD matches to earlier delivery snapshots are candidates only: descriptor reuse/lifetime is not established. No production RAW transfer is inferred.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('archive', type=Path)
    print(json.dumps(analyze_bindings(events(parser.parse_args().archive)), indent=2))
