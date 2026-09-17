"""Compile/link original ES 3.0 sources in a real Mesa GLES context, without GLSL translation."""
import ctypes as C
import os
from pathlib import Path

os.environ.setdefault('EGL_PLATFORM', 'surfaceless')
egl=C.CDLL('libEGL.so.1')
def api(name, result, *args):
    f=getattr(egl,name);f.restype=result;f.argtypes=args;return f
ptr=C.c_void_p;integer=C.c_int;uint=C.c_uint
get_display=api('eglGetDisplay',ptr,ptr)
initialize=api('eglInitialize',uint,ptr,C.POINTER(integer),C.POINTER(integer))
bind=api('eglBindAPI',uint,uint)
choose=api('eglChooseConfig',uint,ptr,C.POINTER(integer),C.POINTER(ptr),integer,C.POINTER(integer))
create=api('eglCreateContext',ptr,ptr,ptr,ptr,C.POINTER(integer))
current=api('eglMakeCurrent',uint,ptr,ptr,ptr,ptr)
get_proc=api('eglGetProcAddress',ptr,C.c_char_p)
destroy=api('eglDestroyContext',uint,ptr,ptr)
terminate=api('eglTerminate',uint,ptr)
display=get_display(None);major=integer();minor=integer()
assert initialize(display,C.byref(major),C.byref(minor)), 'EGL initialization'
assert bind(0x30A0), 'GLES API'
attributes=(integer*5)(0x3040,0x40,0x3033,1,0x3038)
config=ptr();count=integer()
assert choose(display,attributes,C.byref(config),1,C.byref(count)) and count.value, 'GLES3 config'
context=create(display,config,None,(integer*3)(0x3098,3,0x3038))
assert context and current(display,None,None,context), 'GLES3 context'
def gl(name,result,*args):
    address=get_proc(name.encode());assert address,name
    return C.CFUNCTYPE(result,*args)(address)
make_shader=gl('glCreateShader',uint,uint)
source=gl('glShaderSource',None,uint,integer,C.POINTER(C.c_char_p),ptr)
compile_shader=gl('glCompileShader',None,uint)
shader_status=gl('glGetShaderiv',None,uint,uint,C.POINTER(integer))
shader_log=gl('glGetShaderInfoLog',None,uint,integer,ptr,ptr)
delete_shader=gl('glDeleteShader',None,uint)
make_program=gl('glCreateProgram',uint)
attach=gl('glAttachShader',None,uint,uint)
link=gl('glLinkProgram',None,uint)
program_status=gl('glGetProgramiv',None,uint,uint,C.POINTER(integer))
program_log=gl('glGetProgramInfoLog',None,uint,integer,ptr,ptr)
delete_program=gl('glDeleteProgram',None,uint)
version=gl('glGetString',C.c_char_p,uint)(0x1F02)
def shader(text,kind):
    handle=make_shader(kind);encoded=C.c_char_p(text.encode())
    source(handle,1,C.byref(encoded),None);compile_shader(handle)
    ok=integer();shader_status(handle,0x8B81,C.byref(ok))
    if not ok.value:
        log=C.create_string_buffer(16384);shader_log(handle,len(log),None,log)
        raise AssertionError(log.value.decode())
    return handle
vertex=shader('#version 300 es\nprecision highp float;\nvoid main(){gl_Position=vec4(0.0,0.0,0.0,1.0);}',0x8B31)
files=sorted(Path('app/src/main/assets/shaders/remosaic/tetra').glob('*.glsl'))
try:
    for path in files:
        fragment=shader(path.read_text(),0x8B30)
        program=make_program();attach(program,vertex);attach(program,fragment);link(program)
        ok=integer();program_status(program,0x8B82,C.byref(ok))
        if not ok.value:
            log=C.create_string_buffer(16384);program_log(program,len(log),None,log)
            raise AssertionError((str(path),log.value.decode()))
        delete_program(program);delete_shader(fragment)
    print('PASS:',len(files),'unmodified GLES shader programs;',version.decode())
finally:
    delete_shader(vertex);current(display,None,None,None);destroy(display,context);terminate(display)
