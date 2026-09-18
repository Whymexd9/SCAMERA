#!/usr/bin/env python3
"""Report the tensor interface of a Vivo softpqe .vdnn photo upscaler.

The camera ships nine of these: three luma enhancement models, two 2x, two 4x
and two chroma models. Their configs already say which model runs at which zoom
and with which parameters, but not the tensor interface - tile size, channel
count and data type - which decides whether an equivalent stage in our own
pipeline needs one plane or several, and how large a block it has to work on.

The file is a FlatBuffers container that embeds a QNN context binary; the
context's own metadata carries the graph interface. Only that metadata is read.
Weights are never extracted, no vendor code is loaded or executed, and nothing
here depends on a vendor library being present. Keep the model files outside
git; they belong to the device.

Usage:
    python3 tools/inspect_softpqe_model.py /path/to/softpqe_y_4x_0_2_0.vdnn ...
"""
import argparse
import struct
import sys
from pathlib import Path

# Start of the serialization envelope QNN writes ahead of a context binary.
ENVELOPE = bytes.fromhex('00000002000000030000000000000001')


class Metadata:
    """A deliberately narrow FlatBuffers reader for the QNN context envelope."""

    def __init__(self, data):
        if data[:16] != ENVELOPE:
            raise ValueError('not a QNN context envelope')
        metadata_size, context_offset, _ = struct.unpack_from('<QQQ', data, 16)
        if context_offset != 40 + metadata_size:
            raise ValueError('inconsistent metadata length')
        # Everything past the metadata is the compiled graph; it is not read.
        self.data = data[:context_offset]

    def number(self, offset, fmt='<I'):
        size = struct.calcsize(fmt)
        if offset < 0 or offset + size > len(self.data):
            raise ValueError('reference outside the envelope')
        return struct.unpack_from(fmt, self.data, offset)[0]

    def field(self, table, index):
        vtable = table - self.number(table, '<i')
        size = self.number(vtable, '<H')
        if size < 4 or size % 2 or size > 256:
            raise ValueError('invalid vtable')
        if 4 + 2 * index >= size:
            return None
        offset = self.number(vtable + 4 + 2 * index, '<H')
        return table + offset if offset else None

    def reference(self, field):
        if field is None:
            raise ValueError('missing field')
        return field + self.number(field)

    def ref_field(self, table, index):
        return self.reference(self.field(table, index))

    def vector(self, position, refs=False):
        length = self.number(position)
        if length > 64:
            raise ValueError('unexpected vector size')
        return [(self.reference if refs else self.number)(position + 4 + 4 * i)
                for i in range(length)]

    def string(self, position):
        length = self.number(position)
        if length > 256 or position + 4 + length >= len(self.data):
            raise ValueError('invalid string')
        return self.data[position + 4:position + 4 + length].decode('utf-8')

    def unwrap(self, table):
        """Record version 1 is what the remosaic libraries carry; these are 3."""
        version = self.number(self.field(table, 0))
        if version not in (1, 3):
            raise ValueError(f'unsupported record version {version}')
        return self.ref_field(table, 2)

    def tensor(self, table):
        rank = self.number(self.field(table, 6))
        shape = self.vector(self.ref_field(table, 7))
        if len(shape) != rank or any(x <= 0 for x in shape):
            raise ValueError('invalid tensor dimensions')
        return dict(name=self.string(self.ref_field(table, 9)), shape=shape,
                    datatype=hex(self.number(self.field(table, 3))))

    def graphs(self):
        binary = self.unwrap(self.ref_field(self.reference(40), 0))
        # Version 1 keeps the graph list directly on the binary record; version
        # 3 puts a versioned record in between, whose own first field holds the
        # list. Both are tried rather than assumed, so a further shift shows up
        # as a clean failure instead of nonsense.
        for index in range(24):
            field = self.field(binary, index)
            if field is None:
                continue
            for entry in self.candidates(field):
                # A list may hold records other than graphs; those are skipped
                # rather than failing the whole read.
                result = []
                for table in entry:
                    try:
                        result.append(self.graph(table))
                    except (ValueError, UnicodeDecodeError, struct.error):
                        continue
                if result:
                    return result
        raise ValueError('no graph list found')

    def candidates(self, field):
        """Graph-table lists reachable from a field, directly or one record in."""
        try:
            entries = self.vector(self.reference(field), refs=True)
        except (ValueError, struct.error):
            return
        yield entries
        for entry in entries:
            try:
                yield self.vector(self.ref_field(self.unwrap(entry), 0), refs=True)
            except (ValueError, struct.error):
                continue

    def graph(self, table):
        return dict(name=self.string(self.ref_field(table, 0)),
                    inputs=[self.tensor(t) for t in
                            self.vector(self.ref_field(table, 2), refs=True)],
                    outputs=[self.tensor(t) for t in
                             self.vector(self.ref_field(table, 4), refs=True)])


def dump(meta, table, label, depth=0):
    """Print what each field of a table reads as, for when a layout shifts."""
    pad = '  ' * (depth + 1)
    print(f'{pad}{label} @ {table}')
    for index in range(24):
        field = meta.field(table, index)
        if field is None:
            continue
        line = f'{pad}  [{index}] raw={meta.number(field)}'
        try:
            target = meta.reference(field)
            head = meta.number(target)
            line += f' ref={target} head={head}'
            if 0 < head < 64:
                line += f' (vector of {head}?)'
                if depth < 5:
                    try:
                        first = meta.reference(target + 4)
                        print(line)
                        dump(meta, first, f'[{index}][0]', depth + 1)
                        continue
                    except (ValueError, struct.error):
                        pass
            try:
                line += f' str={meta.string(target)!r}'
            except (ValueError, UnicodeDecodeError):
                pass
        except (ValueError, struct.error):
            pass
        print(line)


def describe(path, structure=False, table=None):
    data = Path(path).read_bytes()
    start = data.find(ENVELOPE)
    if start < 0:
        raise ValueError('no embedded QNN context')
    print(f'{Path(path).name}  ({len(data)} bytes, context at {start})')
    meta = Metadata(data[start:])
    if table is not None:
        dump(meta, table, f'table@{table}')
        return
    if structure:
        root = meta.reference(40)
        dump(meta, root, 'root')
        wrapper = meta.reference(meta.field(root, 0))
        dump(meta, wrapper, 'wrapper', 1)
        dump(meta, meta.unwrap(wrapper), 'payload', 2)
        return
    for graph in meta.graphs():
        print(f'  graph {graph["name"]}')
        for kind in ('inputs', 'outputs'):
            for tensor in graph[kind]:
                print(f'    {kind[:-1]:6s} {tensor["name"]:28s} '
                      f'{tensor["shape"]} dtype={tensor["datatype"]}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('models', nargs='+', type=Path)
    parser.add_argument('--structure', action='store_true',
                        help='report the metadata table layout instead of the graphs')
    args = parser.parse_args()
    for model in args.models:
        try:
            describe(model, args.structure, args.table)
        except (ValueError, OSError, struct.error) as error:
            failures += 1
            print(f'{model.name}: {error}')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
