#!/usr/bin/env python3
"""Inspect pinned HP9 VDNN wrappers; optionally extract their private QNN context.

No vendor code is executed. No binaries are downloaded or added to the APK.
The wrapper datatype code is reported verbatim: QNN System metadata is still
required to establish the execution tensor datatype/quantization contract.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct

MODELS = {
    '64cadd31a4dcccb2f5660e6502691cf65d3272eb9761cc6f1bad533f824ad63a':
        ('hex-x1', [1, 288, 288, 18], [1, 288, 288, 3], 9),
    '3483d225ec2595530121c16588713c928594335c11ff656785bd2d24ef1a7776':
        ('hex-x2', [1, 288, 288, 18], [1, 544, 544, 3], 9),
    '07f8e184185aa09863a0b707e276488d82146dafafc3347c55af892241cb12cd':
        ('roi-quad-x1', [1, 544, 544, 12], [1, 544, 544, 3], 4),
}


class FlatBuffer:
    """Bounds-checked reads of the observed VDNN FlatBuffer fields."""
    def __init__(self, data):
        self.data = data

    def check(self, offset, length):
        if offset < 0 or length < 0 or offset > len(self.data) - length:
            raise ValueError('FlatBuffer range outside file')

    def number(self, fmt, offset):
        self.check(offset, struct.calcsize('<' + fmt))
        return struct.unpack_from('<' + fmt, self.data, offset)[0]

    def pointer(self, offset):
        distance = self.number('I', offset)
        if distance < 4:
            raise ValueError('Invalid FlatBuffer relative pointer')
        result = offset + distance
        self.check(result, 4)
        return result

    def field(self, table, index):
        vtable = table - self.number('i', table)
        length = self.number('H', vtable)
        object_size = self.number('H', vtable + 2)
        if length < 4 or length % 2 or object_size < 4:
            raise ValueError('Invalid FlatBuffer table')
        self.check(vtable, length)
        self.check(table, object_size)
        if index < 0 or 4 + index * 2 >= length:
            raise ValueError('Missing required VDNN field')
        offset = self.number('H', vtable + 4 + index * 2)
        if offset < 4 or offset + 4 > object_size:
            raise ValueError('Invalid required VDNN field')
        return table + offset

    def vector(self, field, item_size=4):
        offset = self.pointer(field)
        count = self.number('I', offset)
        start = offset + 4
        self.check(start, count * item_size)
        return start, count

    def string(self, field):
        start, count = self.vector(field, 1)
        self.check(start + count, 1)
        if self.data[start + count] != 0:
            raise ValueError('Unterminated VDNN string')
        return self.data[start:start + count].decode('utf-8')

    def one(self, field):
        offset, count = self.vector(field)
        if count != 1:
            raise ValueError('Expected exactly one tensor')
        return offset

    def shape(self, field):
        table = self.pointer(self.one(field))
        start, count = self.vector(self.field(table, 0))
        if count != 4:
            raise ValueError('Expected rank-4 VDNN shape')
        return list(struct.unpack_from('<4I', self.data, start))


def inspect(data):
    sha = hashlib.sha256(data).hexdigest()
    if sha not in MODELS:
        raise ValueError('Unknown VDNN SHA256; refusing to guess its schema')
    name, input_shape, output_shape, code = MODELS[sha]
    reader = FlatBuffer(data)
    root = reader.pointer(0)
    network = reader.pointer(reader.field(root, 7))
    def tensor(base):
        return {
            'name': reader.string(reader.one(reader.field(network, base))),
            'shape': reader.shape(reader.field(network, base + 1)),
            'vdnn_type_code': reader.number('I', reader.one(reader.field(network, base + 2))),
        }
    inp, out = tensor(0), tensor(5)
    if (inp['shape'] != input_shape or out['shape'] != output_shape or
            inp['vdnn_type_code'] != code or out['vdnn_type_code'] != code):
        raise ValueError('Unexpected pinned VDNN tensor contract')
    start, length = reader.vector(reader.field(network, 10), 1)
    if length < 1024 or length > 32 * 1024 * 1024:
        raise ValueError('Invalid QNN context size')
    context = data[start:start + length]
    if context[:8] != bytes.fromhex('0000000200000003'):
        raise ValueError('Unexpected QNN context header')
    versions = sorted(set(x.decode('ascii') for x in
                          re.findall(rb'v2\.28\.0\.[0-9_]+', context)))
    if not versions:
        raise ValueError('Expected QNN 2.28 build string')
    result = {
        'model': name, 'source_sha256': sha, 'input': inp, 'output': out,
        'conversion_tag': reader.string(reader.field(network, 11)),
        'qnn_build_strings': versions,
        'context': {'offset': start, 'size': length,
                    'sha256': hashlib.sha256(context).hexdigest()},
        'capture_ready': False,
        'unverified': ['QNN execution datatype and quantization',
                       'VST/IVST calibration and per-frame exposure',
                       'registered burst order and valid output tile placement'],
    }
    return result, context


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('model', type=Path)
    parser.add_argument('--context-output', type=Path,
                        help='write private context to a NEW file; never commit it')
    args = parser.parse_args()
    if args.model.stat().st_size > 32 * 1024 * 1024:
        parser.error('VDNN exceeds size limit')
    result, context = inspect(args.model.read_bytes())
    if args.context_output:
        with args.context_output.open('xb') as stream:
            stream.write(context)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
