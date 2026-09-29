#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
from analyze import events, words, references


def dma_key(observation):
    if not isinstance(observation, dict) or observation.get('error'):
        return None
    text = observation.get('fdinfo', '')
    if not isinstance(text, str) or len(text) > 8192:
        return None
    fields = {}
    for line in text.splitlines():
        if ':' not in line:
            continue
        name, value = line.split(':', 1)
        if name in fields:
            return None
        fields[name] = value.strip()
    # An ordinary anon-inode entry is insufficient evidence of a DMA buffer.
    if not fields.get('exp_name'):
        return None
    try:
        inode, size = int(fields['ino']), int(fields['size'])
        if inode <= 0 or size <= 0:
            return None
    except (KeyError, ValueError):
        return None
    return inode, size, fields['exp_name']


def analyze_identity(trace):
    candidates, inputs, unreadable = {}, [], 0
    for event in trace:
        if event.get('event') == 'nice_input_enter':
            inputs.append(event)
        if event.get('event') not in ('queue_enter', 'queue_leave', 'delivery_enter'):
            continue
        # queue_leave v13 may omit queue; resolve only by this process/call ID.
        queue = event.get('queue')
        if queue is None:
            previous = [e for e in trace if e.get('event') == 'queue_enter'
                        and e.get('pid') == event.get('pid') and e.get('id') == event.get('id')]
            queue = previous[0].get('queue') if len(previous) == 1 else None
        if queue is None:
            continue
        for field in ('readyIdentity', 'pendingIdentity'):
            snapshot = event.get(field, {})
            if snapshot.get('error'):
                unreadable += 1
            for row in snapshot.get('rows', []):
                if row.get('error'):
                    unreadable += 1
                for fd in row.get('fds', []):
                    key = dma_key(fd)
                    if key is None:
                        unreadable += 1
                        continue
                    candidates.setdefault(key, set()).add((event['pid'], queue, row['id']))
    returned, timed_returns = {}, {}
    # These are observation windows, not sensor/capture identities. Retain the
    # unfiltered evidence as well: a queue may be prepared while an older burst
    # is still being processed, and timeMs is a wall clock shared across roles.
    preparations = {}
    for event in trace:
        if event.get('event') == 'queue_enter' and isinstance(event.get('timeMs'), int):
            preparations.setdefault((event.get('pid'), event.get('queue')), []).append(event['timeMs'])
    for event in trace:
        if event.get('event') != 'delivery_leave' or event.get('returnBits') != 1:
            continue
        try:
            ids = words(event['requestedIds'])
            # Only single-frame split returns have an unambiguous ID here.
            field = {'past': 'past', 'future': 'future'}.get(event.get('route'))
            if field is None or len(ids) != 1:
                continue
            objects = references(event[field])
            rows = event.get(field+'Identity', {}).get('rows', [])
            if len(objects) != 1 or len(rows) != 1:
                continue
            row = rows[0]
            if row.get('error') or row.get('index') != 0 or int(row['object'], 16) != objects[0][0]:
                continue
            for fd in row.get('fds', []):
                key = dma_key(fd)
                if key:
                    returned.setdefault(key, set()).add((event['pid'], event['queue'], ids[0]))
                    if isinstance(event.get('timeMs'), int):
                        timed_returns.setdefault(key, []).append(
                            (event['timeMs'], event['pid'], event['queue'], ids[0]))
        except (KeyError, ValueError, TypeError):
            continue
    result = []
    for event in inputs:
        key = dma_key(event.get('sourceFd'))
        matches = sorted(candidates.get(key, set())) if key else []
        window_matches = set()
        input_time = event.get('timeMs')
        if isinstance(input_time, int):
            for when, pid, queue, record_id in timed_returns.get(key, []):
                # Equal-ms events are intentionally excluded: ordering between
                # processes is unknown. Do not use archive file order to decide.
                starts = [t for t in preparations.get((pid, queue), []) if t < input_time]
                if not starts or not max(starts) < when < input_time:
                    continue
                if input_time in preparations.get((pid, queue), []):
                    continue
                window_matches.add((pid, queue, record_id, max(starts)))
        result.append(dict(pid=event['pid'], inputId=event['inputId'],
                           source=event['source'], proc=event['proc'],
                           dmaObject=dict(zip(('inode', 'size', 'exporter'), key)) if key else None,
                           queueCandidates=[dict(pid=p, queue=q, recordId=i) for p, q, i in matches],
                           returnedBufferCandidates=[dict(pid=p, queue=q, recordId=i)
                                                     for p, q, i in sorted(returned.get(key, set()))],
                           observedWindowCandidates=[dict(pid=p, queue=q, recordId=i, prepareTimeMs=t)
                                                     for p, q, i, t in sorted(window_matches)],
                           observedWindowStatus='unreadable' if key is None else
                           'time_unavailable' if not isinstance(input_time, int) else
                           'unmatched' if not window_matches else
                           'candidate' if len(window_matches) == 1 else 'ambiguous',
                           status='unreadable' if key is None else
                           'unmatched' if not matches else 'candidate' if len(matches) == 1 else 'ambiguous'))
    return dict(inputs=result, unreadableQueueIdentities=unreadable,
                sensorFrameIdentityEstablished=False,
                limitation='DMA objects may be reused; matches do not prove sensor timestamp or capture identity',
                observedWindowLimitation='Candidates use wall-clock order within the latest observed queue preparation; overlapping bursts, missing events or clock changes can invalidate this window')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('archive', type=Path)
    print(json.dumps(analyze_identity(events(parser.parse_args().archive)), indent=2))
