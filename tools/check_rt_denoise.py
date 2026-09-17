#!/usr/bin/env python3
"""Behavioral checks: bypass, filter controls, edge dimensions, curves, invalid-input rollback."""
import ctypes as C
import sys
import numpy as np

lib=C.CDLL(sys.argv[1])
P=C.POINTER(C.c_float)
lib.rt_denoise.argtypes=[P,C.c_int,C.c_int,P,C.c_int,P,P,C.c_char_p,C.c_size_t]
lib.rt_curve.argtypes=[C.POINTER(C.c_double),C.c_int,P,C.c_char_p,C.c_size_t]
rng=np.random.default_rng(12512)
image=np.ones((257,321,4),np.float32)
image[:,:,:3]=.25+rng.normal(0,.014,(257,321,3))
image[40:140,90:210,:3]+=.25
defaults=np.array([35,20,20,0,0,1.7,0,0,0,0,1,0,1,0],np.float32)
def run(p,src=image,lc=None,cc=None,success=True):
    a=src.copy();err=C.create_string_buffer(512)
    ok=lib.rt_denoise(a.ctypes.data_as(P),a.shape[1],a.shape[0],p.ctypes.data_as(P),len(p),
        None if lc is None else lc.ctypes.data_as(P),None if cc is None else cc.ctypes.data_as(P),err,len(err))
    assert bool(ok)==success,err.value
    if success:
        assert np.isfinite(a).all()
        assert np.array_equal(a[:,:,3],src[:,:,3]),'Alpha changed'
    else:assert np.array_equal(a,src,equal_nan=True),'Failure changed caller image'
    return a
zero=defaults.copy();zero[:2]=0
assert np.array_equal(run(zero),image),'Disabled pipeline is not identity'
baseline=run(defaults)
assert baseline[180:240,20:90,:3].std()<image[180:240,20:90,:3].std()*.9,'Noise not reduced'
for idx,value in [(0,80),(1,80),(2,90),(3,40),(4,40),(5,2.7),(6,1),(7,1),(8,3),(11,1)]:
    p=defaults.copy();p[idx]=value
    assert np.max(abs(run(p)-baseline))>1e-6,('Disconnected parameter',idx)
for kernel in range(6):
    p=defaults.copy();p[8]=3;p[9]=kernel
    out=run(p)
    p[10]=3
    assert np.max(abs(run(p)-out))>1e-6,('Median passes',kernel)
for channels in range(1,6):
    p=defaults.copy();p[8]=channels;run(p)
# Noisy curves in upstream control-point format, sampled by upstream FlatCurve.
points=np.array([1,.05,.15,.35,.35,.55,.04,.35,.35],np.float64)
curve=np.zeros(501,np.float32);err=C.create_string_buffer(512)
assert lib.rt_curve(points.ctypes.data_as(C.POINTER(C.c_double)),len(points),curve.ctypes.data_as(P),err,len(err))==1,err.value
assert np.isfinite(curve).all() and np.ptp(curve)>.05
assert np.max(abs(run(defaults,lc=curve)-baseline))>1e-6
assert np.max(abs(run(defaults,cc=curve)-baseline))>1e-6
for value in [float('nan'),float('inf'),-1,101]:
    p=defaults.copy();p[0]=value;run(p,success=False)
p=defaults.copy();p[6]=.5;run(p,success=False)
p=defaults.copy();p[13]=2;p[12]=1;gain=run(p);p[12]=0
assert np.max(abs(run(p)-gain))>1e-6,'Auto gain disconnected'
# Width crosses the native tile boundary, with an odd height.
large=np.ones((129,1101,4),np.float32);large[:,:,:3]=.3+rng.normal(0,.01,(129,1101,3))
run(defaults,large)
print('PASS: identity, finite output, alpha, 14 controls, six median kernels, native curves, invalid-input rollback, odd/tiled sizes')
