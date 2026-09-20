#!/usr/bin/env python3
"""Compare PD2454 tuning ports with original ARM64 code and supplied JSON.

Original normal-frame selector, TCE filename selector, and HDR entry reader run
in Unicorn. Only allocation/memory/formatting/cJSON imports are shimmed. This
is not a scene decision, Camera2 integration, or photographic TCE test.
"""
import argparse
import ctypes
import hashlib
import math
import struct
import subprocess
import tempfile
from pathlib import Path
from elftools.elf.elffile import ELFFile
from unicorn import UC_HOOK_CODE
from unicorn.arm64_const import *
from check_vivo_nice_motion import emulator, invoke
from extract_vivo_nice_tuning import extract, header, ObjectPairs

SHA = '48c3b6a7b8b971c1e8327d6dfa1302cf5e5b5a623cdd22626e3f728202297186'
DRIVER = r'''
#include "vivo-nice-tuning.h"
using namespace vivo_nice::tuning;
extern "C" int normal(int lens,float zoom,int mode,int special,int seamless) {
 try { return normalFrameCount(lens,zoom,mode,special,seamless); }
 catch(const std::invalid_argument&) {return -1;}
}
extern "C" int effect(int lens,float zoom,char* out) {
 try {auto r=selectTceEffect(lens,zoom);std::strcpy(out,r.filename);return r.matched;}
 catch(const std::invalid_argument&) {return -1;}
}
extern "C" int group(int lens,int seamless,int version) {
 bool dual=usesDualHdrGroup(seamless,version);
 auto row=findHdrRow(lens,dual,"nicehdrBinning");
 return (dual?100:0)+row->lens;
}
extern "C" int hdr(int lens,int dual,const char* name,int* out) {
 auto r=findHdrRow(lens,dual,name);if(!r)return -1;
 out[0]=r->count0;out[1]=r->count1;out[2]=r->evSize;
 for(size_t i=0;i<r->evSize;++i)out[3+i]=r->ev[i];
 return 0;
}
'''


