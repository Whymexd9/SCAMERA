#!/usr/bin/env python3
"""Original RAW->guide oracle. Requires pyelftools, unicorn and a C++17 compiler.

The donor receives two RAW frame descriptors, first reference and then donor,
with measured exposure products 1 and 1/gain. Its original guide builder runs
unpatched. The same bytes and resulting ratio are passed to the C++ port.
Gamma=.6 follows the recovered default constructor at 0x3ce2a0.
"""
import argparse, hashlib, struct, random, subprocess, tempfile
from pathlib import Path
from check_vivo_nice_motion import emulator,invoke,UC_ARM64_REG_S0,CRE_SHA256
DRIVER='#include "vivo-nice-guide.h"\n#include <iostream>\nint main(int argc,char**argv){\n int w=std::stoi(argv[1]),h=std::stoi(argv[2]),bits=std::stoi(argv[3]);\n float gain=std::stof(argv[4]),gamma=std::stof(argv[5]);\n std::vector<uint16_t> raw(size_t(w)*h);std::cin.read((char*)raw.data(),raw.size()*2);\n auto out=vivo_nice::stockMotionGuide4(raw.data(),w,h,w,bits,gain,gamma);\n std::cout.write((char*)out.data(),out.size());\n}\n'

parser=argparse.ArgumentParser(description='Compare the C++ RAW motion guide with pinned CRE ARM64 code')
parser.add_argument('library',type=Path)
parser.add_argument('--cxx',default='c++')
args=parser.parse_args()
if hashlib.sha256(args.library.read_bytes()).hexdigest()!=CRE_SHA256:
 parser.error('unsupported CRE binary: SHA-256 mismatch')
repo=Path(__file__).resolve().parents[1]
temporary=tempfile.TemporaryDirectory(prefix='nice-guide-')
folder=Path(temporary.name)
driver=folder/'guide.cpp'
driver.write_text(DRIVER)
executable=folder/'guide'
subprocess.run([args.cxx,'-std=c++17','-O2','-I',str(repo/'app/src/main/cpp'),str(driver),'-o',str(executable)],check=True)
worst=0;total=0
for w,h in [(128,128),(132,100)]:
 for bits in [10,12,14]:
  for gain in [.25,1,4]:
   u,calls=emulator(args.library)
   rng=random.Random(42);raw=[rng.randrange(0,1<<bits) for _ in range(w*h)]
   payload=struct.pack('<'+'H'*len(raw),*raw);u.mem_write(0x1100000,payload)
   for f in range(2):
    p=0x10b0018+f*0x400
    u.mem_write(p,struct.pack('<III',w,h,w*2));u.mem_write(p+0x38,struct.pack('<Q',0x1100000));u.mem_write(p+0x68,struct.pack('<I',bits))
    u.mem_write(p+0x78,struct.pack('<ff',1 if f==0 else 1/gain,0 if f==0 else 1));u.mem_write(p+0xb4,struct.pack('<f',1))
   u.mem_write(0x10a0000,struct.pack('<QQ',0x10b0000,0x10b0400))
   u.mem_write(0x1090000,struct.pack('<QQQ',0x10a0000,0x10a0010,0x10a0010))
   gamma=.6
   u.reg_write(UC_ARM64_REG_S0,struct.unpack('<I',struct.pack('<f',gamma))[0])
   status=invoke(u,0x26fde4,(0x1070000,0x1090000,4))
   begin,end,capacity=struct.unpack('<QQQ',u.mem_read(0x10700a8,24))
   assert status==0 and end-begin==128,(status,end-begin)
   fmt,gw,gh=struct.unpack('<III',u.mem_read(begin+64,12))
   assert (fmt,gw,gh)==(9,w//4,h//4)
   stride=struct.unpack('<I',u.mem_read(begin+64+48,4))[0]
   assert stride==gw
   data=struct.unpack('<Q',u.mem_read(begin+64+16,8))[0]
   expected=bytes(u.mem_read(data,(w//4)*(h//4)))
   actual=subprocess.run([str(executable),str(w),str(h),str(bits),str(gain),str(gamma)],input=payload,capture_output=True,check=True).stdout
   assert len(expected)==len(actual)
   bad=sum(a!=b for a,b in zip(expected,actual));error=max(abs(a-b) for a,b in zip(expected,actual));total+=len(actual);worst=max(error,worst)
   print(w,h,bits,gain,'bad',bad,'max',error,flush=True)
   assert bad==0,[(i,a,b) for i,(a,b) in enumerate(zip(expected,actual)) if a!=b][:8]
print('PASS: original RAW guide matches C++ bit-for-bit:',total,'samples; device integration untested')
temporary.cleanup()
