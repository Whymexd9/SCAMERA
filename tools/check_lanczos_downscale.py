"""Compare production streaming Lanczos to an independent dense float64 reference."""
from pathlib import Path
import ctypes
import subprocess
import tempfile
import numpy as np

ROOT = Path(__file__).resolve().parents[1]

def matrix(n, m, a):
    x = np.arange(n)[None, :] - ((np.arange(m)[:, None] + .5) * n / m - .5)
    z = x * m / n
    w = np.sinc(z) * np.sinc(z / a) * (np.abs(z) < a)
    return w / w.sum(axis=1, keepdims=True)

def reference(rgb, w, h, a):
    v = rgb.astype(np.float64) / 255
    linear = np.where(v <= .04045, v / 12.92, ((v + .055) / 1.055) ** 2.4)
    wx, wy = matrix(rgb.shape[1], w, a), matrix(rgb.shape[0], h, a)
    out = np.stack([wy @ linear[:, :, c] @ wx.T for c in range(3)], axis=2).clip(0, 1)
    return np.rint(255 * np.where(out <= .0031308, 12.92*out, 1.055*out**(1/2.4)-.055)).astype(np.uint8)

with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    (root/'check.cpp').write_text('''#include "lanczos-downscale.h"
extern "C" int run(const unsigned char* s,int w,int h,unsigned char* d,int ow,int oh,int a){
try {scamera_lanczos::resize(s,w,h,w*4+8,d,ow,oh,ow*4+12,a);return 1;}catch(...){return 0;}}
''')
    subprocess.run(['g++','-std=c++14','-O3','-Wall','-Wextra','-shared','-fPIC',
                    '-I'+str(ROOT/'app/src/main/cpp'),str(root/'check.cpp'),'-o',str(root/'check.so')],check=True)
    lib = ctypes.CDLL(str(root/'check.so'))
    lib.run.argtypes=[ctypes.c_void_p,ctypes.c_int,ctypes.c_int,ctypes.c_void_p,ctypes.c_int,ctypes.c_int,ctypes.c_int]
    def resize(rgb, w, h, a):
        sh, sw, _ = rgb.shape
        source=np.full((sh,sw*4+8),211,np.uint8)
        source[:,:sw*4].reshape(sh,sw,4)[:,:,:3]=rgb
        source[:,:sw*4].reshape(sh,sw,4)[:,:,3]=255
        output=np.full((h,w*4+12),173,np.uint8)
        assert lib.run(source.ctypes.data,sw,sh,output.ctypes.data,w,h,a)==1
        assert np.all(output[:,w*4:]==173), 'row padding overwritten'
        rgba=output[:,:w*4].reshape(h,w,4)
        assert np.all(rgba[:,:,3]==255)
        return rgba[:,:,:3].copy()
    rng=np.random.default_rng(731)
    for a in range(2,6):
        for sw,sh,w,h in [(37,29,19,11),(44,32,11,8),(1,11,1,4),(19,1,7,1),(8,8,2,2)]:
            rgb=rng.integers(0,256,(sh,sw,3),dtype=np.uint8)
            actual=resize(rgb,w,h,a)
            expected=reference(rgb,w,h,a)
            error=np.abs(actual.astype(int)-expected.astype(int)).max()
            assert error<=1,(a,sw,sh,error)
        for level in (0,1,37,128,254,255):
            flat=np.full((31,47,3),level,np.uint8)
            assert np.all(resize(flat,19,13,a)==level),('DC/edge drift',a,level)
        identity=rng.integers(0,256,(11,13,3),dtype=np.uint8)
        assert np.array_equal(resize(identity,13,11,a),identity)
        checker=(np.indices((64,64)).sum(0)%2*255).astype(np.uint8)
        checker=np.repeat(checker[:,:,None],3,axis=2)
        reduced=resize(checker,16,16,a)[3:-3,3:-3]
        assert np.abs(reduced.astype(int)-188).max()<=1,('alias suppression',a)
    fixture=rng.integers(0,256,(45,61,3),dtype=np.uint8)
    outputs=[resize(fixture,27,19,a) for a in range(2,6)]
    assert all(not np.array_equal(outputs[i],outputs[j]) for i in range(4) for j in range(i)), 'kernels not distinct'
    b=np.zeros(1024,np.uint8)
    assert lib.run(b.ctypes.data,8,8,b.ctypes.data,2,2,6)==0
    assert lib.run(b.ctypes.data,8,8,b.ctypes.data,1,1,3)==0
    print('Lanczos 2–5: dense reference, DC, edges, stride, identity, alias suppression and validation passed')

# Guard stage placement: successful Vivo -> Lanczos -> gain map/encoding.
source=(ROOT/'app/src/main/java/com/particlesdevs/photoncamera/processing/processor/HdrxProcessor.java').read_text()
assert source.index('VivoRaisrProcessor.process(')<source.index('if (vivoSucceeded && downscaleKernel != 0)')<source.index('VivoPostDownscale.process(')<source.index('gm = pipeline.RunHDRGainMap(')
print('Pipeline order passed')
