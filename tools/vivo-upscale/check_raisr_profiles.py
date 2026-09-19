#!/usr/bin/env python3
"""Execute the pinned ARM64 RAISR profile-selection routine (not inference).

Usage: check_raisr_profiles.py /path/to/extracted/dump
Requires pyelftools and unicorn. TinyXML navigation and libc are emulated;
zoom/ISO decisions and writes to the native configuration execute unchanged.
The supplied XML, selected filters and ELF must match manifest.json.
"""
import hashlib
import io
import json
from pathlib import Path
import struct
import sys
import xml.etree.ElementTree as ET

from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import (
    UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2,
    UC_ARM64_REG_D0, UC_ARM64_REG_SP, UC_ARM64_REG_LR,
    UC_ARM64_REG_PC, UC_ARM64_REG_TPIDR_EL0,
)


def main():
    root = Path(sys.argv[1])
    manifest = json.loads(Path(__file__).with_name('manifest.json').read_text())
    hashes = {item['path']: item['sha256'] for item in manifest['files']}

    def verified(path):
        data = (root / path).read_bytes()
        if hashlib.sha256(data).hexdigest() != hashes[path]:
            raise ValueError(f'Unverified firmware: {path}')
        return data

    elf = ELFFile(io.BytesIO(verified('vendor/lib64/libvivo_raisr.so')))
    segments = [(s['p_vaddr'], s['p_memsz'], s.data())
                for s in elf.iter_segments() if s['p_type'] == 'PT_LOAD']
    end = max(addr + size for addr, size, _ in segments)

    def execute(xml, profile, zoom, iso, faces):
        u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        u.mem_map(0, (end + 4095) // 4096 * 4096)
        for addr, _, data in segments:
            u.mem_write(addr, data)
        base, params, heap = 0x10000000, 0x10001000, 0x10008000
        u.mem_map(base, 0x20000)
        u.mem_write(params + 0x24, struct.pack('<f', zoom))
        u.mem_write(params + 0x138, struct.pack('<II', iso, faces))
        u.mem_write(params + 0x2c, b'/vendor/camera3rd/nti/raisr\0')
        nodes, next_token = {}, 0

        def allocate(data):
            nonlocal heap
            address = heap
            heap += (len(data) + 15) // 16 * 16
            if heap >= base + 0x18000:
                raise RuntimeError('Emulator heap exhausted')
            u.mem_write(address, data)
            return address

        def string(address):
            data = bytearray()
            for _ in range(4096):
                c = u.mem_read(address + len(data), 1)[0]
                if not c:
                    return bytes(data)
                data.append(c)
            raise ValueError('Unterminated string')

        def node(element):
            if element is None:
                return 0
            address = allocate(b'\0' * 16)
            nodes[address] = element
            return address

        def hook(uc, address, size, data):
            nonlocal next_token
            a, b, c = (uc.reg_read(reg) for reg in
                       (UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2))
            result = 0
            if address == 0x1818c:  # XMLDocument constructor
                document = ET.Element('document')
                document.append(xml)
                nodes[a] = document
            elif address in (0x18948, 0x18444):  # LoadFile / destructor
                pass
            elif address == 0x13fb4:  # FirstChildElement
                children = list(nodes[a])
                name = string(b).decode() if b else None
                result = node(next((x for x in children if name is None or x.tag == name), None))
            elif address == 0x16a8c:  # GetText (fresh writable buffer for strtok)
                value = nodes[a].text
                result = allocate(value.encode() + b'\0') if value else 0
            elif address == 0x8490:  # atoi
                result = int(string(a))
            elif address == 0x84d0:  # atof
                uc.reg_write(UC_ARM64_REG_D0,
                             struct.unpack('<Q', struct.pack('<d', float(string(a))))[0])
            elif address == 0x84a0:  # sprintf("%d", zoom group)
                assert string(b) == b'%d'
                value = str(c).encode()
                uc.mem_write(a, value + b'\0')
                result = len(value)
            elif address == 0x84f0:  # strlen
                result = len(string(a))
            elif address in (0x84e0, 0x84b0):  # strcpy / strcat
                destination = a + (len(string(a)) if address == 0x84b0 else 0)
                uc.mem_write(destination, string(b) + b'\0')
                result = a
            elif address == 0x84c0:  # strtok (only comma-separated numbers here)
                assert string(b) == b','
                start = a or next_token
                if start:
                    value = string(start)
                    index = value.find(b',')
                    next_token = start + index + 1 if index >= 0 else 0
                    if index >= 0:
                        uc.mem_write(start + index, b'\0')
                    result = start if value else 0
            elif address == 0x8500:
                raise AssertionError('Native stack guard failed')
            elif 0x8400 <= address < 0x8600:
                raise AssertionError(f'Unexpected libc call {address:#x}')
            else:
                return
            uc.reg_write(UC_ARM64_REG_X0, result)
            uc.reg_write(UC_ARM64_REG_PC, uc.reg_read(UC_ARM64_REG_LR))

        u.hook_add(UC_HOOK_CODE, hook)
        u.reg_write(UC_ARM64_REG_X0, params)
        u.reg_write(UC_ARM64_REG_X1, allocate(b'unused.xml\0'))
        u.reg_write(UC_ARM64_REG_X2, allocate(profile.encode() + b'\0'))
        u.reg_write(UC_ARM64_REG_SP, base + 0x1f000)
        u.reg_write(UC_ARM64_REG_TPIDR_EL0, base + 0x18000)
        u.reg_write(UC_ARM64_REG_LR, base)
        u.emu_start(0x8eb8, base, count=10000)
        assert u.reg_read(UC_ARM64_REG_PC) == base, 'Instruction limit reached'
        assert u.reg_read(UC_ARM64_REG_X0) == 0, 'Native profile selection failed'
        group, remaining = struct.unpack('<If', u.mem_read(params + 0x1180, 8))
        white1, white2, black = struct.unpack('<iif', u.mem_read(params + 0x1414, 12))
        return string(params + 0x160).decode(), group, remaining, (white1, white2, black)

    count = 0
    for profile, low, high, default_wb in (
            ('raisr_rear_master', 140, 750, (1, 2, 1.0)),
            ('raisr_rear_tele_3x', 90, 400, (2, 1, 1.0))):
        prefix = f'vendor/camera3rd/nti/raisr/{profile}'
        xml = ET.fromstring(verified(f'{prefix}/raisr_param.xml'))
        assert xml.findtext('zoomMode') == '1'
        assert xml.findtext('isoThreshold') == f'{low},{high}'
        for zoom in (1.0, 1.5, 2.0, 4.0):
            for iso, level in ((50, 'lowISO'), (low - 1, 'lowISO'), (low, 'midISO'),
                               (high - 1, 'midISO'), (high, 'higISO'), (3200, 'higISO')):
                for faces in (0, 1):
                    path, group, remaining, wb = execute(xml, profile, zoom, iso, faces)
                    expected = f"{prefix}/{xml.findtext('filter/zoom1/' + level)}"
                    assert path == '/' + expected, (path, expected)
                    verified(expected)
                    assert group == 1 and remaining == zoom, (group, remaining)
                    assert wb == ((2, 6, 6.0) if faces else default_wb), wb
                    count += 1
        print(f'PASS {profile}: zoomMode=1 selects Z1 at 1–4x; ISO <{low} / {low}–{high - 1} / >={high}')
    print(f'PASS {count} native selection cases; XML/libc emulated; no image inference')


if __name__ == '__main__':
    main()
