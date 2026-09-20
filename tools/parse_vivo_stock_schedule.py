#!/usr/bin/env python3
"""Read stock Java logger events without inventing a HAL exposure ABI.

Source: PD2454 VivoCamera APK, SuperNightCaptureCommand.java and
SuperNightUtils.java. Events remain independent: adjacency does not establish
that two lines belong to the same capture. This is analysis, not camera control.
"""
import argparse
import json
import math
import re
from pathlib import Path

ARRAY = re.compile(r'\b(aecFrameInfo|aecFrameControl|captureFrameControl|motionMetering):\s*(null|\[[^\]]*\])')
COUNTS = re.compile(r'forwardFrameCount:\s*(-?\d+)\s+backwardFrameCount:\s*(-?\d+)\s+totalFrameCount:\s*(-?\d+)\s+batchInfo:\s*(\[[^\]]*\])')
THREAD = re.compile(r'^\s*(\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+)\s+(\d+)\s+(\d+)\s+[VDIWEF]\s+')


def values(text, integer=False):
    if text == 'null':
        return None
    tokens = text[1:-1].split(',') if text[1:-1].strip() else []
    result = []
    for token in tokens:
        token = token.strip()
        if integer and not re.fullmatch(r'[+-]?\d+', token):
            raise ValueError('noninteger capture control')
        number = int(token) if integer else float(token)
        if not math.isfinite(number):
            raise ValueError('nonfinite value')
        if integer and not -(2**31) <= number < 2**31:
            raise ValueError('outside int32 range')
        result.append(number)
    return result


def capture_counts(forward, backward, total, batch):
    if min(forward, backward) < 0 or total != forward + backward:
        raise ValueError('inconsistent capture counts')
    return dict(forward_count=forward, backward_count=backward,
                total_count=total, batch_info=batch)


def parse(text):
    events = []
    for line_number, line in enumerate(text.splitlines(), 1):
        # Accept only lines from the identified stock command, not unrelated
        # messages containing similarly named arrays.
        if '[SuperNightCaptureCommand]' not in line:
            continue
        context = dict(line=line_number)
        thread = THREAD.match(line)
        if thread:
            context.update(timestamp=thread[1], pid=int(thread[2]), tid=int(thread[3]))
        for match in ARRAY.finditer(line):
            event = dict(context, kind=match[1])
            try:
                data = values(match[2], match[1] == 'captureFrameControl')
                event['raw'] = data
                if data is None:
                    event['status'] = 'absent'
                elif match[1] == 'captureFrameControl':
                    if len(data) < 3 or data[2] < 0 or data[2] > len(data)-3:
                        raise ValueError('truncated or invalid batch length')
                    event.update(capture_counts(data[0], data[1], data[0]+data[1], data[3:3+data[2]]))
                    event['uninterpreted_tail'] = data[3+data[2]:]
                elif match[1] in ('aecFrameControl', 'aecFrameInfo'):
                    if len(data) < 48:
                        raise ValueError('AEC array shorter than observed 48-value contract')
                    event['normal_marker_plane'] = data[:16]
                    event['shutter_ms_plane'] = data[32:48]
                    # The Java consumer compares the first plane to zero for
                    # N; it does not establish all roles or the gain plane ABI.
                    event['uninterpreted_16_31'] = data[16:32]
                    event['uninterpreted_tail'] = data[48:]
            except (ValueError, OverflowError) as exc:
                event['error'] = str(exc)
            events.append(event)
        match = COUNTS.search(line)
        if match:
            event = dict(context, kind='frame_counts')
            try:
                event.update(capture_counts(int(match[1]), int(match[2]), int(match[3]), values(match[4], True)))
            except (ValueError, OverflowError) as exc:
                event['error'] = str(exc)
            events.append(event)
    return dict(events=events, captures_paired=False,
                complete_hal_abi=False, usable_event_count=sum('error' not in e and e.get('status') != 'absent' for e in events))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('log', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = json.dumps(parse(args.log.read_text(errors='replace')), indent=2, ensure_ascii=False, allow_nan=False)+'\n'
    if args.output:
        args.output.write_text(result)
    else:
        print(result, end='')


if __name__ == '__main__':
    main()
