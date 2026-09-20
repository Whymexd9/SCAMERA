#!/usr/bin/env python3
"""Exercise collector property restoration and file selection with mock Android IO.

Runs the actual shell script under bash, not a reimplementation of its logic.
Does not test Android property permissions or the stock camera on a phone.
"""
import json
import os
import signal
import subprocess
import tarfile
import tempfile
import time
from pathlib import Path

SCRIPT = Path(__file__).with_name('collect_vivo_tce_json.sh')
HASH = '9f5deac3bc68fc86fcf16b98f43c232a9642bc309c7d5d42c88d6a4b596b892d'
MOCK = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
name=Path(sys.argv[0]).name
state=Path(os.environ['TCE_TEST_STATE'])
data=json.loads(state.read_text())
if name=='id': print(0)
elif name=='sha256sum': print(data['hash']+'  '+sys.argv[1])
elif name=='getprop': print(data.get(sys.argv[1],''))
elif name=='setprop':
    key,value=sys.argv[1:3]
    if data.get('fail_enable') and value=='-1': sys.exit(1)
    data[key]=value
    if value=='-1': data['enable_calls']=data.get('enable_calls',0)+1
    state.write_text(json.dumps(data))
elif name=='logcat': print('mock TCE log')
else: raise RuntimeError(name)
'''
KEY = 'vendor.vivo.vaf.dump.nice.portraitseg'


def run_case(root, name, initial='', *, scenario='normal'):
    directory = root/name
    directory.mkdir()
    bins, output, camera = [directory/n for n in ('bin', 'output', 'camera')]
    bins.mkdir()
    camera.mkdir()
    state = directory/'state.json'
    data = {'hash': HASH, KEY: initial}
    if scenario == 'hash':
        data['hash'] = 'wrong'
    if scenario == 'level':
        data['vendor.vivo.vaf.dump.nicetce'] = '4'
    if scenario == 'failure':
        data['fail_enable'] = True
    state.write_text(json.dumps(data))
    for name in ('id', 'sha256sum', 'getprop', 'setprop', 'logcat'):
        p = bins/name
        p.write_text(MOCK)
        p.chmod(0o755)
    # An old valid stock JSON must never be confused with this capture.
    fixture = {'ToneInfo': {'createToneMode': 1}, 'RawHDRInputAEParam': {}}
    (camera/'old.json').write_text(json.dumps(fixture))
    os.utime(camera/'old.json', (1, 1))
    env = dict(os.environ, PATH=str(bins)+os.pathsep+os.environ['PATH'], TCE_TEST_STATE=str(state))
    process = subprocess.Popen(['bash', str(SCRIPT), str(output), str(camera)],
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, text=True, env=env)
    if scenario in ('normal', 'signal', 'empty', 'eof'):
        deadline = time.monotonic()+5
        while True:
            # Read may coincide with the mock's truncation/rewrite.
            try:
                enabled = json.loads(state.read_text()).get('enable_calls', 0) == 1
            except json.JSONDecodeError:
                enabled = False
            if enabled:
                break
            if process.poll() is not None or time.monotonic() > deadline:
                raise AssertionError('Collector did not enable property')
            time.sleep(.01)
        if scenario == 'signal':
            process.send_signal(signal.SIGTERM)
            out, err = process.communicate(timeout=5)
            assert process.returncode == 130, (out, err)
        else:
            if scenario == 'normal':
                (camera/'new.json').write_text(json.dumps(fixture))
                (camera/'other.json').write_text('{"unrelated":true}')
                (camera/'image.bin').write_bytes(b'image data must not be collected')
            out, err = process.communicate('' if scenario == 'eof' else '\n', timeout=10)
            assert process.returncode == (0 if scenario == 'normal' else 2), (out, err)
            archives = list(output.glob('*.tar.gz'))
            assert len(archives) == 1
            with tarfile.open(archives[0]) as archive:
                members = [m for m in archive.getmembers() if '/json/' in m.name and m.isfile()]
                assert len(members) == (1 if scenario == 'normal' else 0)
                if members:
                    assert json.load(archive.extractfile(members[0])) == fixture
                assert not any(m.name.endswith('.bin') for m in archive.getmembers())
    else:
        out, err = process.communicate('', timeout=5)
        assert process.returncode != 0, (out, err)
    assert json.loads(state.read_text()).get(KEY) == initial, (scenario, state.read_text())
    assert not (output/'.tce-json-collector-lock').exists()


def main():
    with tempfile.TemporaryDirectory(prefix='tce-collector-') as directory:
        root = Path(directory)
        for initial in ('', '0', '1', '-1'):
            run_case(root, 'normal-'+(initial or 'empty'), initial)
        for scenario in ('signal', 'empty', 'eof', 'hash', 'level', 'failure'):
            run_case(root, scenario, scenario=scenario)
    print('PASS: 10 collector cases; exact property restoration, failure/signal/EOF, donor guards, new JSON only')


if __name__ == '__main__':
    main()
