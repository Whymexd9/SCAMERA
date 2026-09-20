#!/usr/bin/env python3
"""Read the pinned PD2454 metadata schema; this does not query a camera service."""
import argparse
import hashlib
import json
from pathlib import Path
from elftools.elf.elffile import ELFFile
import struct

SHA256 = '83f1f950456f62396530f80ac393eaeaa0b4602427a41f59650137cd9f76c362'


def read_schema(path):
    if hashlib.sha256(path.read_bytes()).hexdigest() != SHA256:
        raise ValueError('Unsupported Vivo metadata donor')
    with path.open('rb') as stream:
        elf = ELFFile(stream)
        segments = [s for s in elf.iter_segments() if s['p_type'] == 'PT_LOAD']
        symbols = {s.name: s['st_value'] for s in elf.get_section_by_name('.dynsym').iter_symbols()}

        def read(address, length):
            for segment in segments:
                start = segment['p_vaddr']
                if start <= address and address + length <= start + segment['p_filesz']:
                    stream.seek(segment['p_offset'] + address - start)
                    return stream.read(length)
            raise ValueError(f'Address outside donor: {address:x}')

        def string(address):
            return read(address, 256).split(b'\0', 1)[0].decode('ascii')

        rows = []
        # InitializeAlgoTagInfo 15194: six sections, source entries stride 16;
        # byte type at +8 and uint32 count at +12. RELR pointers use base zero.
        for section in range(6):
            name = string(struct.unpack('<Q', read(symbols['algo_metadata_section_names'] + 8*section, 8))[0])
            start, end = struct.unpack('<II', read(symbols['algo_metadata_section_bounds'] + 8*section, 8))
            table = struct.unpack('<Q', read(symbols['tag_info'] + 8*section, 8))[0]
            assert start == section << 16 and start <= end <= start + 65536
            for tag in range(start, end):
                text, kind, count = struct.unpack('<QB3xI', read(table + 16*(tag-start), 16))
                rows.append(dict(id=tag, section=name, name=string(text), type=kind, count=count))
        return rows


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('library', type=Path)
    args = parser.parse_args()
    print(json.dumps({'sha256': SHA256, 'tags': read_schema(args.library)}, indent=2))
