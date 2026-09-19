#!/usr/bin/env python3
"""Exercise shipped autonomous HDR kernels on Mesa, with radiometric fixtures."""
from pathlib import Path
import moderngl
import numpy as np
ctx=moderngl.create_standalone_context(backend='egl',require=430)
root=Path(__file__).resolve().parents[1]/'app/src/main/assets/shaders'
def compute(name,es=False):
 s=(root/(name+'.glsl')).read_text().replace('#define LAYOUT //\nLAYOUT','#version '+('310 es' if es else '430')+'\nlayout(local_size_x=8,local_size_y=8) in;')
 s=s.replace('#define VIVO_HDR 0','#define VIVO_HDR 1').replace('#define SABRE_RECONSTRUCTION 0','#define SABRE_RECONSTRUCTION 1')
 return ctx.compute_shader(s)
for name in ['merge/mergeAlign','vivohdr/mask','vivohdr/combine','vivohdr/finalize']:compute(name,True).release()
align=compute('merge/mergeAlign');mask=compute('vivohdr/mask');combine=compute('vivohdr/combine');finish=compute('vivohdr/finalize')
w,h=35,27
owned=[]
def tex(a,dtype='f2'):
 a=np.asarray(a); t=ctx.texture((a.shape[1],a.shape[0]),a.shape[2],a.astype('float16' if dtype=='f2' else 'uint16').tobytes(),dtype=dtype)
 t.filter=(moderngl.NEAREST,moderngl.NEAREST);t.repeat_x=t.repeat_y=False;owned.append(t);return t

def uniforms(prog,values):
 for k,v in values.items():
  if k in prog:prog[k].value=v

