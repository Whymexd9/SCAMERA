#!/usr/bin/env python3
"""Inventory embedded V79 QNN contexts without running vendor code.

Requires pyelftools. Does not extract weights unless --extract-dir is explicitly
provided; keep that directory outside git. The metadata reader supports only the
observed QNN serialization envelope, not arbitrary QNN versions.
"""
import argparse
import hashlib
import json
import struct
from pathlib import Path
from elftools.elf.elffile import ELFFile


class Metadata:
    def __init__(self, data):
        if data[:16] != bytes.fromhex('00000002000000030000000000000001'):
            raise ValueError('Unknown QNN envelope')
        metadata_size, context_offset, context_size = struct.unpack_from('<QQQ', data, 16)
        if context_offset != 40 + metadata_size or context_size != len(data) - context_offset:
            raise ValueError('Inconsistent context lengths')
        self.data = data[:context_offset]

    def number(self, offset, fmt='<I'):
        size = struct.calcsize(fmt)
        if offset < 0 or offset + size > len(self.data):
            raise ValueError('Metadata reference outside envelope')
        return struct.unpack_from(fmt, self.data, offset)[0]

    def field(self, table, index):
        vtable = table - self.number(table, '<i')
        size = self.number(vtable, '<H')
        if size < 4 or size % 2 or size > 256:
            raise ValueError('Invalid vtable')
        if 4 + 2 * index >= size:
            return None
        offset = self.number(vtable + 4 + 2 * index, '<H')
        return table + offset if offset else None

    def reference(self, field):
        if field is None:
            raise ValueError('Missing metadata field')
        offset = field + self.number(field)
        self.number(offset)
        return offset

    def ref_field(self, table, index):
        return self.reference(self.field(table, index))

    def vector(self, position, refs=False):
        length = self.number(position)
        if length > 64:
            raise ValueError('Unexpected metadata vector size')
        return [(self.reference if refs else self.number)(position + 4 + 4 * i)
                for i in range(length)]

    def string(self, position):
        length = self.number(position)
        if length > 256 or position + 4 + length >= len(self.data):
            raise ValueError('Invalid metadata string')
        return self.data[position + 4:position + 4 + length].decode('utf-8')

    def unwrap(self, table):
        if self.number(self.field(table, 0)) != 1:
            raise ValueError('Only observed V1 records are supported')
        return self.ref_field(table, 2)

    def tensor(self, table):
        shape = self.vector(self.ref_field(table, 7))
        if len(shape) != self.number(self.field(table, 6)) or any(x <= 0 for x in shape):
            raise ValueError('Invalid tensor dimensions')
        return dict(name=self.string(self.ref_field(table, 9)), shape=shape,
                    datatype_hex=hex(self.number(self.field(table, 3))))

    def graphs(self):
        root = self.reference(40)
        binary = self.unwrap(self.ref_field(root, 0))
        result = []
        for wrapped in self.vector(self.ref_field(binary, 13), refs=True):
            graph = self.unwrap(wrapped)
            result.append(dict(name=self.string(self.ref_field(graph, 0)),
                               inputs=[self.tensor(x) for x in self.vector(self.ref_field(graph, 2), refs=True)],
                               outputs=[self.tensor(x) for x in self.vector(self.ref_field(graph, 4), refs=True)]))
        return result


def inventory(path, extract_dir=None):
    with open(path, 'rb') as stream:
        elf = ELFFile(stream)
        if elf['e_machine'] != 'EM_AARCH64' or elf.elfclass != 64 or not elf.little_endian:
            raise ValueError('Expected Android ARM64 ELF')
        symbols = elf.get_section_by_name('.dynsym')
        rows = []
        for symbol in symbols.iter_symbols():
            name = symbol.name
            if not name.startswith('T2Q_') or '_v79_' not in name or not name.endswith('_bin'):
                continue
            section = elf.get_section(symbol['st_shndx'])
            offset, size = symbol['st_value'] - section['sh_addr'], symbol['st_size']
            if offset < 0 or size < 40 or offset + size > section['sh_size']:
                raise ValueError('Model outside section')
            stream.seek(section['sh_offset'] + offset)
            data = stream.read(size)
            if len(data) != size:
                raise ValueError('Short model read')
            lengths = symbols.get_symbol_by_name(name + '_len')
            if not lengths:
                raise ValueError('Missing exported model length')
            length_symbol = lengths[0]
            length_section = elf.get_section(length_symbol['st_shndx'])
            stream.seek(length_section['sh_offset'] + length_symbol['st_value'] - length_section['sh_addr'])
            if struct.unpack('<I', stream.read(4))[0] != size:
                raise ValueError('Exported model length mismatch')
            rows.append(dict(symbol=name, size=size, sha256=hashlib.sha256(data).hexdigest(),
                             graphs=Metadata(data).graphs()))
            if extract_dir:
                extract_dir.mkdir(parents=True, exist_ok=True)
                (extract_dir / (name + '.bin')).write_bytes(data)
    return dict(library_sha256=hashlib.sha256(Path(path).read_bytes()).hexdigest(), models=rows,
                hp9_4x_input_mapping_verified=False)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('library', type=Path)
    parser.add_argument('--extract-dir', type=Path)
    args = parser.parse_args()
    print(json.dumps(inventory(args.library, args.extract_dir), indent=2))
