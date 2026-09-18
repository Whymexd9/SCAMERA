#!/usr/bin/env python3
"""Exercise real GLES compute (Mesa on CI), with deterministic mock NPU output."""
import array
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
with tempfile.TemporaryDirectory(prefix='hex-gpu-') as temp:
    cpu=Path(temp,'cpu');gpu=Path(temp,'gpu');cpu.mkdir();gpu.mkdir()
    faults=[Path(temp,'gl-error'),Path(temp,'precision-error')]
    for p in faults:p.mkdir()
    env=dict(os.environ,EGL_PLATFORM='surfaceless',LIBGL_ALWAYS_SOFTWARE='1')
    result=subprocess.run([sys.argv[1],str(cpu),str(gpu),*(str(p) for p in faults)],env=env,text=True,capture_output=True)
    if result.returncode:
        print(result.stdout[-8000:]);print(result.stderr);raise SystemExit(result.returncode)
    reports=re.findall(r'HEX COMPUTE: requested=HYBRID CPU \+ GPU \+ NPU gpu_tiles=(\d+)',result.stdout)
    if len(reports)!=26 or any(int(n)!=3 for n in reports[:24]) or reports[24:]!=['0','0']:
        print(result.stdout);raise AssertionError('GPU did not process all three tiles following the CPU check')
    assert 'GL error=' in result.stdout and 'precision check failed' in result.stdout
    for p in faults:
        for f in p.iterdir():assert f.read_bytes()==(cpu/f.name).read_bytes(),f
    worst=0
    for p in cpu.glob('*.raw'):
        a=array.array('H');a.frombytes(p.read_bytes());b=array.array('H');b.frombytes((gpu/p.name).read_bytes())
        assert len(a)==len(b)
        error=max(abs(x-y) for x,y in zip(a,b));worst=max(worst,error)
        assert error<=16,(p.name,error)
        assert p.with_suffix('.input').read_bytes()==(gpu/p.with_suffix('.input').name).read_bytes()
    print('GPU compute PASS: 24 captures, all CFA, x1/x2/full, noise/detail controls, borders/overlaps, GL-error and precision-error fallback; max Bayer16 error=',worst)