def packed_relocations(elf):
    data = elf.get_section_by_name('.rela.dyn').data()
    if data[:4] != b'APS2': raise ValueError('Expected APS2 relocations')
    pos = 4
    def sleb():
        nonlocal pos
        value = shift = 0
        while True:
            b = data[pos]; pos += 1
            value |= (b & 127) << shift; shift += 7
            if not b & 128: return value - (1 << shift) if b & 64 else value
    count, offset, addend = sleb(), sleb(), 0
    while count:
        size, flags = sleb(), sleb()
        if size <= 0 or size > count: raise ValueError('Invalid relocation group')
        delta = sleb() if flags & 2 else 0
        info = sleb() if flags & 1 else 0
        if flags & 8 and flags & 4: addend += sleb()
        elif not flags & 8: addend = 0
        for _ in range(size):
            offset += delta if flags & 2 else sleb()
            if not flags & 1: info = sleb()
            if flags & 8 and not flags & 4: addend += sleb()
            yield offset, info, addend
        count -= size
    if pos != len(data): raise ValueError('Trailing relocation bytes')


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('library', type=Path)
    ap.add_argument('nice_directory', type=Path)
    args = ap.parse_args()
    if hashlib.sha256(args.library.read_bytes()).hexdigest() != SHA:
        raise SystemExit('Unsupported tuning donor')
    config = extract(args.nice_directory)
    repo = Path(__file__).resolve().parents[1]
    assert header(config) == (repo/'app/src/main/cpp/vivo-nice-tuning-data.h').read_text()
    u, _ = emulator(args.library)
    cursor = 0x1100000
    def alloc(data):
        nonlocal cursor
        address = cursor; cursor += (len(data)+15)//16*16
        u.mem_write(address, data)
        return address
    def cstr(address):
        out = bytearray()
        while (b := u.mem_read(address+len(out),1)[0]): out.append(b)
        return bytes(out)
    def vector(values, fmt):
        raw = b''.join(struct.pack(fmt, *v) if isinstance(v,list) else struct.pack(fmt,v) for v in values)
        ptr = alloc(raw)
        return struct.pack('<QQQ',ptr,ptr+len(raw),ptr+len(raw))
    with args.library.open('rb') as f:
        elf = ELFFile(f); syms = elf.get_section_by_name('.dynsym')
        symbols = {s.name:s['st_value'] for s in syms.iter_symbols()}
        # Resolve existing donor data symbols, not synthetic pointer guesses.
        for off, info, add in packed_relocations(elf):
            if info & 0xffffffff in (257,1025):
                sym = syms.get_symbol(info>>32)
                if sym['st_value']: u.mem_write(off,struct.pack('<Q',sym['st_value']+add))
    for lens,row in enumerate(config['normal']):
        for fragment, vals, fmt in [
            (f'g_zoom_trigger_{"mutp"[lens]}',row['zoom'],'<2f'),
            (f'g_nice_normal_frame_num_{"wutp"[lens]}',row['counts'],'<i'),
            (f'g_nice_normal_frame_num_{"wutp"[lens]}_r',row['counts_r'],'<i')]:
            address = symbols[f'_ZN10NiceConfig{len(fragment)}{fragment}E']
            u.mem_write(address,vector(vals,fmt))
        if lens == 2: u.mem_write(0x263a50,vector(row['special'],'<i'))
    u.mem_write(0x266fc0,struct.pack('<i',config['stagger']))
    log = alloc(struct.pack('<i',6));u.mem_write(0x1d15d8,struct.pack('<Q',log))
    input_ptr, preview, output, vec = 0x1800000,0x1810000,0x1820000,0x1830000

    # cJSON ABI nodes retain duplicate keys via linked children.
    def node(value, name=''):
        raw = bytearray(64)
        struct.pack_into('<Q',raw,56,alloc(name.encode()+b'\0'))
        ptr = alloc(bytes(raw))
        if isinstance(value,(ObjectPairs,list)):
            members = value if isinstance(value,ObjectPairs) else [('',v) for v in value]
            children = [node(v,k) for k,v in members]
            if children: u.mem_write(ptr+16,struct.pack('<Q',children[0]))
            for i,c in enumerate(children):
                u.mem_write(c,struct.pack('<QQ',children[i+1] if i+1<len(children) else 0,children[i-1] if i else 0))
        elif isinstance(value,(int,float)):
            u.mem_write(ptr+40,struct.pack('<i',int(value)))
            u.mem_write(ptr+48,struct.pack('<d',float(value)))
        return ptr
    def children(ptr):
        ptr = struct.unpack('<Q',u.mem_read(ptr+16,8))[0]
        out = []
        while ptr:
            out.append(ptr); ptr = struct.unpack('<Q',u.mem_read(ptr,8))[0]
        return out
    def imports(u,address,size,data):
        a,b = u.reg_read(UC_ARM64_REG_X0),u.reg_read(UC_ARM64_REG_X1)
        if address == 0x1bc510:
            key = cstr(b).lower();ret = 0
            for child in children(a):
                name = struct.unpack('<Q',u.mem_read(child+56,8))[0]
                if cstr(name).lower() == key: ret = child;break
        elif address == 0x1bc528:
            items = children(a);ret = items[b] if b < len(items) else 0
        elif address == 0x1bc570: ret = len(children(a))
        elif address == 0xcd0f4:
            fmt = cstr(u.reg_read(UC_ARM64_REG_X3))
            text = cstr(u.reg_read(UC_ARM64_REG_X4)) if fmt == b'%s' else fmt
            u.mem_write(a,text+b'\0');ret = len(text)
        else: raise AssertionError(hex(address))
        u.reg_write(UC_ARM64_REG_X0,ret);u.reg_write(UC_ARM64_REG_PC,u.reg_read(UC_ARM64_REG_LR))
    for address in (0x1bc510,0x1bc528,0x1bc570,0xcd0f4):
        u.hook_add(UC_HOOK_CODE,imports,begin=address,end=address)

    with tempfile.TemporaryDirectory(prefix='vivo-tuning-') as tmp:
        tmp = Path(tmp);(tmp/'test.cpp').write_text(DRIVER)
        subprocess.run(['c++','-std=c++17','-O2','-shared','-fPIC','-Wall','-Wextra','-Werror',
                        '-I',str(repo/'app/src/main/cpp'),str(tmp/'test.cpp'),'-o',str(tmp/'test.so')],check=True)
        lib = ctypes.CDLL(str(tmp/'test.so'))
        lib.normal.argtypes = [ctypes.c_int,ctypes.c_float,ctypes.c_int,ctypes.c_int,ctypes.c_int]
        lib.effect.argtypes = [ctypes.c_int,ctypes.c_float,ctypes.c_void_p]
        lib.hdr.argtypes = [ctypes.c_int,ctypes.c_int,ctypes.c_char_p,ctypes.c_void_p]
        checked = 0
        for lens in (-1,0,1,2,3,4):
          for zoom in (-1.,0.,1.99999,2.,3.99999,4.,99.999,100.,101.):
           for mode in (0,1,2,3):
            for special in (0,1,2):
             for seamless in (0,4,6,0x500,0x502):
                u.mem_write(input_ptr,struct.pack('<ifii',lens,zoom,mode,special))
                u.mem_write(preview+0x5f0c,struct.pack('<i',seamless))
                expected = invoke(u,0x16a710,(0,preview,input_ptr))
                actual = lib.normal(lens,zoom,mode,special,seamless)
                assert expected == actual,(lens,zoom,mode,special,seamless,expected,actual)
                checked += 1
        effects = 0
        for lens,row in enumerate(config['normal']):
            data = b''.join(struct.pack('<f',float(t))+name.encode().ljust(128,b'\0') for t,name in row['effects'])
            ptr = alloc(data);u.mem_write(vec,struct.pack('<QQQ',ptr,ptr+len(data),ptr+len(data)))
            for zoom in (-1.,0.,2.99999,3.,9.99999,10.,29.99999,30.,998.99,999.,1000.):
                u.reg_write(UC_ARM64_REG_S0,struct.unpack('<I',struct.pack('<f',zoom))[0])
                expected = invoke(u,0x170078,(0,vec,output,256))
                buf = ctypes.create_string_buffer(256)
                actual = lib.effect(lens,zoom,buf)
                assert (actual,buf.value) == (expected,cstr(output)),(lens,zoom,actual,expected,buf.value,cstr(output))
                effects += 1
        # Native std::map layout: root at map+8, node left/right at 0/8,
        # key at 0x20, group value at 0x28. Construct known config keys 0/1/2.
        groups_by_pointer = {}
        for dual, address in ((False,0x266fc8),(True,0x266fe0)):
            nodes = []
            for lens in range(3):
                item = bytearray(0x30);struct.pack_into('<i',item,0x20,lens)
                ptr = alloc(bytes(item));nodes.append(ptr)
                groups_by_pointer[ptr+0x28] = (100 if dual else 0)+lens
            u.mem_write(nodes[1],struct.pack('<QQ',nodes[0],nodes[2]))
            u.mem_write(address,struct.pack('<QQQ',nodes[0],nodes[1],3))
        groups = 0
        for lens in (-1,0,1,2,3,9):
          for seamless in (0,4,6,0x500,0x502):
           for version in (0,1,2,3):
            u.mem_write(preview+0x44c,struct.pack('<i',lens))
            u.mem_write(preview+0x5f0c,struct.pack('<i',seamless))
            u.mem_write(preview+0x3c04,struct.pack('<i',version))
            invoke(u,0x16b284,(0,preview))
            ptr = struct.unpack('<Q',u.mem_read(preview+0x3bf0,8))[0]
            native = groups_by_pointer[ptr]
            assert lib.group(lens,seamless,version) == native
            assert struct.unpack('<i',u.mem_read(preview+0x3bfc,4))[0] == native//100
            groups += 1
        entries = 0
        for row in config['hdr']:
            parent = node(ObjectPairs([(row['name'],ObjectPairs([('value',[row['count0'],row['count1'],row['ev']])]))]))
            name = alloc(row['name'].encode()+b'\0')
            ptr = alloc(b'\xa5'*128)
            initial = struct.pack('<iiQQQ',-17,-23,ptr,ptr+128,ptr+128)
            u.mem_write(output,initial)
            invoke(u,0x1a3fd0,(output,parent,name))
            a,b,start,end,_ = struct.unpack('<iiQQQ',u.mem_read(output,32))
            n = (end-start)//4
            expected = [a,b,n,*struct.unpack('<'+'i'*n,u.mem_read(start,n*4))]
            buf = (ctypes.c_int*35)()
            assert lib.hdr(row['lens'],row['dual'],row['name'].encode(),buf) == 0
            assert list(buf)[:3+n] == expected,row
            assert bytes(u.mem_read(end,128-n*4)) == b'\xa5'*(128-n*4)
            entries += 1
        for lens in range(4):
            assert lib.normal(lens,math.nan,0,0,0) == -1
            assert lib.effect(lens,math.inf,ctypes.create_string_buffer(256)) == -1
        assert lib.hdr(2,0,b'nicehdrBinningLivePhoto',(ctypes.c_int*35)()) == -1
        print(f'PASS: {checked} native frame counts, {effects} native TCE selections, {entries} native HDR row reads, {groups} native HDR group choices')
        print('No scene inference, capture submission, or TCE image call is exercised.')

if __name__ == '__main__': main()
