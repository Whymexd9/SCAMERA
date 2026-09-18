#!/usr/bin/env python3
"""Build the same scalar kernel used on ARM. Supply the FFTW source and static library."""
import argparse
import pathlib
import subprocess

p=argparse.ArgumentParser()
p.add_argument('--fftw-include',required=True)
p.add_argument('--fftw-library',required=True)
p.add_argument('--output',required=True)
p.add_argument('--sanitize',action='store_true')
a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1]/'app/src/main/cpp/rt-denoise'
sources=['engine.cc','auto-chroma.cc','portable.cc','color-subset.cc','curve-subset.cc','curve-api.cc',
         'vendor/FTblockDN.cc','vendor/cplx_wavelet_dec.cc','vendor/boxblur.cc','vendor/labimage.cc','vendor/flatcurves.cc']
flags=['-g','-O1','-fsanitize=address,undefined','-fno-omit-frame-pointer'] if a.sanitize else ['-O2']
subprocess.run(['g++','-std=c++17','-U__SSE2__','-U__SSE__','-fopenmp','-fPIC','-shared',*flags,
                '-I'+a.fftw_include,'-I'+str(root/'vendor'),*[str(root/s) for s in sources],
                a.fftw_library,'-Wl,--no-undefined','-o',a.output],check=True)
