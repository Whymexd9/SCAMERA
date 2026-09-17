"""Offline RAW response check. Run from repo root; requires tifffile, numpy, moderngl and compiled Java helper."""
import numpy as np,tifffile,subprocess,sys,argparse,tempfile
from pathlib import Path
sys.path.insert(0,'tools')
from check_remosaic_shaders import run
parser=argparse.ArgumentParser(description='Measure residual 4x4 response on a BGGR telephoto 4x ISZ DNG; no RGB ground-truth claim.')
parser.add_argument('dng');parser.add_argument('--java-classes',required=True);args=parser.parse_args()
workspace=tempfile.TemporaryDirectory();tmp=Path(workspace.name)
raw=tifffile.imread(args.dng)[:,:,None];h,w=raw.shape[:2]
u={'rawWidth':w,'rawHeight':h,'blockSize':4,'phase':(0,0),'blackLevel':64.,'whiteLevel':1023.,'tileSize':32}
grid=run('blockprofile',{'RawBuffer':raw},u,output_size=(((w+31)//32)*4,((h+31)//32)*4))
grid.astype('<f4').tofile(str(tmp/'grid.f32'))
subprocess.run(['java','-cp',args.java_classes,'TetraResponseMapCheck',str(tmp/'grid.f32'),str(grid.shape[1]),str(grid.shape[0]),str(tmp/'map.f32')],check=True)
gainmap=np.fromfile(str(tmp/'map.f32'),'<f4').reshape(28,36,4)
prep=run('tetra/prepare',{'RawBuffer':raw,'GainMap':gainmap},{'size':(w,h),'phase':(0,0),'quadColors':(2,1,1,0),'blackLevel':64.,'whiteLevel':1023.,'gainB':1.,'gainR':1.},half=True)
# Independently recompute per-site profiles from corrected sensor samples.
a=raw[:,:,0].astype(float)-64;th,tw=h//32,w//32
b=a[:th*32,:tw*32].reshape(th,4,8,tw,4,8).transpose(0,3,1,4,2,5).mean((2,3))
b2=prep[:th*32,:tw*32,0].reshape(th,4,8,tw,4,8).transpose(0,3,1,4,2,5).mean((2,3))
for q in range(4):
 zs=[bb[:,:,q//2*4:q//2*4+4,q%2*4:q%2*4+4].reshape(th,tw,16) for bb in [b,b2]]
 ratios=[z/np.maximum(z.mean(2)[:,:,None],1e-8) for z in zs]
 baseline=np.median(ratios[0],axis=(0,1));spreads=[]
 for rr in [ratios[0]/baseline,ratios[1]]:
  per=[]
  for yy in range(3):
   for xx in range(4):
    region=rr[yy*th//3:(yy+1)*th//3,xx*tw//4:(xx+1)*tw//4]
    per.append(np.ptp(np.median(region,axis=(0,1)))*100)
  spreads.append((round(float(np.median(per)),3),round(float(max(per)),3)))
 print('q',q,'regional residual spread global/map (median,max) %',spreads,flush=True)
