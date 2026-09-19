"""Render production filters on Mesa GLES, without a device or translated GLSL."""
from pathlib import Path
import ctypes as C
import os, re
import numpy as np
os.environ.setdefault('EGL_PLATFORM','surfaceless')
E=C.CDLL('libEGL.so.1'); P=C.c_void_p; I=C.c_int; U=C.c_uint

def egl(n,r,*a):
    f=getattr(E,n);f.restype=r;f.argtypes=a;return f
getproc=egl('eglGetProcAddress',P,C.c_char_p)
def gl(n,r,*a):return C.CFUNCTYPE(r,*a)(getproc(n.encode()))
d=egl('eglGetDisplay',P,P)(None)
assert egl('eglInitialize',U,P,P,P)(d,None,None)
assert egl('eglBindAPI',U,U)(0x30A0)
c=P();count=I()
assert egl('eglChooseConfig',U,P,P,P,I,P)(d,(I*5)(0x3040,0x40,0x3033,1,0x3038),C.byref(c),1,C.byref(count))
ctx=egl('eglCreateContext',P,P,P,P,P)(d,c,None,(I*3)(0x3098,3,0x3038))
assert egl('eglMakeCurrent',U,P,P,P,P)(d,None,None,ctx)
def shader(k,text):
    s=gl('glCreateShader',U,U)(k);b=C.c_char_p(text.encode());gl('glShaderSource',None,U,I,P,P)(s,1,C.byref(b),None);gl('glCompileShader',None,U)(s)
    ok=I();gl('glGetShaderiv',None,U,U,P)(s,0x8B81,C.byref(ok))
    if not ok.value:
        log=C.create_string_buffer(8192);gl('glGetShaderInfoLog',None,U,I,P,P)(s,8192,None,log);raise AssertionError(log.value)
    return s
vs=shader(0x8B31,'#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}')
def program(name,defs={}):
    source=(Path('app/src/main/assets/shaders/FalseColor')/(name+'.glsl')).read_text()
    for k,v in defs.items():source=re.sub(r'#define '+k+r' [^\n]*','#define '+k+' '+str(v),source)
    p=gl('glCreateProgram',U)();gl('glAttachShader',None,U,U)(p,vs);fs=shader(0x8B30,'#version 300 es\n'+source);gl('glAttachShader',None,U,U)(p,fs);gl('glLinkProgram',None,U)(p)
    ok=I();gl('glGetProgramiv',None,U,U,P)(p,0x8B82,C.byref(ok));assert ok.value
    gl('glDeleteShader',None,U)(fs);return p
fbo=U();gl('glGenFramebuffers',None,I,P)(1,C.byref(fbo));gl('glBindFramebuffer',None,U,U)(0x8D40,fbo)
vao=U();gl('glGenVertexArrays',None,I,P)(1,C.byref(vao));gl('glBindVertexArray',None,U)(vao)
def texture(data):
    t=U();gl('glGenTextures',None,I,P)(1,C.byref(t));gl('glBindTexture',None,U,U)(0xDE1,t)
    for k,v in [(0x2801,0x2601),(0x2800,0x2601),(0x2802,0x812F),(0x2803,0x812F)]:gl('glTexParameteri',None,U,U,I)(0xDE1,k,v)
    gl('glTexImage2D',None,U,I,I,I,I,I,U,U,P)(0xDE1,0,0x881A,data.shape[1],data.shape[0],0,0x1908,0x1406,data.ctypes.data)
    return t.value
loc=lambda p,n:gl('glGetUniformLocation',I,U,C.c_char_p)(p,n.encode())
def uniform(p,n,value):gl('glUniform1i',None,I,I)(loc(p,n),value)
def bind(p,name,t,slot):
    gl('glActiveTexture',None,U)(0x84C0+slot);gl('glBindTexture',None,U,U)(0xDE1,t);uniform(p,name,slot)
def draw(p,source,mask,w,h,**uniforms):
    output=texture(np.zeros((h,w,4),np.float32));gl('glUseProgram',None,U)(p);bind(p,'InputBuffer',source,0);bind(p,'SaliencyMask',mask,1)
    for k,v in uniforms.items():uniform(p,k,v)
    gl('glFramebufferTexture2D',None,U,U,U,U,I)(0x8D40,0x8CE0,0xDE1,output,0)
    assert gl('glCheckFramebufferStatus',U,U)(0x8D40)==0x8CD5
    gl('glViewport',None,I,I,I,I)(0,0,w,h);gl('glDrawArrays',None,U,I,I)(4,0,3)
    data=np.empty((h,w,4),np.float32);gl('glReadPixels',None,I,I,I,I,U,U,P)(0,0,w,h,0x1908,0x1406,data.ctypes.data)
    assert gl('glGetError',U)()==0
    gl('glDeleteTextures',None,I,P)(1,C.byref(U(output)));return data
mask=texture(np.ones((1,1,4),np.float32))
data=np.full((24,32,4),.4,np.float32);data[12,16,:3]=[.8,.28,.8];data[5,5,:3]=2
source=texture(data)
def render(amount,protect=False):
    p=program('falsecolor',{'SIZE':'32,24','STRENGTH':float(amount),'HAS_SALIENCY':int(protect)})
    a=draw(p,source,mask,32,24,maskRotation=0);gl('glDeleteProgram',None,U)(p);return a
no=render(0);yes=render(1);protected=render(1,True)
assert np.max(abs(no[:,:,:3]-data[:,:,:3]))<.002
assert yes[5,5,:3].min()>1.99
chroma=lambda a:abs(a[12,16,0]-a[12,16,1])+abs(a[12,16,2]-a[12,16,1])
assert chroma(yes)<chroma(no)*.5
assert chroma(protected)>chroma(yes)+.05
assert abs((yes[12,16,:3]-no[12,16,:3])@[.2126,.7152,.0722])<.001
# Flat saturated HDR regions preserve their colour.
data[:,:,:3]=[1.6,.1,.9];source=texture(data);yes=render(1)
assert np.max(abs(yes[:,:,:3]-data[:,:,:3]))<.002
program('falsecolor',{'CA_RED':'1.0','CA_BLUE':'-1.0','HAS_SALIENCY':1})
# Input and inverse-mask mapping: position a marker, check all rotations.
data=np.zeros((24,32,4),np.float32);data[4:8,8:12,:]=1;source=texture(data)
p=program('saliency_input')
for angle in [0,90,180,270]:
    a=draw(p,source,mask,512,384,rotation=angle)
    yy,xx=np.unravel_index(a[:,:,0].argmax(),a[:,:,0].shape);u=(xx+.5)/512;v=(yy+.5)/384
    if angle==90:u,v=v,1-u
    elif angle==180:u,v=1-u,1-v
    elif angle==270:u,v=1-v,u
    assert 8/32<=u<=12/32 and 4/24<=v<=8/24,(angle,u,v)
print('GLES PASS: median chroma suppression; luminance/HDR/zero identity; saliency response; four rotations; CA compilation')
