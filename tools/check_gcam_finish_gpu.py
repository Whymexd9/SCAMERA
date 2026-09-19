#!/usr/bin/env python3
"""Execute production finish and Cyclops shaders against independent fixtures."""
import moderngl
import numpy as np
from pathlib import Path
ctx=moderngl.create_standalone_context(backend='egl',require=430)
root=Path(__file__).resolve().parents[1]/'app/src/main/assets/shaders'
# Compile ES 3.10 source as well as the executable desktop test shader.
for name in ['merge/cyclopsMask','gcamfinish/finish','gcamfinish/blur']:
    s=(root/(name+'.glsl')).read_text()
    if 'LAYOUT' in s:
        s=s.replace('#define LAYOUT //\nLAYOUT','#version 310 es\nlayout(local_size_x=8,local_size_y=8) in;')
        ctx.compute_shader(s)
vs='''#version 430
void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}'''
finish=ctx.program(vertex_shader=vs,fragment_shader='#version 430\n'+(root/'gcamfinish/finish.glsl').read_text())
blur=ctx.program(vertex_shader=vs,fragment_shader='#version 430\n'+(root/'gcamfinish/blur.glsl').read_text())
for name in ['gcamfinish/finish','gcamfinish/blur']:
    ctx.program(vertex_shader=vs.replace('430','310 es'),fragment_shader='#version 310 es\n'+(root/(name+'.glsl')).read_text())
w,h=35,27
owned=[]
def tex(a):
    a=np.asarray(a,dtype=np.float16);t=ctx.texture((a.shape[1],a.shape[0]),a.shape[2],a.tobytes(),dtype='f2')
    t.repeat_x=t.repeat_y=False;t.filter=(moderngl.LINEAR,moderngl.LINEAR);owned.append(t);return t

def render(base,updates={},level_values=None):
    for t in owned:t.release()
    owned.clear()
    inp=tex(base)
    levels=[];previous=inp
    for i in range(3):
        shape=(max(1,previous.height//2),max(1,previous.width//2),4)
        t=tex(np.zeros(shape))
        if level_values is None:
            fbo=ctx.framebuffer([t]);fbo.use();ctx.viewport=(0,0,t.width,t.height)
            previous.use(0);blur['InputBuffer'].value=0;blur['outputSize'].value=(t.width,t.height)
            ctx.vertex_array(blur,[]).render(vertices=3);fbo.release()
        else:t.write(np.full(shape,level_values[i],np.float16).tobytes())
        levels.append(t);previous=t
    out=tex(np.zeros_like(base));fbo=ctx.framebuffer([out]);fbo.use();ctx.viewport=(0,0,w,h)
    settings={'frequencyGain':(1,1,1),'noiseFloor':.002,'shadowMatch':0,'logMix':1,'splitGain':(1,1),'highlightAmount':0,'localAmount':0,'clarityAmount':0,'dehazeAmount':0,'flareLevel':0,'atmosphere':1}
    settings.update(updates)
    for k,v in settings.items():finish[k].value=v
    for unit,(name,t) in enumerate(zip(['InputBuffer','Level1','Level2','Level3'],[inp,*levels])):
        t.use(unit);finish[name].value=unit
    ctx.vertex_array(finish,[]).render(vertices=3);ctx.finish()
    result=np.frombuffer(out.read(),np.float16).astype(float).reshape(h,w,4)
    fbo.release();return result
rng=np.random.default_rng(12)
base=rng.uniform(.01,4,(h,w,4)).astype('float32');base[:,:,3]=1
out=render(base)
assert np.max(abs(out-base))<.008, np.max(abs(out-base))
assert out[:,:,:3].max()>1
black=np.zeros_like(base);black[:,:,3]=1
assert render(black,{'shadowMatch':1,'splitGain':(1.5,2)})[:,:,:3].max()==0
constant=np.full_like(base,.1);constant[:,:,3]=1
out=render(constant,{'shadowMatch':1,'splitGain':(1.25,1.6)})
z=(.1-.04)/(.4-.04);amount=1-z*z*(3-2*z)
expected=.1**(1-amount)*(.1*1.25/1.025*1.6)**amount
assert np.max(abs(out[:,:,:3]-expected))<.001
hdr=np.empty_like(base);hdr[:]=[2,3,4,1]
compressed=render(hdr,{'highlightAmount':1})
assert 0<compressed[0,0,0]<2
assert abs(compressed[0,0,2]/compressed[0,0,0]-2)<.005
flat=np.full_like(base,.2);flat[:,:,3]=1
assert np.max(abs(render(flat,{'frequencyGain':(2,2,2)})-flat))<.001
for settings in [{'flareLevel':.02},{'dehazeAmount':.5},{'localAmount':.5},{'clarityAmount':.5}]:
    changed=render(flat,settings,[.3,.25,.1])
    assert np.isfinite(changed).all() and np.max(abs(changed-flat))>.005,settings
# Each frequency control must independently affect its corresponding band.
for i in range(3):
    gains=[1,1,1];gains[i]=1.5
    changed=render(flat,{'frequencyGain':tuple(gains)},[.18,.16,.14])
    assert np.max(abs(changed-flat))>.0005,i
# Cyclops cleanup: independent box/Gaussian/byte arithmetic including borders.
s=(root/'merge/cyclopsMask.glsl').read_text().replace('#define LAYOUT //\nLAYOUT','#version 430\nlayout(local_size_x=8,local_size_y=8) in;')
shader=ctx.compute_shader(s)
def shift(a,dy,dx):return np.pad(a,((4,4),(4,4)),mode='edge')[4+dy:4+dy+h,4+dx:4+dx+w]
for mask in [np.ones((h,w)),np.zeros((h,w)),np.pad(np.ones((h-8,w-8)),4),rng.uniform(0,1,(h,w))]:
    original=tex(np.repeat(mask[:,:,None],4,2));a=tex(np.zeros((h,w,4)));b=tex(np.zeros((h,w,4)))
    source=original
    for stage in range(6):
        dest=a if stage%2==0 else b
        source.bind_to_image(0,read=True,write=False);original.bind_to_image(1,read=True,write=False);dest.bind_to_image(2,read=False,write=True)
        shader['stage'].value=stage;shader.run((w+7)//8,(h+7)//8);ctx.memory_barrier();source=dest
    actual=np.frombuffer(source.read(),np.float16).reshape(h,w,4)[:,:,0]
    m=(mask.astype('float16')>.5).astype('float32')
    for _ in range(3):m=(sum(shift(m,y,x) for y in [-1,0,1] for x in [-1,0,1])/9).astype('float16').astype('float32')
    m=1-(m>=1).astype(float)
    weights=np.exp(-.5*(np.arange(6)-2)**2);weights/=weights.sum()
    m=sum(shift(m,0,i-3)*weights[i] for i in range(6)).astype('float16').astype(float)
    m=sum(shift(m,i-3,0)*weights[i] for i in range(6))
    expected=np.maximum(np.floor(mask.astype('float16').astype(float)*255)-np.floor(np.clip(m,0,1)*255),0)/255
    assert np.max(abs(actual-expected))<2/255,(actual.min(),actual.max(),np.max(abs(actual-expected)))
print('Finish GPU PASS: neutral identity, black, HDR, log blend, all 3 bands, local/dehaze/flare controls; Cyclops 4 mask fixtures incl borders and uint8 rounding; ES shaders compile')
