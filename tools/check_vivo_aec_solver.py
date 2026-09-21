#!/usr/bin/env python3
"""Replay N/S/ES/L and selected plan metadata from complete phone inputs.

No output exposure is supplied to the portable solver. MAAE enable, unmodified
output slots 3/5 and opaque metadata are outside this exposure-plan comparison.
"""
import argparse,ctypes,json,struct,subprocess,tempfile
from pathlib import Path
from check_vivo_aec_context import read
DRIVER=r'''
#include "vivo-aec-wire.h"
#include <cstdio>
extern "C" int plan(const unsigned char* payload,unsigned size,unsigned char* output) {
 try {
  auto value=vivo_aec::encodeSolverPlan(vivo_aec::solveExposures(vivo_aec::decodeSolverSnapshot(payload,size)));
  std::memcpy(output,value.data(),value.size());return 0;
 }catch(const std::exception& e){std::fprintf(stderr,"%s\n",e.what());return -1;}
}
'''
def encode_bank(bank):
    result=struct.pack('<I',len(bank['tables']))
    for t in bank['tables']:
        header=bytes.fromhex(t['header']);rows=bytes.fromhex(t['rows'])
        n=struct.unpack_from('<I',header,4)[0];assert len(rows)==24*n
        result+=header[:4]+header[24:28]+struct.pack('<I',n)
        for i in range(n):result+=rows[24*i:24*i+4]+rows[24*i+8:24*i+20]
    blur=bytes.fromhex(bank['blurRows']);assert len(blur)%12==0
    return result+struct.pack('<I',len(blur)//12)+blur
def main():
    ap=argparse.ArgumentParser();ap.add_argument('logs',nargs='+',type=Path);args=ap.parse_args()
    root=Path(__file__).resolve().parents[1];total=0
    with tempfile.TemporaryDirectory(prefix='ae-solver-') as tmp:
        d=Path(tmp);(d/'driver.cpp').write_text(DRIVER)
        subprocess.run(['c++','-std=c++17','-O2','-ffp-contract=off','-shared','-fPIC','-Wall','-Wextra','-Werror',
            '-I',str(root/'app/src/main/cpp'),str(d/'driver.cpp'),'-o',str(d/'driver.so')],check=True)
        lib=ctypes.CDLL(str(d/'driver.so'));ptr=ctypes.POINTER(ctypes.c_ubyte);lib.plan.argtypes=[ptr,ctypes.c_uint,ptr]
        for path in args.logs:
            scalars={}
            for line in path.read_text(encoding='utf-8-sig').splitlines():
                if not line.startswith('SCAMERA_AE_CONTEXT '):continue
                e=json.loads(line.split(' ',1)[1])
                if e['event']=='accessor' and e['data'] is None:
                    key=(e['pid'],e['sample'],e['offset']);v=e['scalar']
                    if key in scalars:assert scalars[key]==v
                    scalars[key]=v
            count=0
            for start,end,data in read(path):
                fields=[bytes.fromhex(start[k]) for k in ('input','common','calculator')]
                fields += [data[k] for k in (0x298,0x300,0xc0,0x10,0x2a0,0x2c0)]
                fields += [bytes.fromhex(start['motion'])]
                assert list(map(len,fields))==[0xe8,0xb8,0x74,0x930,0x440,12,8,0xb0,0x44,0x65c]
                sensorType=scalars[(start['pid'],start['sample'],0x168)]
                payload=b''.join(fields)+struct.pack('<i',sensorType)+encode_bank(start['bank'])+encode_bank(start['alternateBank'])
                incoming=(ctypes.c_ubyte*len(payload)).from_buffer_copy(payload);out=(ctypes.c_ubyte*100)()
                assert lib.plan(incoming,len(payload),out)==0,(path.name,start['sample'])
                native=bytes.fromhex(end['output']);params=bytes.fromhex(end['input'])
                expected=native[:48]+native[64:80]+native[96:108]+native[116:132]+params[0x60:0x64]+params[0xd8:0xdc]
                assert bytes(out)==expected,(path.name,start['sample'],[(i,bytes(out)[i:i+4].hex(),expected[i:i+4].hex()) for i in range(0,100,4) if bytes(out)[i:i+4]!=expected[i:i+4]])
                count+=1
            print(f'PASS: {path.name}: {count} complete N/S/ES/L + plan metadata, no output oracle');total+=count
    print(f'PASS: {total} phone exposure plans; MAAE and Camera2 request mapping are not tested')
if __name__=='__main__':main()
