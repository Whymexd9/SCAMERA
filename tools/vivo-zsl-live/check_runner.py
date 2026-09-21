#!/usr/bin/env python3
"""Run the actual shell coordinator with a fake Android tree and injector."""
import os,subprocess,tempfile,hashlib,tarfile
from pathlib import Path
source=Path(__file__).with_name('run.sh').read_text()
for outcome in ['complete','partial','attach_error','missing_queue','duplicate_provider','complete_magisk','policy_failure','policy_missing','wrong_domain']:
    with tempfile.TemporaryDirectory() as td:
        root=Path(td);work=root/'work';work.mkdir();bin=root/'bin';bin.mkdir()
        proc=root/'proc'/'777';proc.mkdir(parents=True);(proc/'attr').mkdir()
        (root/'proc'/'uptime').write_text('100.0 100.0\n')
        (proc/'cmdline').write_bytes(b'/vendor/bin/hw/vendor.qti.camera.provider-service_64\0')
        (proc/'status').write_text('Uid:\t1000\nGid:\t1000\nGroups:\n')
        (proc/'attr'/'current').write_text('u:r:hal_camera3rd_default:s0' if outcome=='wrong_domain' else 'u:r:hal_camera_default:s0')
        (proc/'maps').write_text('/vendor/lib64/libvivo.vas.adapter.vcf.so\n' + ('' if outcome=='missing_queue' else '/vendor/lib64/libvcf_session.so\n'))
        decoy=root/'proc'/'12302';decoy.mkdir()
        (decoy/'cmdline').write_bytes(b'/vendor/bin/hw/vendor.vivo.hardware.camera3rd.provider@1.0-service\0')
        (decoy/'maps').write_text('/vendor/lib64/libvivo.vas.adapter.vcf.so\n')
        if outcome=='duplicate_provider':
            duplicate=root/'proc'/'778';duplicate.mkdir()
            (duplicate/'cmdline').write_bytes((proc/'cmdline').read_bytes())
        scratch=root/'scratch';scratch.mkdir();dest=root/'download'
        script=source.replace('/proc/',str(root/'proc')+'/').replace('/data/local/tmp/',str(scratch)+'/').replace('/sdcard/Download/SCAMERA',str(dest))
        (work/'run.sh').write_text(script);(work/'trace.js').write_text('//fixture')
        policy=Path(__file__).with_name('policy-fix.sh').read_text()
        for original,replacement in [('/proc/',str(root/'proc')+'/'),('/data/adb/',str(root/'adb')+'/'),('/debug_ramdisk/',str(root/'debug_ramdisk')+'/'),('/sbin/',str(root/'sbin')+'/')]:
            policy=policy.replace(original,replacement)
        (work/'policy-fix.sh').write_text(policy)
        crash=Path(__file__).with_name('collect-crash.sh').read_text().replace('/data/tombstones/',str(root/'tombstones')+'/')
        (work/'collect-crash.sh').write_text(crash)

        payload='''#!/bin/sh
printf started > injected

[ "$1" = -p ] && [ "$2" = 777 ] && [ "$3" = -s ] || exit 99
'''
        if outcome in ('complete','partial','complete_magisk'):payload+='''echo 'SCAMERA_ZSL {"event":"nice_ready"}'
echo 'SCAMERA_ZSL {"event":"nice_leave","status":0}'
echo 'SCAMERA_ZSL {"event":"finished"}'
sleep 3
'''
        else:payload+="echo 'fixture: permission denied' >&2\nexit 42\n"
        if outcome in ('complete','complete_magisk'):payload=payload.replace("sleep 3", "echo 'SCAMERA_ZSL {\"event\":\"ready\"}'\nsleep 3")
        (work/'frida-inject').write_text(payload)
        (work/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256((work/name).read_bytes()).hexdigest()+'  '+name+'\n' for name in ['frida-inject','trace.js','run.sh','policy-fix.sh','collect-crash.sh']))
        stubs={'logcat':'echo fixture-crash-log','df':'echo filesystem 4000000 1000 2000000 1% /', 'uname':'echo aarch64','id':'echo 0','getprop':'echo fixture','getenforce':'echo Enforcing','dmesg':'exit 0',
          'sha256sum':'''case "$1" in
 /sys/fs/selinux/policy) echo 'fixture-policy-hash /sys/fs/selinux/policy';;
 /vendor/lib64/libvcf_session.so) echo '93a3149a70fea5f177bb0542278c47ec232fc477aa96cb7a63534b1f6b5f8dad  libvcf_session.so';;
 /vendor/lib64/libvivo.vas.adapter.vcf.so) echo 'f5fdf379e0316fee77a0ff7760ef247881e4dfade98f3a7bc8b9b8f93c12e901  libvivo.vas.adapter.vcf.so';;
 *) exec /usr/bin/sha256sum "$@";; esac'''}
        if outcome!='policy_missing':
            tool='magiskpolicy' if outcome=='complete_magisk' else 'ksud'
            prefix='[ "$#" = 2 ] && [ "$1" = --live ] && [ "$2" = "allow hal_camera_default tmpfs file { read write open getattr map execute }" ]' if tool=='magiskpolicy' else '[ "$#" = 3 ] && [ "$1" = sepolicy ] && [ "$2" = patch ] && [ "$3" = "allow hal_camera_default tmpfs file { read write open getattr map execute }" ]'
            stubs[tool]=prefix+" || exit 99\nprintf applied > policy-called\nexit "+('17' if outcome=='policy_failure' else '0')
        for name,body in stubs.items():p=bin/name;p.write_text('#!/bin/sh\n'+body+'\n');p.chmod(0o700)
        env=dict(os.environ,PATH=str(bin)+':'+os.environ['PATH'])
        result=subprocess.run(['sh',str(work/'run.sh')],env=env,text=True,capture_output=True,timeout=15)
        if outcome in ('missing_queue','duplicate_provider'):
            assert result.returncode==1,result.stdout
            assert not dest.exists()
            assert not (scratch/'scamera-zsl-live.lock').exists()
            continue
        if outcome in ('policy_failure','policy_missing','wrong_domain'):
            assert result.returncode==1,(result.stdout,result.stderr)
            assert not (work/'injected').exists()
            assert 'ГОТОВО:' in result.stdout
            assert not (scratch/'scamera-zsl-live.lock').exists()
            archive=next(dest.glob('*.tar.gz'))
            with tarfile.open(archive) as tar:
                policy_log=tar.extractfile('./policy-fix.log').read().decode()
                assert 'policy_error' in policy_log or 'POLICY_COMMAND_EXIT=17' in policy_log,policy_log
            continue
        assert result.returncode==0,result.stderr
        assert (work/'policy-called').exists()
        assert (work/'injected').exists()

        if outcome=='partial':
            assert 'NICE ПОДКЛЮЧЁН' not in result.stdout,result.stdout
            assert 'ОЧЕРЕДЬ ZSL ПОДКЛЮЧЕНА' not in result.stdout
        assert 'ГОТОВО:' in result.stdout,result.stdout
        assert not (scratch/'scamera-zsl-live.lock').exists()
        archives=list(dest.glob('*.tar.gz'));assert len(archives)==1
        with tarfile.open(archives[0]) as tar:
            assert 'fixture-crash-log' in tar.extractfile('./logcat-crash.txt').read().decode()
            assert './modules-before.txt' in tar.getnames()
            assert './modules-after.txt' in tar.getnames()
            policy_log=tar.extractfile('./policy-fix.log').read().decode()
            assert 'POLICY_COMMAND_EXIT=0' in policy_log,policy_log
            log=tar.extractfile('./trace.log').read().decode()
            assert ('nice_leave' in log)==(outcome in ('complete','partial','complete_magisk'))
            if outcome=='attach_error':assert 'permission denied' in log
print('PASS: shell runner success and attachment failure, PID arguments, report archiving and lock cleanup; fake Android environment only.')

print('PASS: Qualcomm selection with camera3rd decoy; missing queue and duplicate providers rejected before injection.')

print('PASS: exact live rule and arguments for KernelSU/Magisk, policy failures archived without injection, wrong domain refused.')
