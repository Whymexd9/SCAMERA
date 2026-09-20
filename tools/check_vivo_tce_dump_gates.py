#!/usr/bin/env python3
"""Verify PD2454 native dump branches, without changing algorithm instructions.

Only Android property_get and libc atoi imports are supplied by the host.
Stops at branch destinations, before file IO, JSON serialization or image code.
This is not a device stability or complete TCE processing test.
"""
import argparse
import hashlib
import struct
from pathlib import Path
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import *

SHA256 = '9f5deac3bc68fc86fcf16b98f43c232a9642bc309c7d5d42c88d6a4b596b892d'
PROPERTY = b'vendor.vivo.vaf.dump.nice.portraitseg'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('tce', type=Path)
    args = parser.parse_args()
    assert hashlib.sha256(args.tce.read_bytes()).hexdigest() == SHA256
    u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    u.mem_map(0, 0x800000)
    u.mem_map(0x1000000, 0x100000)
    stubs = {}
    with args.tce.open('rb') as f:
        elf = ELFFile(f)
        for segment in elf.iter_segments():
            if segment['p_type'] == 'PT_LOAD':
                u.mem_write(segment['p_vaddr'], segment.data())
        symbols = elf.get_section_by_name('.dynsym')
        for section in elf.iter_sections():
            if section['sh_type'] not in ('SHT_RELA', 'SHT_REL'):
                continue
            for relocation in section.iter_relocations():
                kind = relocation['r_info_type']
                offset = relocation['r_offset']
                addend = relocation.entry.get('r_addend', 0)
                if kind == 1027:
                    u.mem_write(offset, struct.pack('<Q', addend))
                elif kind in (1025, 1026, 257):
                    symbol = symbols.get_symbol(relocation['r_info_sym'])
                    value = symbol['st_value']
                    if not value:
                        value = 0x700000 + 16 * len(stubs)
                        stubs[value] = symbol.name
                    u.mem_write(offset, struct.pack('<Q', value + addend))
    prop_value = b''
    destinations = set()

    def hook(machine, address, _size, _data):
        if address in destinations:
            machine.emu_stop()
            return
        if address not in stubs:
            return
        name = stubs[address]
        x0 = machine.reg_read(UC_ARM64_REG_X0)
        x1 = machine.reg_read(UC_ARM64_REG_X1)
        if name == '__system_property_get':
            assert bytes(machine.mem_read(x0, len(PROPERTY)+1)) == PROPERTY+b'\0'
            machine.mem_write(x1, prop_value+b'\0')
            result = len(prop_value)
        elif name == 'atoi':
            result = int(bytes(machine.mem_read(x0, 16)).split(b'\0')[0])
        else:
            raise AssertionError('Unexpected import: '+name)
        machine.reg_write(UC_ARM64_REG_X0, result & 0xffffffffffffffff)
        machine.reg_write(UC_ARM64_REG_PC, machine.reg_read(UC_ARM64_REG_LR))

    u.hook_add(UC_HOOK_CODE, hook)
    u.reg_write(UC_ARM64_REG_TPIDR_EL0, 0x1000000)
    context, sp = 0x1010000, 0x10f0000

    def branch(start, enabled, disabled):
        nonlocal destinations
        destinations = {enabled, disabled}
        u.reg_write(UC_ARM64_REG_SP, sp)
        u.reg_write(UC_ARM64_REG_X29, sp+0x200)
        u.emu_start(start, 0x7ff000, count=1000)
        pc = u.reg_read(UC_ARM64_REG_PC)
        assert pc in destinations, hex(pc)
        return pc == enabled

    cases = 0
    for level in (0, 1, 3, 4, 5, 6):
        for prop_value in (b'', b'0', b'1', b'-1'):
            active = bool(prop_value and int(prop_value))
            u.mem_write(context+0x2ec, struct.pack('<i', level))
            u.reg_write(UC_ARM64_REG_X21, context)
            u.reg_write(UC_ARM64_REG_X22, 4)
            assert branch(0x3e9f40, 0x3e9f7c, 0x3ea10c) == (active or level >= 4)
            u.reg_write(UC_ARM64_REG_X25, context)
            assert branch(0x3e63a8, 0x3e63b8, 0x3e7620) == (level >= 4)
            assert branch(0x3e7620, 0x3e765c, 0x3e8b44) == (active or level >= 4)
            u.mem_write(sp+0x90, struct.pack('<Q', context))
            u.mem_write(context, struct.pack('<i', level))
            assert branch(0x3c9f80, 0x3c9fa8, 0x3ca03c) == (
                bool(prop_value and int(prop_value) > 0) or level >= 4)
            cases += 1
    print(f'PASS: {cases} property/level combinations, four original ARM64 gates each')
    print('portraitseg=-1, nicetce=0: JSON enabled; RGB/LUT and segmentation YUV dump gates skipped.')
    print('No device execution, JSON serializer or photographic processing tested.')


if __name__ == '__main__':
    main()
