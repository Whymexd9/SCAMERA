#!/usr/bin/env python3
"""Replay copied AE inputs against the portable calculation; never infer capture identity."""
import argparse,ctypes,json,struct,subprocess,tarfile,tempfile
from pathlib import Path
# Reuse the wrapper text without importing the ARM64 emulator dependencies.
import ast
_wrappers=ast.parse(Path(__file__).with_name('check_vivo_aec_table.py').read_text())
TABLE_DRIVER=next(ast.literal_eval(node.value) for node in _wrappers.body
                 if isinstance(node,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='DRIVER' for t in node.targets))
DRIVER=TABLE_DRIVER+r'''
#include "vivo-aec-adjust.h"
extern "C" int adjust(unsigned char* packet, const float* p, int banding, int blur, int mode) {
 try {
  vivo_aec::Exposure value;float factor;
  std::memcpy(&value,packet,16);std::memcpy(&factor,packet+16,4);
  auto out=vivo_aec::normalExposureWithoutBlur(value,factor,
       {p[0],p[1],p[2],p[3],p[4],p[5],p[6],banding!=0,blur!=0,mode});
  std::memcpy(packet,&out.value,16);std::memcpy(packet+16,&out.correction,4);
  return 0;
 } catch(const std::invalid_argument&) { return -1; }
}
'''
def f32(s):return struct.unpack('<f',bytes.fromhex(s))[0]
def events(path):
    with tarfile.open(path) as tar:
        members=[m for m in tar.getmembers() if m.name in ('trace.log','./trace.log')]
        assert len(members)==1 and members[0].isfile() and members[0].size<=16*1024*1024
        return [json.loads(l[12:]) for l in tar.extractfile(members[0]).read().decode().splitlines()
                if l.startswith('SCAMERA_ZSL ')]
def main():
    ap=argparse.ArgumentParser();ap.add_argument('archive',type=Path);args=ap.parse_args()
    ev=events(args.archive);tables={};solvers={};calls=set()
    assert not [e for e in ev if e['event']=='ae_tuning_error']
    for e in ev:
        if e['event']=='ae_tuning_table':
            key=(e['pid'],e['tableId']);assert key not in tables
            h=bytes.fromhex(e['header']);r=bytes.fromhex(e['rows']);n=e['count']
            assert len(h)==32 and e['stride']==24 and 2<=n<=1024 and len(r)==24*n
            assert struct.unpack_from('<I',h,4)[0]==n
            tables[key]=(struct.unpack_from('<f',h)[0],struct.unpack_from('<f',h,24)[0],
                         [struct.unpack_from('<fIQII',r,i*24) for i in range(n)])
        if e['event']=='ae_tuning_solver':
            key=(e['pid'],e['solverId']);assert key not in solvers and e['returnBits']==0
            solvers[key]=e
    root=Path(__file__).resolve().parents[1];counts={'lookup':0,'adjust':0}
    with tempfile.TemporaryDirectory(prefix='ae-trace-') as tmp:
        d=Path(tmp);(d/'driver.cpp').write_text(DRIVER)
        subprocess.run(['c++','-std=c++17','-O2','-ffp-contract=off','-shared','-fPIC',
                        '-Wall','-Wextra','-Werror','-I',str(root/'app/src/main/cpp'),
                        str(d/'driver.cpp'),'-o',str(d/'driver.so')],check=True)
        lib=ctypes.CDLL(str(d/'driver.so'))
        lib.lookup.argtypes=[ctypes.POINTER(ctypes.c_ubyte),ctypes.c_float,ctypes.c_uint,
                            ctypes.POINTER(ctypes.c_float),ctypes.POINTER(ctypes.c_uint64),ctypes.POINTER(ctypes.c_uint)]
        lib.adjust.argtypes=[ctypes.POINTER(ctypes.c_ubyte),ctypes.POINTER(ctypes.c_float),
                            ctypes.c_int,ctypes.c_int,ctypes.c_int]
        for e in ev:
            if e['event'] not in ('ae_tuning_lookup','ae_tuning_adjust'):continue
            assert e['returnBits']==0 and solvers[(e['pid'],e['solverId'])]['thread']==e['thread']
            key=(e['pid'],e['callId']);assert key not in calls;calls.add(key)
            divisor,tolerance,rows=tables[(e['pid'],e['tableId'])];n=len(rows)
            initial=bytes.fromhex(e['before']);expected=bytes.fromhex(e['after'])
            if e['event']=='ae_tuning_lookup':
                assert len(initial)==len(expected)==24
                out=(ctypes.c_ubyte*24).from_buffer_copy(initial)
                status=lib.lookup(out,divisor,n,(ctypes.c_float*n)(*[r[0] for r in rows]),
                                  (ctypes.c_uint64*n)(*[r[2] for r in rows]),
                                  (ctypes.c_uint*n)(*[r[3] for r in rows]))
                counts['lookup']+=1
            else:
                assert len(initial)==len(expected)==16
                flags=bytes.fromhex(e['flags']);assert len(flags)==2
                active=bool(e['blurCount'] and e['blurRows'] and flags[1] and not e['blurDisabled'])
                initial+=bytes.fromhex(e['factorBefore']);expected+=bytes.fromhex(e['factorAfter'])
                assert len(initial)==len(expected)==20
                out=(ctypes.c_ubyte*20).from_buffer_copy(initial)
                # sensorMinGain is not captured. It is unreachable for the
                # observed nonzero table floor; refuse traces needing it.
                assert rows[0][0]>=1.e-6
                params=(ctypes.c_float*7)(rows[0][0],rows[-1][0],rows[0][2],rows[-1][2],
                                         f32(e['period']),tolerance,0.)
                status=lib.adjust(out,params,flags[0],active,e['mode'])
                counts['adjust']+=1
                # Unsupported blur must reject atomically, not silently skip.
                refused=(ctypes.c_ubyte*20).from_buffer_copy(initial)
                assert lib.adjust(refused,params,flags[0],1,e['mode'])==-1
                assert bytes(refused)==initial
            assert status==0 and bytes(out)==expected,(e['event'],e['solverId'],status,bytes(out).hex(),expected.hex())
    assert all(counts.values())
    print(f"PASS: {counts['lookup']} table lookups and {counts['adjust']} adjustments match phone output byte-for-byte")
    print(f'{len(tables)} table snapshots; {len(solvers)} complete solver calls; active blur rejected without mutation')
    print('This verifies observed calculation branches, not stock scheduling or Camera2 integration.')
if __name__=='__main__':main()
