#!/usr/bin/env python3
"""Replay table selection and EVPlusCalc against complete, scoped phone snapshots.

The N result is an explicit oracle input: this does NOT verify EVBaseCalc or
EVMinusCalc, live Camera2 provenance, or sensor gain conversion.
"""
import argparse, ctypes, json, struct, subprocess, tempfile
from pathlib import Path

DRIVER=r'''
#include "vivo-aec-long.h"
#include <cstring>
extern "C" int replay(const unsigned char* params,const unsigned char* common,
 const unsigned char* calculator,const unsigned char* state,const unsigned char* sensor,
 const unsigned char* bank,const unsigned char* header,const unsigned char* rows,
 const unsigned char* blur,unsigned char* output) {
 auto u32=[](const unsigned char* p){uint32_t v;std::memcpy(&v,p,4);return v;};
 auto u64=[](const unsigned char* p){uint64_t v;std::memcpy(&v,p,8);return v;};
 auto f32=[](const unsigned char* p){float v;std::memcpy(&v,p,4);return v;};
 try {
  const int mode=int(u32(params+0xc4));
  const vivo_aec::NiceCaptureFlags flags{u32(calculator+0x54),u32(calculator+0x58),
   u32(calculator+0x5c),u32(calculator+0x60),u32(calculator+0x64),u32(calculator+0x68),u32(calculator+0x50)};
  if(vivo_aec::hdrRunMode(int64_t(u64(state+0xb8)),flags.modeOverride)!=mode)return 1;
  const unsigned type=vivo_aec::exposureTableType(mode,int(u32(calculator+0x3c)),flags);
  const auto selection=vivo_aec::hdrModeAndTableId(mode,type);
  if(type!=u32(output+0x74) || selection.tableId!=u32(output+0x78))return 2;
  auto hdrFlags=selection.hdrFlags;
  if(hdrFlags&0x5400)hdrFlags|=2;
  if(hdrFlags!=u32(output+0x68))return 3;
  std::vector<vivo_aec::TableRow> table;
  for(unsigned i=0;i<u32(header+4);++i) table.push_back({f32(rows+24*i),u64(rows+24*i+8),u32(rows+24*i+16)!=0});
  std::vector<vivo_aec::BlurRow> motionTable;
  for(unsigned i=0;i<u32(bank+0x38);++i)motionTable.push_back({f32(blur+12*i),f32(blur+12*i+4),f32(blur+12*i+8)});
  vivo_aec::Exposure normal;std::memcpy(&normal,output,16);
  // EVGapCalc selects its tuning offsets by context mode, not HDR run mode.
  unsigned gapOffset=u32(state)==10 ? 0x8c : 0x80;
  const auto gaps=vivo_aec::exposureGaps(f32(common+gapOffset),f32(common+gapOffset+4),f32(common+gapOffset+8));
  vivo_aec::LongPlanInput input{u64(params),f32(params+8),f32(params+12),
   f32(params+0x38),f32(params+0x5c),f32(params+0x60),f32(params+0xd8),gaps.longEv,mode,flags};
  // This matches VivoNormalEVExpAdjust's original motion-active guard.
  bool active=common[0x29]!=0 && u32(calculator+0x2c)==0;
  auto result=vivo_aec::plannedLongExposure(normal,input,f32(common+0x50),f32(header),table,
   {table.front().gain,table.back().gain,float(table.front().shutter),float(table.back().shutter),
    f32(calculator),f32(header+24),f32(sensor+8),common[0x28]!=0,active,0},motionTable,0.f);
  std::memcpy(output+0x40,&result,16);return 0;
 }catch(const std::exception&){return -1;}
}
'''

