#!/usr/bin/env python3
"""Reproduce conflicting system/vendor SONAMEs with real host dlopen.
Not an Android/device inference test. Reads the application's actual search order.
"""
import os,re,subprocess,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[2]
source=(root/'app/src/main/java/com/particlesdevs/photoncamera/processing/ml/VivoRaisrProcessor.java').read_text()
order=re.search(r'LIBRARY_PATH\s*=\s*"([^"]+)"',source).group(1).split(':')
soft_order=re.search(r'SOFT_LIBRARY_PATH\s*=\s*"([^"]+)"',source).group(1).split(':')
with tempfile.TemporaryDirectory() as temp:
    d=Path(temp)
    paths={p:d/p.strip('/').replace('/','_') for p in set(order+soft_order)}
    for p in paths.values():p.mkdir()
    def compile_so(code,out,*args):
        f=d/(out.name+'.c');f.write_text(code)
        subprocess.run(['cc','-shared','-fPIC',str(f),*args,'-o',str(out)],check=True)
    system=paths['/system/lib64'];vendor=paths['/vendor/lib64']
    compile_so('int system_join(void){return 42;}',system/'libtestbase.so','-Wl,-soname,libtestbase.so')
    compile_so('int older_vendor_base(void){return 0;}',vendor/'libtestbase.so','-Wl,-soname,libtestbase.so')
    compile_so('extern int system_join(void); int graphics_init(void){return system_join();}',
               system/'libtestgraphics.so','-L'+str(system),'-ltestbase')
    f=d/'loader.c';f.write_text('''#include <dlfcn.h>
#include <stdio.h>
int main(int argc,char** argv){(void)argc;void* h=dlopen(argv[1],RTLD_NOW|RTLD_LOCAL);
if(!h){puts(dlerror());return 1;}int (*fn)(void)=dlsym(h,"graphics_init");
int ok=fn && fn()==42;dlclose(h);return ok?0:2;}
''')
    subprocess.run(['cc',str(f),'-ldl','-o',str(d/'loader')],check=True)
    def run(seq):
        return subprocess.run([str(d/'loader'),str(system/'libtestgraphics.so')],
          env={**os.environ,'LD_LIBRARY_PATH':':'.join(str(paths[p]) for p in seq)},capture_output=True,text=True)
    broken=run(['/vendor/lib64','/vendor/lib64/hw','/system/lib64','/system_ext/lib64'])
    assert broken.returncode==1 and 'system_join' in broken.stdout,broken
    for seq in (order,soft_order):
        fixed=run(seq)
        assert fixed.returncode==0,(fixed.stdout,fixed.stderr)
    print('PASS: vendor-first reproduces missing symbol; application system-first resolves it')
