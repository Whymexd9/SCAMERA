#!/usr/bin/env python3
"""Compare full native NICEFrameCalculator with portable plan packing.

No scene or exposure estimation is stubbed into this comparison: those are
separate, still-unconnected stages. Original instructions choose the row and
pack the complete 0x190-byte result, including echo/EV0/paired-short behavior.
"""
import argparse
import ctypes
import hashlib
import struct
import subprocess
import tempfile
from pathlib import Path
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from capstone.arm64_const import ARM64_OP_IMM
from elftools.elf.elffile import ELFFile
from check_vivo_nice_motion import emulator, invoke
from extract_vivo_nice_tuning import extract

SHA='3268e550541179650a02d7b94895db2430f244c54da2b34bffafbdfdba8fb226'
TUNING_SHA='48c3b6a7b8b971c1e8327d6dfa1302cf5e5b5a623cdd22626e3f728202297186'
DRIVER=r'''
#include "vivo-nice-frame-calculator.h"
extern "C" int calculate(int lens,int mode,int dual,int echo,int more,void* out) {
 try {
  auto result=vivo_nice::calculateNiceFrameInfo(lens,mode,dual?4:0,2,echo,more);
  std::memcpy(out,&result,sizeof(result));return 0;
 } catch(const std::invalid_argument&) {return -1;}
}
'''

class Elf:
    def __init__(self,path):
        self.file=path.open('rb');self.elf=ELFFile(self.file)
    def read(self,address,size):
        for seg in self.elf.iter_segments():
            start=seg['p_vaddr']
            if seg['p_type']=='PT_LOAD' and start<=address and address+size<=start+seg['p_filesz']:
                return seg.data()[address-start:address-start+size]
        raise ValueError(hex(address))
    def string(self,address):
        return self.read(address,160).split(b'\0',1)[0].decode()


