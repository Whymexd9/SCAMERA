#!/usr/bin/env python3
"""Run the actual shell coordinator with a fake Android tree and injector."""
import os,subprocess,tempfile,hashlib,tarfile
from pathlib import Path
source=Path(__file__).with_name('run.sh').read_text()
for outcome in ['complete','attach_error']:
    with tempfile.TemporaryDirectory() as td:
        root=Path(td);work=root/'work';work.mkdir();bin=root/'bin';bin.mkdir()
        proc=root/'proc'/'777';proc.mkdir(parents=True);(proc/'attr').mkdir()
        (root/'proc'/'uptime').write_text('100.0 100.0\n')
        (proc/'cmdline').write_bytes(b'/vendor/bin/hw/vendor.vivo.hardware.camera3rd.provider@1.0-service\0')
        (proc/'status').write_text('Uid:\t1000\nGid:\t1000\nGroups:\n')
        (proc/'attr'/'current').write_text('u:r:hal_camera3rd_default:s0')
        scratch=root/'scratch';scratch.mkdir();dest=root/'download'
        script=source.replace('/proc/',str(root/'proc')+'/').replace('/data/local/tmp/',str(scratch)+'/').replace('/sdcard/Download/SCAMERA',str(dest))
        (work/'run.sh').write_text(script);(work/'trace.js').write_text('//fixture')
        payload='''#!/bin/sh
[ "$1" = -p ] && [ "$2" = 777 ] && [ "$3" = -s ] || exit 99
'''
        if outcome=='complete':payload+='''echo 'SCAMERA_ZSL {"event":"ready"}'
echo 'SCAMERA_ZSL {"event":"nice_leave","status":0}'
echo 'SCAMERA_ZSL {"event":"finished"}'
sleep 3
'''
        else:payload+="echo 'fixture: permission denied' >&2\nexit 42\n"
        (work/'frida-inject').write_text(payload)
        (work/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256((work/name).read_bytes()).hexdigest()+'  '+name+'\n' for name in ['frida-inject','trace.js','run.sh']))
        stubs={'df':'echo filesystem 4000000 1000 2000000 1% /', 'uname':'echo aarch64','id':'echo 0','getprop':'echo fixture','getenforce':'echo Enforcing','dmesg':'exit 0',
          'sha256sum':'''case "$1" in
 /vendor/lib64/libvcf_session.so) echo '93a3149a70fea5f177bb0542278c47ec232fc477aa96cb7a63534b1f6b5f8dad  libvcf_session.so';;
 /vendor/lib64/libvivo.vas.adapter.vcf.so) echo 'f5fdf379e0316fee77a0ff7760ef247881e4dfade98f3a7bc8b9b8f93c12e901  libvivo.vas.adapter.vcf.so';;
 *) exec /usr/bin/sha256sum "$@";; esac'''}
        for name,body in stubs.items():p=bin/name;p.write_text('#!/bin/sh\n'+body+'\n');p.chmod(0o700)
        env=dict(os.environ,PATH=str(bin)+':'+os.environ['PATH'])
        result=subprocess.run(['sh',str(work/'run.sh')],env=env,text=True,capture_output=True,timeout=15)
        assert result.returncode==0,result.stderr
        assert 'ГОТОВО:' in result.stdout,result.stdout
        assert not (scratch/'scamera-zsl-live.lock').exists()
        archives=list(dest.glob('*.tar.gz'));assert len(archives)==1
        with tarfile.open(archives[0]) as tar:
            log=tar.extractfile('./trace.log').read().decode()
            assert ('nice_leave' in log)==(outcome=='complete')
            if outcome=='attach_error':assert 'permission denied' in log
print('PASS: shell runner success and attachment failure, PID arguments, report archiving and lock cleanup; fake Android environment only.')
