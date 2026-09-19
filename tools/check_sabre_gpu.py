#!/usr/bin/env python3
"""Execute the production Sabre shaders, not a CPU reimplementation (Mesa EGL)."""
from pathlib import Path
import moderngl
import numpy as np
ctx=moderngl.create_standalone_context(backend='egl',require=430)
root=Path(__file__).resolve().parents[1]/'app/src/main/assets/shaders/merge'
def program(name,sabre=True):
    s=(root/(name+'.glsl')).read_text().replace('#define LAYOUT //\nLAYOUT','#version 430\nlayout(local_size_x=8,local_size_y=8) in;')
    if sabre:s=s.replace('#define SABRE_RECONSTRUCTION 0','#define SABRE_RECONSTRUCTION 1')
    return ctx.compute_shader(s)
align=program('mergeAlign'); ordinary=program('mergeAlign',False); combine=program('sabreCombine')
w,h=35,27
owned=[]
def tex(a,dtype='f2'):
    a=np.asarray(a);t=ctx.texture((a.shape[1],a.shape[0]),a.shape[2],a.astype({'f2':'float16','u2':'uint16'}[dtype]).tobytes(),dtype=dtype)
    t.filter=(moderngl.NEAREST,moderngl.NEAREST);owned.append(t);return t

def run(base,donor,gain=1,flow=(0,0),sr=True):
    for t in owned:t.release()
    owned.clear()
    raw=tex(np.repeat(np.repeat(base,2,0),2,1)[:,:,:1]*65535,'u2')
    b=tex(base);d=tex(donor);out=tex(np.zeros_like(base));conf=tex(np.zeros_like(base))
    field=np.zeros((5,5,4),np.float32)
    field[:,:,0]=np.floor(flow[0])/w;field[:,:,1]=np.floor(flow[1])/h
    field[:,:,2]=flow[0]-np.floor(flow[0]);field[:,:,3]=flow[1]-np.floor(flow[1])
    f=tex(field)
    raw.use(0);f.use(1);d.use(2)
    b.bind_to_image(2,read=True,write=False);out.bind_to_image(3,read=False,write=True);d.bind_to_image(4,read=True,write=False);conf.bind_to_image(5,read=False,write=True)
    uniforms={'inTexture':0,'alignmentTexture':1,'alterSampler':2,'whitelevel':65535,'blackLevel':(0,0,0,0),'minLevel':0.0,'exposure':gain,'noiseS':.0001,'noiseO':.00001,'shift':(0,0),'alignmentSize':(5,5),'rawHalf':(w,h),'cfaShift':(0,0),'rawMfsr':int(sr),'mosaicPeriod':1,'sabreRejection':1,'sabreNoiseRef':(.0001,.00001),'sabreNoiseAlt':(.0001,.00001),'packedScale':1.0}
    for k,v in uniforms.items():
        if k in align:align[k].value=v
    align.run((w+7)//8,(h+7)//8);ctx.memory_barrier()
    result=np.frombuffer(out.read(),np.float16).astype(float).reshape(h,w,4)
    mask=np.frombuffer(conf.read(),np.float16).astype(float).reshape(h,w,4)
    return result,mask,b,out,conf

base=np.full((h,w,4),.2,np.float32)
for gain in [.25,.5,1,2,4]:
    out,mask,*_=run(base,base/gain,gain)
    assert np.max(np.abs(out-.2))<.001,(gain,out.min(),out.max())
    assert np.min(mask)>.99,(gain,mask.min())
# Distinct CFA channel levels must remain distinct, including dispatch edges.
colour=np.empty_like(base);colour[:]=[.1,.2,.22,.35]
for gain in [.5,1,2]:
    out,mask,*_=run(colour,colour/gain,gain)
    assert np.max(abs(out-colour))<.001
# Clipped donors carry zero mass, and cannot darken the accumulated reference.
out,mask,b,d,c=run(base,np.ones_like(base))
assert mask.max()==0
old=tex(np.zeros_like(base));new=tex(np.zeros_like(base));result=tex(np.zeros_like(base))
for index,t in enumerate([b,d,c,old,result,new]):t.bind_to_image(index,read=index<4,write=index>=4)
combine['first'].value=1;combine.run((w+7)//8,(h+7)//8);ctx.memory_barrier()
a=np.frombuffer(result.read(),np.float16)
assert np.max(abs(a-.2))<.001
# Confidence-weighted normalization: one valid donor at .4 with mass .25.
b=tex(base);d=tex(np.full_like(base,.4));c=tex(np.full_like(base,.25))
for index,t in enumerate([b,d,c,old,result,new]):t.bind_to_image(index,read=index<4,write=index>=4)
combine.run((w+7)//8,(h+7)//8);ctx.memory_barrier()
a=np.frombuffer(result.read(),np.float16)
assert np.max(abs(a-.24))<.001
# Motion in a flat field is rejected; noise-sized differences are retained.
out,mask,*_=run(base,np.full_like(base,.4))
assert mask.max()<.001,mask.max()
out,mask,*_=run(base,np.full_like(base,.201))
assert mask.min()>.9,mask.min()
# Subpixel RBF reconstruction compared with integer nearest sampling of a
# continuously shifted low-frequency scene (exclude boundary replication).
y,x=np.mgrid[:h,:w];signal=lambda x:.35+.12*np.sin(x*.22)
base=np.repeat(signal(x)[...,None],4,2).astype('float32')
donor=np.repeat(signal(x-.4)[...,None],4,2).astype('float32')
a,_,*_=run(base,donor,flow=(.4,0),sr=True)
b,_,*_=run(base,donor,flow=(.4,0),sr=False)
roi=np.s_[3:-3,3:-3,:]
ea=np.mean((a[roi]-base[roi])**2);eb=np.mean((b[roi]-base[roi])**2)
assert ea<eb,(ea,eb)
assert np.isfinite(a).all()
print('Sabre GPU PASS: both shader variants compile; 5 bracket gains, clipping, zero confidence, weighted mass, motion/noise, subpixel reconstruction; MSE',ea,eb)
