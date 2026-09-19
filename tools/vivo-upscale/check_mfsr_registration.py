#!/usr/bin/env python3
"""Execute pinned CHI registration, read stage names; NOT image inference.
Usage: check_mfsr_registration.py /path/com.qti.feature2.mfsr.sm8750.so
Requires pyelftools and unicorn. Does not invoke opaque factory callbacks.
"""
import hashlib,io,json,struct,sys
from pathlib import Path
from elftools.elf.elffile import ELFFile
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM
from unicorn.arm64_const import UC_ARM64_REG_X0,UC_ARM64_REG_LR
b=Path(sys.argv[1]).read_bytes()
m=json.loads(Path(__file__).with_name('manifest.json').read_text())
expected=next(x['sha256'] for x in m['files'] if x['path']=='vendor/lib64/com.qti.feature2.mfsr.sm8750.so')
assert hashlib.sha256(b).hexdigest()==expected,'Unverified SM8750 feature binary'
e=ELFFile(io.BytesIO(b));segments=[s for s in e.iter_segments() if s['p_type']=='PT_LOAD']
end=max(s['p_vaddr']+s['p_memsz'] for s in segments)
u=Uc(UC_ARCH_ARM64,UC_MODE_ARM);u.mem_map(0,(end+4095)//4096*4096)
for s in segments:u.mem_write(s['p_vaddr'],s.data())
u.mem_map(0x10000000,4096);u.reg_write(UC_ARM64_REG_X0,0x10000000);u.reg_write(UC_ARM64_REG_LR,0x10000800)
u.emu_start(0x4f860,0x10000800,count=100)
ops=struct.unpack('<II6Q',bytes(u.mem_read(0x10000000,0x38)))
assert ops==(0x38,0,0x1c4cc,0,0x4f8b0,0x4f8c0,0x4fc40,0x4fc50),ops
print('PASS original ChiFeature2OpsEntry: 56-byte registration and four callback addresses')
sym=e.get_section_by_name('.dynsym').get_symbol_by_name('MFSRStageDescriptor')[0]
assert sym['st_size']==320
stages=[]
for i in range(10):
    stage,name,count,session=struct.unpack('<4Q',bytes(u.mem_read(sym['st_value']+i*32,32)))
    assert stage==i and count==1
    title=bytes(u.mem_read(name,64)).split(b'\0')[0].decode('ascii')
    stages.append(title)
print('SM8750 stages:',', '.join(stages))
print('No factory/session/request callbacks or inference executed.')