def dispatch(prog):prog.run((w+7)//8,(h+7)//8);ctx.memory_barrier()
def read(t):return np.frombuffer(t.read(),np.float16).astype(np.float32).reshape(h,w,4)
def merge(ref,donors,scale=1.0,noise=(.0001,.00001),flow=(0,0)):
 for t in owned:t.release()
 owned.clear()
 r=tex(ref);a=r;state=tex(np.zeros_like(ref));outmask=None
 raw=tex(np.repeat(np.repeat(ref/scale,2,0),2,1)[:,:,:1]*65535,'u2')
 field=np.zeros((5,5,4),np.float32);field[:,:,:2]=[flow[0]/w,flow[1]/h];f=tex(field)
 for index,(values,gain) in enumerate(donors):
  d=tex(values);aligned=tex(np.zeros_like(ref));confidence=tex(np.zeros_like(ref));clean=tex(np.zeros_like(ref))
  raw.use(0);f.use(1);d.use(2)
  r.bind_to_image(2,read=True,write=False);aligned.bind_to_image(3,read=False,write=True);d.bind_to_image(4,read=True,write=False);confidence.bind_to_image(5,read=False,write=True)
  uniforms(align,{'inTexture':0,'alignmentTexture':1,'alterSampler':2,'whitelevel':65535,'blackLevel':(0,0,0,0),'minLevel':0.0,'exposure':gain,'noiseS':noise[0],'noiseO':noise[1],'shift':(0,0),'alignmentSize':(5,5),'rawHalf':(w,h),'cfaShift':(0,0),'rawMfsr':0,'mosaicPeriod':1,'sabreRejection':0,'sabreNoiseRef':noise,'sabreNoiseAlt':noise,'packedScale':scale})
  dispatch(align)
  confidence.bind_to_image(0,read=True,write=False);clean.bind_to_image(1,read=False,write=True);dispatch(mask)
  dst=tex(np.zeros_like(ref));newstate=tex(np.zeros_like(ref))
  for binding,t in enumerate([r,a,aligned,clean,state,dst,newstate]):t.bind_to_image(binding,read=binding<5,write=binding>=5)
  uniforms(combine,{'first':int(index==0),'referenceScale':scale,'donorScale':scale*gain,'noiseRef':noise,'noiseAlt':(noise[0]*gain,noise[1]*gain*gain)})
  dispatch(combine);a=dst;state=newstate;outmask=read(clean)
 final=tex(np.zeros_like(ref))
 for binding,t in enumerate([r,a,state,final]):t.bind_to_image(binding,read=binding<3,write=binding==3)
 uniforms(finish,{'referenceScale':scale,'whitePoint':(1,1,1,1)})
 dispatch(finish)
 return read(final),read(state),outmask

ref=np.empty((h,w,4),np.float32);ref[:]=[.06,.10,.12,.18]
for gain in [.25,.5,1,2,4,8]:
 result,state,conf=merge(ref,[(ref/gain,gain)])
 assert np.max(abs(result-ref))<.001,(gain,result.min(),result.max())
 assert np.isfinite(state).all()
# In shortest-exposure units, clipped normal reference=.25; short donor=.175
# before exposure conversion (gain=4) reconstructs radiance=.7, not .25 or .475.
r=np.full_like(ref,.25);d=np.full_like(ref,.175)
result,state,conf=merge(r,[(d,4)],scale=.25)
assert np.max(abs(result-.7))<.002,('highlight',result.min(),result.max(),conf.min())
# A clipped donor never darkens a valid reference.
result,_,_=merge(ref,[(np.ones_like(ref),1)])
assert np.max(abs(result-ref))<.001
# Local moving patch: expand rejection around its boundary; no ghost into reference.
d=ref.copy();d[8:20,10:25]+=.35
result,_,conf=merge(ref,[(d,1)])
assert np.max(abs(result-ref))<.001
assert conf[9:19,11:24].max()==0
# Zero contributors (both clipped), black, extreme ratio, and shifted image edges.
for value in [0,1]:
 result,_,_=merge(np.full_like(ref,value),[(np.full_like(ref,value),1)])
 assert np.max(abs(result-value))<.001
result,_,conf=merge(ref,[(ref,1)],flow=(-4,0))
assert conf[:,:4].max()==0
assert np.isfinite(result).all()
# Noise reduction must actually improve MSE; static donors are accumulated.
rng=np.random.default_rng(2718);truth=np.full_like(ref,.2)
nref=truth+rng.normal(0,.01,ref.shape).astype('float32')
donors=[(truth+rng.normal(0,.01,ref.shape).astype('float32'),1) for _ in range(5)]
result,state,conf=merge(nref,donors,noise=(0,.0001))
assert np.mean((result-truth)**2)<np.mean((nref-truth)**2)*.5
assert state[:,:,2].mean()>3
# NR zero bypass, constant colour, noise reduction and edge retention.
vs='#version 430\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}'
s=(root/'vivohdr/denoise.glsl').read_text()
ctx.program(vertex_shader=vs.replace('430','310 es'),fragment_shader='#version 310 es\n'+s).release()
nr=ctx.program(vertex_shader=vs,fragment_shader='#version 430\n'+s)
def denoise(a,luma,chroma,wp=(1,1,1),lsc=(1,1,1,1)):
 inp=tex(a);out=tex(np.zeros_like(a));fb=ctx.framebuffer([out]);fb.use();ctx.viewport=(0,0,w,h);inp.use(0);tex(np.array(lsc,np.float32).reshape(1,1,4)).use(1)
 uniforms(nr,{'InputBuffer':0,'GainMap':1,'whitePoint':wp,'noiseSlope':(0,0,0),'noiseOffset':(.0001,.0001,.0001),'lumaAmount':luma,'chromaAmount':chroma,'radiusStep':1})
 ctx.vertex_array(nr,[]).render(vertices=3);v=read(out);fb.release();return v
assert np.max(abs(denoise(nref,0,0)[:,:,:3]-nref[:,:,:3]))<.001
filtered=denoise(nref,1,1)
assert np.mean((filtered[:,:,:3]-.2)**2)<np.mean((nref[:,:,:3]-.2)**2)*.6
edge=truth.copy();edge[:,w//2:,:3]=.8
filtered=denoise(edge,1,1)
assert np.max(abs(filtered[:,:,:3]-edge[:,:,:3]))<.001
print('Autonomous HDR GPU PASS: ES compilation, 6 exposure ratios, full highlight replacement, clipping, motion, invalid borders, black, weighted burst noise reduction, NR bypass/colour/edges.')
# Production tone/colour shader including the real shader imports.
s=(root/'headroom/render.glsl').read_text().replace('#define NEUTRALPOINT 0.0,0.0,0.0','#define NEUTRALPOINT 1.0,1.0,1.0')
for imp in ['coords','interpolation']:s=s.replace('#import '+imp,(root/('utils/import_'+imp+'.glsl')).read_text())
ctx.program(vertex_shader=vs.replace('430','310 es'),fragment_shader='#version 310 es\n'+s).release()
tone=ctx.program(vertex_shader=vs,fragment_shader='#version 430\n'+s)
def render_tone(amount,shadows=0,local=0,raw_scale=1.0,display_gain=4.0):
 a=np.ones((h,w,4),np.float32);a[:,:,:3]=np.linspace(.001,.99,w)[None,:,None]
 a[:,:,:3]*=raw_scale
 inp=tex(a);gain=tex(np.ones((1,1,4)));out=tex(np.zeros_like(a));fb=ctx.framebuffer([out]);fb.use();ctx.viewport=(0,0,w,h)
 inp.use(0);gain.use(1)
 uniforms(tone,{'InputBuffer':0,'GainMap':1,'displayGain':display_gain/raw_scale,'sceneWhite':display_gain/raw_scale,'outputExposureScale':1.0,'toneAmount':amount,'localContrast':local,'shadowLift':shadows,'activeSize':(0,0,w-1,h-1)})
 for k in ['sensorToIntermediate','intermediateToSRGB']:tone[k].write(np.eye(3,dtype='float32').tobytes())
 ctx.vertex_array(tone,[]).render(vertices=3);v=read(out);fb.release();return v
mapped=render_tone(1);linear=render_tone(0)
assert np.isfinite(mapped).all() and mapped.min()>=0 and mapped.max()<=1
assert np.min(np.diff(mapped[h//2,:,0]))>=-1e-4
assert np.std(mapped[h//2,10:30,0])>.03 # retained highlight contrast
assert np.max(abs(mapped-linear))>.05
assert np.max(abs(render_tone(1,1)-mapped))>.01
assert np.max(abs(render_tone(1,0,1)-mapped))>.0001
print('HDR tone PASS: production GLES/desktop shaders, monotonic bounded output, highlight detail, independent tone/shadow/local controls.')

# Packing at the shortest exposure must not change reference midtones, even at
# 8 EV. The production Java histogram meters with 1/rawScale, then restores
# this scale in linearDisplayGain. Check the real renderer at that boundary.
normal=render_tone(1,display_gain=1)
for raw_scale in [0.5,0.25,0.0625,1/256]:
 compressed=render_tone(1,raw_scale=raw_scale,display_gain=1)
 assert np.max(abs(compressed[:,:w//2,:3]-normal[:,:w//2,:3]))<.002
print('HDR radiometry PASS: reference midtones invariant across 1, 2, 4 and 8 EV packing.')

# Regression: zero -> tiny confidence must not switch a clipped pixel from
# the reference to a fully bright donor. Exercise production combine+finalize.
def clipped_confidence(confidence,wp=(1,1,1,1),donor=.7):
 ref=np.full((h,w,4),.25,np.float32);z=np.zeros_like(ref)
 r=tex(ref);d=tex(np.full_like(ref,donor));a=tex(z);state=tex(z)
 for i,t in enumerate([r,r,d,tex(np.full_like(ref,confidence)),tex(z),a,state]):
  t.bind_to_image(i,read=i<5,write=i>=5)
 uniforms(combine,{'first':1,'referenceScale':.25,'donorScale':1.,'noiseRef':(.0001,.00001),'noiseAlt':(.0004,.00016)})
 dispatch(combine)
 out=tex(z)
 for i,t in enumerate([r,a,state,out]):t.bind_to_image(i,read=i<3,write=i==3)
 uniforms(finish,{'referenceScale':.25,'whitePoint':wp});dispatch(finish)
 return read(out)
zero=clipped_confidence(0);tiny=clipped_confidence(.0001);full=clipped_confidence(1)
assert np.max(abs(tiny-zero))<.001
assert np.max(abs(full-.7))<.002
values=[clipped_confidence(c)[h//2,w//2,0] for c in np.linspace(0,1,101)]
assert np.min(np.diff(values))>=-.001
assert np.max(np.diff(values))<.03
# No donor -> clipped fallback must be neutral after white balance; reliable
# coloured donor remains coloured (no desaturation of valid recovered detail).
wp=np.array([.37,1,1,.64],np.float32)
fallback=clipped_confidence(0,tuple(wp))/wp
assert np.max(np.ptp(fallback,axis=2))<.003
assert np.max(abs(clipped_confidence(1,tuple(wp))-.7))<.002
print('Highlight seam regression PASS: continuous 101-step confidence sweep, zero/tiny confidence, full replacement, neutral fallback.')
# WB + chromatic LSC must transform both signal and variance equally. With
# luma=chroma=1 the bilateral RGB result is invariant in sensor units.
wp=np.array([.37,1,.64],np.float32);lsc=np.array([1.6,1.,1.,.8],np.float32)
g=lsc[[0,1,3]];g=g/g.mean()/wp
scaled=nref.copy();scaled[:,:,:3]*=g
expected=denoise(nref,1,1)[:,:,:3]
actual=denoise(scaled,1,1,tuple(wp),tuple(lsc))[:,:,:3]/g
assert np.max(abs(actual-expected))<.001
print('Denoise variance regression PASS: red/green/blue WB and lens-shading gain covariance.')

# A hard tile-validity edge is feathered INWARD over packed CFA quads;
# the rejected side remains the reference and never receives donor pixels.
z=np.zeros((h,w,4),np.float32);r=tex(z+.25);a=tex(z+.7);mass=z.copy()
mass[:,:,0]=1;mass[:,w//2:,3]=1;out=tex(z)
for i,t in enumerate([r,a,tex(mass),out]):t.bind_to_image(i,read=i<3,write=i==3)
uniforms(finish,{'referenceScale':.25,'whitePoint':(1,1,1,1)});dispatch(finish)
seam=read(out)[h//2,:,0]
assert np.max(abs(seam[:w//2]-.25))<.001
assert np.max(np.diff(seam))<.16
assert abs(seam[-1]-.7)<.002
print('Tile boundary regression PASS: rejected side unchanged, inward-only feather, bounded adjacent-pixel jump.')
