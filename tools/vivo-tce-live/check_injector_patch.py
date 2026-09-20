#!/usr/bin/env python3
"""Execute original/patched ARM64 startup with only runtime init stubbed."""
from pathlib import Path
import hashlib,sys
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM,UC_HOOK_CODE
from unicorn.arm64_const import UC_ARM64_REG_SP,UC_ARM64_REG_LR,UC_ARM64_REG_FP
START=0x7d8dd8; POLICY=0x32d99d0; INIT=0x32d90a4; END=0x900000
original=Path(sys.argv[1]).read_bytes();patched=Path(sys.argv[2]).read_bytes()
assert hashlib.sha256(original).hexdigest()=='d8ce6fe18db97594d1c7a43be4f8fb405fc76f6a01470df34504bca6461328d0'
assert hashlib.sha256(patched).hexdigest()=='4952c58fa7f3caca7816e5e8a2b8be9c2b88b95a28110296504007b661c2fc4f'
for data,expected in [(original,[INIT,POLICY]),(patched,[INIT])]:
    for i in range(8):
        u=Uc(UC_ARCH_ARM64,UC_MODE_ARM)
        for p in {START&~4095,INIT&~4095,END,0x100000}:u.mem_map(p,4096)
        u.mem_write(START,data[START:START+24])
        for p in [INIT,POLICY,END]:u.mem_write(p,bytes.fromhex('c0035fd6'))
        sp=0x100800+i*16;fp=0x12345678+i
        u.reg_write(UC_ARM64_REG_SP,sp);u.reg_write(UC_ARM64_REG_FP,fp);u.reg_write(UC_ARM64_REG_LR,END)
        seen=[]
        u.hook_add(UC_HOOK_CODE,lambda uc,a,n,ctx:seen.append(a) if a in [INIT,POLICY] else None)
        u.emu_start(START,END,count=20)
        assert seen==expected
        assert u.reg_read(UC_ARM64_REG_SP)==sp and u.reg_read(UC_ARM64_REG_FP)==fp
        assert u.reg_read(UC_ARM64_REG_LR)==END
print('PASS: 16 ARM64 executions; runtime initialization retained, automatic policy patch call removed, stack/FP/LR preserved. No device injection tested.')
