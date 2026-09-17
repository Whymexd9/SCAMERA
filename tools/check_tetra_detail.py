"""Headless Tetra Detail regressions. Run from repo root; needs moderngl/numpy."""
import numpy as np
from check_remosaic_shaders import run

def tetra(raw,quad=(2,1,1,0),phase=(0,0),block_gain=None,gains=(1.,1.),half=True):
 h,w=raw.shape[:2]
 u={'size':(w,h),'phase':phase,'quadColors':quad,'blackLevel':64.,'whiteLevel':1023.,'gainB':gains[0],'gainR':gains[1],'blockGain':tuple(np.ones(64) if block_gain is None else block_gain)}
 # Match the first-frame GPU moment reduction used by the Java dispatcher.
 y,x=np.mgrid[:h,:w];c=np.array(quad)[((y+phase[1])%8)//4*2+((x+phase[0])%8)//4]
 signal=np.clip((raw[:,:,0].astype(float)-64)/959,0,1)
 mean=signal[c==1].mean();vg=max(np.mean(signal[c==1]**2)-mean**2,0)
 trust=[]
 for color,gain in [(2,gains[0]),(0,gains[1])]:
  vc=max(np.mean((signal[c==color]*gain)**2)-mean**2,0)
  ratio=np.sqrt(vc/max(vg,1e-12));trust.append(max(0,1-abs(np.log(max(ratio,1e-6)))/np.log(1.5)) if vg>1e-6 else 0)
 u['detailTrust']=tuple(trust)
 coarse=run('tetra/coarse',{'RawBuffer':raw},u,output_size=((w+phase[0]+7)//8,(h+phase[1]+7)//8),half=half)
 coarse=run('tetra/chroma',{'InputBuffer':coarse},u,half=half)
 guide=run('tetra/guide',{'RawBuffer':raw,'CoarseBuffer':coarse},u,half=half)
 return run('tetra/reconstruct',{'GuideBuffer':guide},u,integer=True)[:,:,0]

def original(raw,quad=(2,1,1,0),phase=(0,0),block_gain=None,gains=(1.,1.),half=True):
 h,w=raw.shape[:2]
 u={'rawWidth':w,'rawHeight':h,'blockSize':4,'phase':phase,'quadColors':quad,'blackLevel':64.,'whiteLevel':1023.,'gainB':gains[0],'gainR':gains[1],'blockGain':tuple(np.ones(64) if block_gain is None else block_gain)}
 masked=run('stages',{'RawBuffer':raw},dict(u,stage=0),half=half)
 green=run('greensteer',{'InputBuffer':masked},{'size':(w,h),'reach':8,'steer':8.},half=half)
 diffs=[]
 for stage in (1,2):
  masked=run('stages',{'RawBuffer':raw,'GreenBuffer':green},dict(u,stage=stage),half=half)
  bu={'size':(w,h),'kernelSize':9,'axis':0,'divide':0}
  tmp=run('maskblur',{'InputBuffer':masked},bu,half=half)
  interp=run('maskblur',{'InputBuffer':tmp},dict(bu,axis=1,divide=1),half=half)
  diffs.append(run('clampdiff',{'InterpBuffer':interp,'MaskedBuffer':masked},{'size':(w,h),'reach':8},half=half))
 return run('assemble',{'RawBuffer':raw,'GreenBuffer':green,'DiffBBuffer':diffs[0],'DiffRBuffer':diffs[1]},u,integer=True)[:,:,0]

def mosaic(rgb,quad=(2,1,1,0),phase=(0,0)):
 h,w=rgb.shape[:2];y,x=np.mgrid[:h,:w]
 c=np.array(quad)[((y+phase[1])%8)//4*2+((x+phase[0])%8)//4];target=np.array(quad)[y%2*2+x%2]
 raw=np.rint(64+959*np.take_along_axis(rgb,c[:,:,None],axis=2)).astype('u2')
 truth=np.rint(64+959*np.take_along_axis(rgb,target[:,:,None],axis=2))[:,:,0]
 return raw,truth

def checks():
 h,w=128,160;y,x=np.mgrid[:h,:w]
 for value in (0.,1.):
  raw,truth=mosaic(np.full((h,w,3),value));out=tetra(raw)
  assert np.max(abs(out-truth))<=1
 # The GPU moment reduction is also compiled/executed, and checked against
 # the raw data rather than the reconstruction equations.
 rgb=np.repeat((.2+.001*x+.002*y)[:,:,None],3,2);raw,_=mosaic(rgb)
 u={'size':(w,h),'phase':(0,0),'quadColors':(2,1,1,0),'blackLevel':64.,'whiteLevel':1023.,'gainB':1.,'gainR':1.}
 moments=run('tetra/moments',{'RawBuffer':raw},u,output_size=((w+31)//32,(h+31)//32))
 c=np.array([2,1,1,0])[y%8//4*2+x%8//4];a=(raw[:,:,0].astype(float)-64)/959
 expected=np.array([np.sum(a[c==1]**2),np.sum(a[c==2]**2),np.sum(a[c==0]**2),np.sum(c==1)])
 assert np.allclose(moments.sum((0,1)),expected,rtol=2e-6)

 for quad in [(2,1,1,0),(0,1,1,2),(1,0,2,1),(1,2,0,1)]:
  rgb=np.empty((h,w,3));rgb[:]=(.25,.4,.6)
  for phase in [(0,0),(1,3),(7,7),(4,2)]:
   raw,truth=mosaic(rgb,quad,phase);out=tetra(raw,quad,phase)
   assert np.max(abs(out-truth))<=2,(quad,phase,np.max(abs(out-truth)))
 for phase in [(px,py) for py in range(8) for px in range(8)]:
  rgb=np.repeat((.1+.003*x+.002*y)[:,:,None],3,2)
  raw,truth=mosaic(rgb,phase=phase);out=tetra(raw,phase=phase)
  assert np.max(abs(out[40:-40,40:-40]-truth[40:-40,40:-40]))<=2
  assert out.min()>=64 and out.max()<=1023
 scenes={'gray texture':np.repeat((.4+.12*np.sin(.7*x)+.08*np.sin(.4*y))[:,:,None],3,2),'gray diagonal':np.repeat(np.where(x+y<143,.18,.7)[:,:,None],3,2),'color edge':np.where((x<77)[:,:,None],np.array([.7,.22,.15]),np.array([.15,.4,.65])),'colored texture':np.stack([.3+.09*np.sin(.4*x),.4+.07*np.sin(.4*x),.55+.04*np.sin(.4*x)],axis=2)}
 for name,rgb in scenes.items():
  raw,truth=mosaic(rgb);yy,xx=np.mgrid[:h,:w];cc=np.array([2,1,1,0])[yy%8//4*2+xx%8//4];a=raw[:,:,0].astype(float)-64;means=[a[cc==k].mean() for k in range(3)];gains=(means[1]/means[2],means[1]/means[0]);old=original(raw,gains=gains);new=tetra(raw,gains=gains);sl=np.s_[40:-40,40:-40]
  errors=[np.sqrt(np.mean((o[sl]-truth[sl])**2)) for o in [old,new]]
  print(name,'old/new RAW RMSE:',*[round(e,3) for e in errors])
  assert errors[1]<errors[0],(name,errors)
  if name=='gray texture':assert errors[1]<errors[0]*.65
 print('PASS: colour orders, all Tetra phases, borders, half-float output, detail recovery')
if __name__=='__main__':checks()
