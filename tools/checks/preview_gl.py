from pathlib import Path
import ctypes as C,json,os
# Mesa surfaceless EGL, not Android and not a phone performance measurement.
E=C.CDLL('libEGL.so.1');ptr=C.c_void_p;I=C.c_int;U=C.c_uint
F=C.c_float
getproc=E.eglGetProcAddress;getproc.restype=ptr;getproc.argtypes=[C.c_char_p]
def egl(n,r,a):
 f=getattr(E,n);f.restype=r;f.argtypes=a;return f
def gl(n,r,a):return C.CFUNCTYPE(r,*a)(getproc(n.encode()))
d=C.CFUNCTYPE(ptr,U,ptr,ptr)(getproc(b'eglGetPlatformDisplayEXT'))(0x31DD,None,None)
assert egl('eglInitialize',U,[ptr,ptr,ptr])(d,None,None)
assert egl('eglBindAPI',U,[U])(0x30A0)
c=ptr();count=I();attrs=(I*13)(0x3033,1,0x3040,0x40,0x3024,8,0x3023,8,0x3022,8,0x3021,8,0x3038)
assert egl('eglChooseConfig',U,[ptr,ptr,ptr,I,ptr])(d,attrs,C.byref(c),1,C.byref(count))
ctx=egl('eglCreateContext',ptr,[ptr,ptr,ptr,ptr])(d,c,None,(I*3)(0x3098,3,0x3038))
surf=egl('eglCreatePbufferSurface',ptr,[ptr,ptr,ptr])(d,c,(I*5)(0x3057,8,0x3056,8,0x3038))
assert egl('eglMakeCurrent',U,[ptr,ptr,ptr,ptr])(d,surf,surf,ctx)
base=Path(__file__).resolve().parents[2]/'app/src/main/assets/shaders/preview'
vs='#version 300 es\nlayout(location=0) in vec2 p;layout(location=1) in vec2 uv;out vec2 texCoord;void main(){gl_Position=vec4(p,0,1);texCoord=uv;}'
fs=(base/'rawdevelop_fs.glsl').read_text()
def shader(k,t):
 s=gl('glCreateShader',U,[U])(k);b=C.c_char_p(t.encode());gl('glShaderSource',None,[U,I,ptr,ptr])(s,1,C.byref(b),None);gl('glCompileShader',None,[U])(s);ok=I();gl('glGetShaderiv',None,[U,U,ptr])(s,0x8B81,C.byref(ok))
 if not ok.value:
  b=C.create_string_buffer(8192);gl('glGetShaderInfoLog',None,[U,I,ptr,ptr])(s,8192,None,b);raise AssertionError(b.value)
 return s
def program(f):
 p=gl('glCreateProgram',U,[])()
 for k,t in [(0x8B31,vs),(0x8B30,f)]:gl('glAttachShader',None,[U,U])(p,shader(k,t))
 gl('glLinkProgram',None,[U])(p);ok=I();gl('glGetProgramiv',None,[U,U,ptr])(p,0x8B82,C.byref(ok));assert ok.value;return p
orig=program(fs)
diag=program(fs[:fs.index('void main()')]+'''void main(){int x=int(texCoord.x*float(width)); int y=int(texCoord.y*float(height)); FragColor=vec4(vec3(getUnpackedPixel(x,y)/u_white_level),1.); }''')
cfa=program(fs[:fs.index('void main()')]+'''void main(){int x=int(texCoord.x*float(width)); int y=int(texCoord.y*float(height)); vec3 c=cfa_mode==1?getStandardBayerColor(x,y):getFastColor(x/cfa_block_size,y/cfa_block_size); FragColor=vec4(c,1.); }''')
loc=lambda p,n:gl('glGetUniformLocation',I,[U,C.c_char_p])(p,n.encode())
def ui(p,n,v):gl('glUniform1i',None,[I,I])(loc(p,n),v)
def uf(p,n,v):gl('glUniform1f',None,[I,F])(loc(p,n),v)
def tex(unit,w,h,internal,fmt,typ,b):
 gl('glActiveTexture',None,[U])(0x84C0+unit);t=U();gl('glGenTextures',None,[I,ptr])(1,C.byref(t));gl('glBindTexture',None,[U,U])(0xDE1,t)
 for n,v in [(0x2801,0x2600),(0x2800,0x2600),(0x2802,0x812F),(0x2803,0x812F)]:gl('glTexParameteri',None,[U,U,I])(0xDE1,n,v)
 gl('glPixelStorei',None,[U,I])(0xCF5,1);gl('glTexImage2D',None,[U,I,I,I,I,I,U,U,ptr])(0xDE1,0,internal,w,h,0,fmt,typ,b)