def group_layout(elf, start, size):
    # Read named-member offsets from the original tuning loader, independently
    # of the portable variant-name arrays. No decompiler text fixture required.
    md=Cs(CS_ARCH_ARM64,CS_MODE_ARM);md.detail=True
    page=name=offset=None;result={}
    for ins in md.disasm(elf.read(start,size),start):
        text=ins.op_str
        if ins.mnemonic=='adrp' and text.startswith('x2,'):
            page=ins.operands[1].imm
        elif ins.mnemonic=='add' and text.startswith('x2, x2,'):
            name=elf.string(page+ins.operands[2].imm)
        elif ins.mnemonic=='mov' and text=='x0, x19':offset=0
        elif ins.mnemonic=='add' and text.startswith('x0, x19,'):
            offset=ins.operands[2].imm
        elif ins.mnemonic in ('bl','b') and ins.operands[0].type==ARM64_OP_IMM and ins.operands[0].imm==0x1bf6c0:
            assert name and offset is not None
            result[offset]=name
    assert len(result) in (18,25),result
    return result


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('scene_library',type=Path);ap.add_argument('tuning_library',type=Path)
    ap.add_argument('nice_directory',type=Path);args=ap.parse_args()
    assert hashlib.sha256(args.scene_library.read_bytes()).hexdigest()==SHA
    assert hashlib.sha256(args.tuning_library.read_bytes()).hexdigest()==TUNING_SHA
    src, tuning=Elf(args.scene_library),Elf(args.tuning_library)
    layouts={False:group_layout(tuning,0x1a42b4,0x2a4),True:group_layout(tuning,0x1a48f8,0x218)}
    rows=extract(args.nice_directory)['hdr'];u,_=emulator(args.scene_library)
    log,preview,group,evbase=0x1100000,0x1200000,0x1300000,0x1400000
    u.mem_write(log,struct.pack('<i',6));u.mem_write(0x20488,struct.pack('<Q',log))
    assert struct.unpack('<5f',src.read(0x6f08,20))==(-200.,-100.,0.,101.,100.)
    md=Cs(CS_ARCH_ARM64,CS_MODE_ARM);md.detail=True
    def selected_offset(mode,dual):
        if mode<2 or mode>(30 if dual else 32):return 0x20
        addr=(0x6eeb if dual else 0x6ecc)+mode-2
        target=0x17b6c+4*src.read(addr,1)[0]
        if target==0x17c88:return 0
        ins=list(md.disasm(src.read(target,8),target))[-1]
        assert ins.mnemonic=='add' and ins.op_str.startswith('x27, x27,')
        return ins.operands[2].imm
    repo=Path(__file__).resolve().parents[1]
    with tempfile.TemporaryDirectory(prefix='nice-frame-calculator-') as tmp:
        tmp=Path(tmp);(tmp/'driver.cpp').write_text(DRIVER)
        subprocess.run(['c++','-std=c++17','-O2','-shared','-fPIC','-Wall','-Wextra','-Werror',
                        '-I',str(repo/'app/src/main/cpp'),str(tmp/'driver.cpp'),'-o',str(tmp/'driver.so')],check=True)
        calculate=ctypes.CDLL(str(tmp/'driver.so')).calculate
        calculate.argtypes=[ctypes.c_int]*5+[ctypes.c_void_p]
        checked=rejected=0
        for lens in (-1,0,1,2,3,9):
         for dual in (False,True):
          camera=lens if lens in (0,1,2) else 0
          camera_rows={r['name']:r for r in rows if r['lens']==camera and r['dual']==dual}
          for offset,name in layouts[dual].items():
            row=camera_rows.get(name)
            if row:
                raw=struct.pack('<'+'i'*len(row['ev']),*row['ev']);ptr=evbase+offset*8
                u.mem_write(ptr,raw)
                u.mem_write(group+offset,struct.pack('<iiQQQ',row['count0'],row['count1'],ptr,ptr+len(raw),ptr+len(raw)))
            else:u.mem_write(group+offset,bytes(32))
          for mode in range(-2,38):
           for echo in (0,1):
            for more in (0,1):
                name=layouts[dual][selected_offset(mode,dual)]
                row=camera_rows.get(name)
                out=ctypes.create_string_buffer(b'\xa5'*0x190,0x190)
                status=calculate(lens,mode,dual,echo,more,out)
                # Native would retain unknown initialized defaults on a missing
                # row, or read outside a short paired vector. Adapter rejects.
                if row is None or (dual and len(row['ev'])<2*row['count1']):
                    assert status==-1 and out.raw==b'\xa5'*0x190,(lens,mode,dual)
                    rejected+=1;continue
                assert status==0,(lens,mode,dual,echo,more,name)
                u.mem_write(preview,bytes(0x6000))
                u.mem_write(preview+0x3bec,struct.pack('<i',mode))
                u.mem_write(preview+0x3bf0,struct.pack('<Q',group))
                u.mem_write(preview+0x3bf8,bytes([echo,more]))
                u.mem_write(preview+0x3bfc,struct.pack('<i',dual))
                u.mem_write(preview+0x3c10,b'GUAR'+b'\xa5'*0x190+b'TAIL')
                invoke(u,0x17ad4,(0,preview))
                actual=bytes(u.mem_read(preview+0x3c14,0x190))
                assert out.raw==actual,(lens,mode,dual,echo,more,name,
                    [(hex(i),out.raw[i:i+4].hex(),actual[i:i+4].hex()) for i in range(0,0x190,4) if out.raw[i:i+4]!=actual[i:i+4]])
                assert bytes(u.mem_read(preview+0x3c10,4))==b'GUAR'
                assert bytes(u.mem_read(preview+0x3da4,4))==b'TAIL'
                checked+=1
        print(f'PASS: {checked} complete native frame plans; {rejected} missing/unsafe configurations rejected atomically')
        print('Scene/AE estimation, model selection, Camera2 submission and TCE remain separate.')

if __name__=='__main__':main()
