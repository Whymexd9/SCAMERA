#!/usr/bin/env python3
"""Exercise the shipped SCAM RGB import shader on Mesa (the merge / denoise / tone kernels it used to check are gone)."""
from pathlib import Path
import moderngl
import numpy as np
ctx=moderngl.create_standalone_context(backend='egl',require=430)
root=Path(__file__).resolve().parents[1]/'app/src/main/assets/shaders'
# Original neural RGB must skip a second demosaic, retain highlights and apply
# camera WB / normalized LSC exactly once. Exercise the shipped import shader.
src=(root/'scamhdr/scamrgb.glsl').read_text()
vertex='#version 430\nin vec2 position; void main(){gl_Position=vec4(position,0,1);}'
program=ctx.program(vertex_shader=vertex,fragment_shader='#version 430\n'+src)
quad=ctx.buffer(np.array([-1,-1,1,-1,-1,1,1,1],np.float32).tobytes())
vao=ctx.simple_vertex_array(program,quad,'position')
raw=ctx.texture((4,4),3,np.tile(np.array([2,.5,1.2],np.float32),(4,4,1)).tobytes(),dtype='f4')
gain=ctx.texture((1,1),4,np.array([1,1,1,1],np.float32).tobytes(),dtype='f4')
out=ctx.texture((4,4),3,dtype='f4');fb=ctx.framebuffer([out]);fb.use()
raw.use(0);gain.use(1)
for k,v in {'InputBuffer':0,'GainMap':1,'whitePoint':(1,.5,1),'clipLevel':(1e30,1e30,1e30),'inverseSize':(.25,.25),'cropOffset':(0,0)}.items():program[k].value=v
vao.render(moderngl.TRIANGLE_STRIP)
a=np.frombuffer(fb.read(components=3,dtype='f4'),np.float32).reshape(4,4,3)
assert np.max(np.abs(a-np.array([2,1,1.2])))<1e-5
for resource in [fb,out,gain,raw,vao,quad,program]:resource.release()
print('SCAM RGB import PASS: HDR range and WB/LSC once')