def read(path):
    events=[]
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        if line.startswith('SCAMERA_AE_CONTEXT '):events.append(json.loads(line.split(' ',1)[1]))
    assert sum(e['event']=='ready' for e in events)==1
    assert sum(e['event']=='finished' for e in events)==1
    assert not [e for e in events if e['event']=='error']
    groups={}
    for e in events:
        if 'sample' in e:groups.setdefault((e['pid'],e['sample']),[]).append(e)
    for key,items in groups.items():
        starts=[e for e in items if e['event']=='enter'];ends=[e for e in items if e['event']=='leave']
        assert len(starts)==len(ends)==1,(key,'incomplete/duplicate scope')
        start,end=starts[0],ends[0]
        assert end['returnBits']==0 and start['thread']==end['thread']
        data={}
        for e in items:
            assert e['thread']==start['thread']
            if e['event']=='accessor' and e['data'] is not None:
                value=bytes.fromhex(e['data'])
                if e['offset'] in data:assert data[e['offset']]==value,'accessor changed within scope'
                data[e['offset']]=value
        yield start,end,data

def main():
    ap=argparse.ArgumentParser();ap.add_argument('logs',nargs='+',type=Path);args=ap.parse_args()
    root=Path(__file__).resolve().parents[1];total=0
    with tempfile.TemporaryDirectory(prefix='ae-context-') as tmp:
        d=Path(tmp);(d/'driver.cpp').write_text(DRIVER)
        subprocess.run(['c++','-std=c++17','-O2','-ffp-contract=off','-shared','-fPIC',
            '-Wall','-Wextra','-Werror','-I',str(root/'app/src/main/cpp'),str(d/'driver.cpp'),'-o',str(d/'driver.so')],check=True)
        lib=ctypes.CDLL(str(d/'driver.so'));lib.replay.argtypes=[ctypes.POINTER(ctypes.c_ubyte)]*10
        for path in args.logs:
            count=0
            for start,end,data in read(path):
                params=bytes.fromhex(end['input']);expected=bytes.fromhex(end['output'])
                assert len(params)==0xe8 and len(expected)==0x84
                # DeltaEV and its multiplier can be changed by applyDebugDeltaEV.
                before=bytes.fromhex(start['input'])
                assert len(before)==0xe8
                for i in range(len(params)):
                    if i not in range(0x60,0x64) and i not in range(0xd8,0xdc):assert params[i]==before[i]
                mode=struct.unpack_from('<i',params,0xc4)[0]
                bank=start['alternateBank'] if 9<=mode<=13 else start['bank']
                tableId=struct.unpack_from('<I',expected,0x78)[0]
                assert tableId<len(bank['tables'])
                table=bank['tables'][tableId];header=bytes.fromhex(table['header']);rows=bytes.fromhex(table['rows'])
                assert len(header)==32
                rowCount=struct.unpack_from('<I',header,4)[0]
                assert 1<=rowCount<=1024 and len(rows)==24*rowCount
                # Current complete observations have inactive motion. Do not invent
                # the motion input when replaying a newly collected active branch.
                common=bytes.fromhex(start['common']);calc=bytes.fromhex(start['calculator'])
                assert len(common)==0xb8 and len(calc)==0x74
                assert len(data[0x298])==0x930 and len(data[0xc0])==12
                bankHeader=bytes.fromhex(start['bank']['header'])
                blurRows=bytes.fromhex(start['bank']['blurRows'])
                assert len(bankHeader)==0x48
                blurCount=struct.unpack_from('<I',bankHeader,0x38)[0]
                assert blurCount<=1024 and len(blurRows)==12*blurCount
                assert not common[0x29] or struct.unpack_from('<I',calc,0x2c)[0]!=0,'active motion requires explicit replay input'
                chunks=[params,common,calc,data[0x298],data[0xc0],bankHeader,
                    header,rows,blurRows,expected]
                arrays=[(ctypes.c_ubyte*len(c)).from_buffer_copy(c) for c in chunks]
                status=lib.replay(*arrays)
                assert status==0,(path.name,start['sample'],'selection/status',status)
                assert bytes(arrays[-1])==expected,(path.name,start['sample'],'long output differs',
                    bytes(arrays[-1])[0x40:0x50].hex(),expected[0x40:0x50].hex())
                count+=1
            assert count>0
            total+=count;print(f'PASS: {path.name}: {count} scoped scene/table/long matches')
    print(f'{total} exact phone matches; N is supplied by the phone, S/ES and Camera2 scheduler are not tested')

if __name__=='__main__':main()