def upload(rows,fmt):
 w=len(rows[0]);h=len(rows);flat=[v for r in rows for v in r]
 tex(1,w,h,0x8234,0x8D94,0x1403,(C.c_ushort*len(flat))(*flat))
 b=[]
 for r in rows:
  if fmt==38:
   for j in range(0,w,2):a,c=r[j:j+2];b.extend([a>>4,c>>4,(a&15)|((c&15)<<4)])
  else:
   for j in range(0,w,4):p=r[j:j+4];b.extend([v>>2 for v in p]);b.append(sum((v&3)<<(2*i) for i,v in enumerate(p)))
 tex(0,len(b)//h,h,0x8229,0x1903,0x1401,(C.c_ubyte*len(b))(*[v&255 for v in b]))
 return len(b)//h
verts=(F*8)(-1,-1,1,-1,-1,1,1,1);uv=(F*8)(0,0,1,0,0,1,1,1)
for i,b in [(0,verts),(1,uv)]:gl('glEnableVertexAttribArray',None,[U])(i);gl('glVertexAttribPointer',None,[U,I,U,U,I,ptr])(i,2,0x1406,0,8,b)
def setup(p,fmt,white,stride,mode=1,pattern=0):
 gl('glUseProgram',None,[U])(p)
 for n,v in {'width':8,'height':8,'stride':stride,'crop_width':8,'crop_height':8,'u_raw_format':fmt,'cfa_mode':mode,'cfa_block_size':1 if mode==1 else 2,'bayer_pattern':pattern,'sTexture':0,'sTexture16':1,'u_lsc_map':2,'u_lut_tex':3,'u_tonemap_op':1}.items():ui(p,n,v)
 for n,v in {'u_white_level':white,'r_gain':1,'g_gain':1,'b_gain':1,'u_auto_exposure':1,'u_macro_contrast':1,'u_shadow_vibrance':1,'u_highlight_vibrance':1,'u_gamma':2.2,'u_reinhard_pre_gain':1,'u_reinhard_w':3,'u_reinhard_post_gain':1,'u_aces_pre_gain':1.35,'u_aces_toe':.01,'u_aces_a_coeff':2.7,'u_aces_d_coeff':.59,'u_lottes_pre_gain':.7,'u_lottes_a':1.6,'u_lottes_d':.977,'u_uchi_pre_gain':2,'u_uchi_post_gain':.9,'u_uchi_p':1,'u_uchi_a':1,'u_uchi_m':.22,'u_uchi_l':.4,'u_uchi_c':1.33,'u_sig_contrast':1.5,'u_sig_mid_gray':.25}.items():uf(p,n,v)
 gl('glUniformMatrix3fv',None,[I,I,U,ptr])(loc(p,'color_transform'),1,0,(F*9)(1,0,0,0,1,0,0,0,1))
 gl('glUniform3f',None,[I,F,F,F])(loc(p,'peaking_color'),1,0,1)
def render():
 gl('glViewport',None,[I,I,I,I])(0,0,8,8);gl('glDrawArrays',None,[U,I,I])(5,0,4);b=(C.c_ubyte*256)();gl('glReadPixels',None,[I,I,I,I,U,U,ptr])(0,0,8,8,0x1908,0x1401,b);assert gl('glGetError',U,[])()==0;return bytes(b)
results=[]
# Link the app's actual vertex shader too, not only the diagnostic one.
saved_vs=vs
vs=(base/'rawdevelop_vs.glsl').read_text()
program(fs)
vs=saved_vs
white=1023
for block in [1,2,4]:
 for pat,m in enumerate([[0,1,1,2],[1,0,2,1],[1,2,0,1],[2,1,1,0]]):
  rows=[[int([.2,.4,.6][m[(y//block%2)*2+x//block%2]]*white)for x in range(8)]for y in range(8)]
  stride=upload(rows,32);setup(cfa,32,white,stride,1 if block==1 else 0,pat);ui(cfa,'cfa_block_size',block)
  # Diagnostic CFA main also needs coordinates in macro-pixel units.
  b=render()
  for i in range(0,256,4):
   rgb=list(b[i:i+3]);assert max(abs(a-v)for a,v in zip(rgb,[51,102,153]))<=1,(block,pat,i,rgb)
  results.append({'case':f'CFA block={block} pattern={pat} including borders','pass':True})
rows=[[64 if x<4 else 900 for x in range(8)]for y in range(8)]
stride=upload(rows,32);setup(orig,32,1023,stride);ui(orig,'peaking_enabled',0);a=render();ui(orig,'peaking_enabled',1);b=render()
changed=sum(x!=y for x,y in zip(a,b));assert changed>0, 'peaking lost the RAW edge'
results.append({'case':'corrected RAW peaking','changed_bytes':changed})
# Actual library-derived defaults, including the shadow path, must draw non-black.
setup(orig,32,1023,stride);ui(orig,'peaking_enabled',0);ui(orig,'u_tonemap_op',0);ui(orig,'u_ef_enabled',1)
gl('glUniform4f',None,[I,F,F,F,F])(loc(orig,'u_exp_mults'),1,1,1,2)
gl('glUniform4f',None,[I,F,F,F,F])(loc(orig,'u_layer_weights'),.7,.8,1,.5)
uf(orig,'u_weight_center',.5);uf(orig,'u_blend_smoothness',.15);uf(orig,'u_macro_contrast',1.1)
b=render();levels=sorted(set(b[0::4]));assert len(levels)>1 and max(levels)>200
results.append({'case':'adaptive pipeline ACES-like','red_levels':levels})
print(json.dumps(results,indent=2));print('PASS',len(results),'GPU scenarios; actual app shaders compile/link')
