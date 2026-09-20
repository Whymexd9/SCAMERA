#!/usr/bin/env python3
"""Recover OpenCL text from the supplied, hash-pinned CRE; no vendor payload in git.

Requires pyelftools. Addresses are for this exact donor only. This tool does not
establish which kernel/model was selected for a photograph.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
from elftools.elf.elffile import ELFFile

DONOR_SHA256 = '41b277753f7fedbe4dda1e1c4b76d2086d6e79768904d8a4922b48a0e023e76e'
TABLE_SLOT = 0x47acb0

def decode(path):
    raw = path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != DONOR_SHA256:
        raise ValueError('Unsupported CRE binary: address map is hash-pinned')
    with path.open('rb') as stream:
        elf = ELFFile(stream)
        relocations = [r for r in elf.get_section_by_name('.rela.dyn').iter_relocations()
                       if r['r_offset'] == TABLE_SLOT]
        if len(relocations) != 1 or relocations[0]['r_info_type'] != 1027:
            raise ValueError('Expected one AArch64 RELATIVE table relocation')
        address = relocations[0]['r_addend']
        table = None
        for segment in elf.iter_segments():
            offset = address - segment['p_vaddr']
            if 0 <= offset and offset + 256 <= segment['p_filesz']:
                table = segment.data()[offset:offset + 256]
                break
        if table is None or len(set(table)) != 256:
            raise ValueError('Invalid byte substitution table')
        ro = elf.get_section_by_name('.rodata')
        decoded = ro.data().translate(table)
        base = ro['sh_addr']
    # Readable runs are evidence fragments, not claimed compilation units.
    fragments = []
    for match in re.finditer(rb'[\x09\x0a\x0d\x20-\x7e]{200,}', decoded):
        text = match[0].decode('ascii')
        if '__kernel' in text or 'singleFrameNetPreProcess' in text:
            fragments.append((base + match.start(), text))
    if not any('FP32_GeneralNetPreprocessCL_7' in t for _, t in fragments):
        raise ValueError('Expected seven-frame preparation kernel is missing')
    return address, table, fragments

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('library', type=Path)
    parser.add_argument('output', type=Path, help='New private evidence directory')
    args = parser.parse_args()
    address, table, fragments = decode(args.library)
    args.output.mkdir(parents=True, exist_ok=False)
    inventory = []
    for va, source in fragments:
        name = f'cre-source-{va:08x}.cl'
        payload = source.encode()
        (args.output / name).write_bytes(payload)
        inventory.append({'file': name, 'virtual_address': hex(va),
                          'sha256': hashlib.sha256(payload).hexdigest(),
                          'kernels': re.findall(r'__kernel\s+void\s+(\w+)', source)})
    report = {'donor_sha256': DONOR_SHA256, 'decoder_function': '0x3a7270',
              'byte_lookup_instruction': '0x3a73e8',
              'table_pointer_slot': hex(TABLE_SLOT), 'table_address': hex(address),
              'table_sha256': hashlib.sha256(table).hexdigest(),
              'fragments': inventory, 'photographic_contract_verified': False}
    (args.output / 'manifest.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f'Decoded {len(fragments)} source fragments; donor hash verified')

if __name__ == '__main__':
    main()
