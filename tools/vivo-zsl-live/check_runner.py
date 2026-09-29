#!/usr/bin/env python3
"""Run the actual shell coordinator with a fake Android tree and injector."""
import os,subprocess,tempfile,hashlib,tarfile
from pathlib import Path
source=Path(__file__).with_name('run.sh').read_text()
for outcome in ['complete','partial','attach_error','missing_queue','duplicate_provider','complete_magisk','policy_failure','policy_missing','wrong_domain','missing_nice','nice_attach_error','nice_policy_failure']:
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
        (decoy/'maps').write_text('/vendor/lib64/libvivo.vas.adapter.vcf.so\n' + ('' if outcome=='missing_nice' else '/vendor/lib64/libvivo.vaf.algo.nice.so\n'))
        (decoy/'attr').mkdir(); (decoy/'attr'/'current').write_text('u:r:hal_camera3rd_default:s0')
        if outcome=='duplicate_provider':
            duplicate=root/'proc'/'778';duplicate.mkdir()
            (duplicate/'cmdline').write_bytes((proc/'cmdline').read_bytes())
        scratch=root/'scratch';scratch.mkdir();dest=root/'download'
        script=source.replace('/proc/',str(root/'proc')+'/').replace('/data/local/tmp/',str(scratch)+'/').replace('/sdcard/Download/SCAMERA',str(dest))
        (work/'run.sh').write_text(script);(work/'trace.js').write_text('//fixture');(work/'ae-tuning.js').write_text('//fixture')
        policy=Path(__file__).with_name('policy-fix.sh').read_text()
        for original,replacement in [('/proc/',str(root/'proc')+'/'),('/data/adb/',str(root/'adb')+'/'),('/debug_ramdisk/',str(root/'debug_ramdisk')+'/'),('/sbin/',str(root/'sbin')+'/')]:
            policy=policy.replace(original,replacement)
        (work/'policy-fix.sh').write_text(policy)
        crash=Path(__file__).with_name('collect-crash.sh').read_text().replace('/data/tombstones/',str(root/'tombstones')+'/')
        (work/'collect-crash.sh').write_text(crash)

        payload='''#!/bin/sh
printf started > injected

[ "$1" = -p ] && [ "$3" = -s ] || exit 99
if [ "$2" = 12302 ]; then
  echo 'SCAMERA_ZSL {"event":"ready","pid":12302}'
  echo 'SCAMERA_ZSL {"event":"nice_input_leave","pid":12302}'
  sleep 3
  exit 0
fi
[ "$2" = 777 ] || exit 99
echo 'SCAMERA_ZSL {"event":"ae_tuning_ready"}'
'''
        if outcome=='nice_attach_error':payload=payload.replace("echo 'SCAMERA_ZSL {\"event\":\"ready\",\"pid\":12302}'", 'exit 42')
        if outcome in ('complete','partial','complete_magisk','nice_attach_error'):payload+='''echo 'SCAMERA_ZSL {"event":"nice_ready"}'
echo 'SCAMERA_ZSL {"event":"nice_leave","status":0}'
echo 'SCAMERA_ZSL {"event":"finished"}'
sleep 3
'''
        else:payload+="echo 'fixture: permission denied' >&2\nexit 42\n"
        if outcome in ('complete','complete_magisk','nice_attach_error'):payload=payload.replace("sleep 3", "echo 'SCAMERA_ZSL {\"event\":\"ready\"}'\nsleep 3")
        if outcome in ('complete','complete_magisk'):
            payload=payload.replace("echo 'SCAMERA_ZSL {\"event\":\"finished\"}'", "sleep 2\necho 'SCAMERA_ZSL {\"event\":\"finished\"}'")
        (work/'frida-inject').write_text(payload)
        (work/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256((work/name).read_bytes()).hexdigest()+'  '+name+'\n' for name in ['frida-inject','trace.js','run.sh','policy-fix.sh','collect-crash.sh']))
        stubs={'logcat':'echo fixture-crash-log','df':'echo filesystem 4000000 1000 2000000 1% /', 'uname':'echo aarch64','id':'echo 0','getprop':'echo fixture','getenforce':'echo Enforcing','dmesg':'exit 0',
          'sha256sum':'''case "$1" in
 /vendor/lib64/libvivo.vaf.algo.nice.so) echo '965c448c63274d031f974c3f24de062efe69ca5f90bb5060ef5e495bf05c1738  libvivo.vaf.algo.nice.so';;
 /vendor/lib64/camera/components/com.vivo.stats.aec.so) echo 'b2e3e12469a05cf62c2a5892b2b619228fb29a3ccb8fa9fe5f934df0dd5842e9  com.vivo.stats.aec.so';;
 /sys/fs/selinux/policy) echo 'fixture-policy-hash /sys/fs/selinux/policy';;
 /vendor/lib64/libvcf_platform_utils.so) echo '5f3bf712b8a8c4b092b7e5bd2ccdc4dfdfced7cc301730d1cc3fcbe8fe1a2f9d  libvcf_platform_utils.so';;
 /vendor/lib64/libvcf_session.so) echo '93a3149a70fea5f177bb0542278c47ec232fc477aa96cb7a63534b1f6b5f8dad  libvcf_session.so';;
 /vendor/lib64/libvivo.vas.adapter.vcf.so) echo 'f5fdf379e0316fee77a0ff7760ef247881e4dfade98f3a7bc8b9b8f93c12e901  libvivo.vas.adapter.vcf.so';;
 *) exec /usr/bin/sha256sum "$@";; esac'''}
        if outcome!='policy_missing':
            tool='magiskpolicy' if outcome=='complete_magisk' else 'ksud'
            prefix='[ "$#" = 2 ] && [ "$1" = --live ] && [ "$2" = "allow hal_camera_default tmpfs file { read write open getattr map execute }" ]' if tool=='magiskpolicy' else '[ "$#" = 3 ] && [ "$1" = sepolicy ] && [ "$2" = patch ] && [ "$3" = "allow hal_camera_default tmpfs file { read write open getattr map execute }" ]'
            prefix=prefix.replace('[ \"$2\" = \"allow hal_camera_default tmpfs file { read write open getattr map execute }\" ]', '{ [ \"$2\" = \"allow hal_camera_default tmpfs file { read write open getattr map execute }\" ] || [ \"$2\" = \"allow hal_camera3rd_default tmpfs file { read write open getattr map execute }\" ]; }')
            prefix=prefix.replace('[ \"$3\" = \"allow hal_camera_default tmpfs file { read write open getattr map execute }\" ]', '{ [ \"$3\" = \"allow hal_camera_default tmpfs file { read write open getattr map execute }\" ] || [ \"$3\" = \"allow hal_camera3rd_default tmpfs file { read write open getattr map execute }\" ]; }')
            stubs[tool]=prefix+" || exit 99\nprintf applied > policy-called\nexit "+('17' if outcome=='policy_failure' else '0')
        if outcome=='nice_policy_failure':stubs[tool]='case \"$*\" in *hal_camera3rd_default*) exit 17;; esac\n'+stubs[tool]
        for name,body in stubs.items():p=bin/name;p.write_text('#!/bin/sh\n'+body+'\n');p.chmod(0o700)
        env=dict(os.environ,PATH=str(bin)+':'+os.environ['PATH'])
        result=subprocess.run(['sh',str(work/'run.sh')],env=env,text=True,capture_output=True,timeout=15)
        if outcome in ('missing_queue','duplicate_provider','wrong_domain','missing_nice'):
            assert result.returncode==1,result.stdout
            assert not dest.exists()
            assert not (scratch/'scamera-zsl-live.lock').exists()
            continue
        if outcome in ('policy_failure','policy_missing','nice_policy_failure'):
            assert result.returncode==1,(result.stdout,result.stderr)
            assert not (work/'injected').exists()
            assert 'ГОТОВО:' in result.stdout
            assert not (scratch/'scamera-zsl-live.lock').exists()
            archive=next(dest.glob('*.tar.gz'))
            with tarfile.open(archive) as tar:
                policy_log=tar.extractfile('./policy-nice.log' if outcome=='nice_policy_failure' else './policy-fix.log').read().decode()
                assert 'policy_error' in policy_log or 'POLICY_COMMAND_EXIT=17' in policy_log,policy_log
            continue
        assert result.returncode==0,result.stderr
        assert (work/'policy-called').exists()
        assert (work/'injected').exists()

        if outcome=='partial':
            assert 'NICE ПОДКЛЮЧЁН' not in result.stdout,result.stdout
            assert 'ВСЕ ПЕРЕХВАТЫ ПОДКЛЮЧЕНЫ' not in result.stdout
        if outcome in ('complete','complete_magisk'):assert 'NICE ПОДКЛЮЧЁН' in result.stdout,result.stdout
        if outcome=='nice_attach_error':assert 'NICE ПОДКЛЮЧЁН' not in result.stdout,result.stdout
        assert 'ГОТОВО:' in result.stdout,result.stdout
        assert not (scratch/'scamera-zsl-live.lock').exists()
        archives=list(dest.glob('*.tar.gz'));assert len(archives)==1
        with tarfile.open(archives[0]) as tar:
            assert 'fixture-crash-log' in tar.extractfile('./logcat-crash.txt').read().decode()
            assert './modules-before.txt' in tar.getnames()
            assert './modules-after.txt' in tar.getnames()
            policy_log=tar.extractfile('./policy-nice.log' if outcome=='nice_policy_failure' else './policy-fix.log').read().decode()
            assert 'POLICY_COMMAND_EXIT=0' in policy_log,policy_log
            log=tar.extractfile('./trace.log').read().decode()
            assert log == tar.extractfile('./capture.log').read().decode() + tar.extractfile('./nice-inputs.log').read().decode()
            if outcome in ('complete','complete_magisk'):assert '\"pid\":12302' in log
            assert ('nice_leave' in log)==(outcome in ('complete','partial','complete_magisk','nice_attach_error'))
            if outcome=='attach_error':assert 'permission denied' in log
print('PASS: shell runner success and attachment failure, PID arguments, report archiving and lock cleanup; fake Android environment only.')

print('PASS: Paired Qualcomm/camera3rd selection; missing NICE, wrong domain and duplicate Qualcomm rejected before injection.')

print('PASS: two exact domain rules and arguments for KernelSU/Magisk, policy failures archived without injection, wrong domain refused.')
