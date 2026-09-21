#!/usr/bin/env python3
import argparse
import gzip
import hashlib
import runpy
import tempfile
from pathlib import Path
from urllib.request import urlopen
import zipfile

SHA = '4952c58fa7f3caca7816e5e8a2b8be9c2b88b95a28110296504007b661c2fc4f'
URL = 'https://github.com/frida/frida/releases/download/17.18.0/frida-inject-17.18.0-android-arm64.xz'
ASSET = 'assets/vivo-aec/frida-inject.gz'


def verify(data):
    binary = gzip.decompress(data)
    if hashlib.sha256(binary).hexdigest() != SHA:
        raise ValueError('AE runtime SHA-256 mismatch')


def fetch_runtime():
    with urlopen(URL, timeout=120) as response:
        archive = response.read()
    with tempfile.TemporaryDirectory(prefix='scamera-ae-') as temporary:
        root = Path(temporary)
        source, output = root / 'official.xz', root / 'frida-inject'
        source.write_bytes(archive)
        # Preserve the audited removal of Frida's automatic global policy rewrite.
        prepare = runpy.run_path(str(Path(__file__).parent / 'vivo-tce-live/prepare_injector.py'))['prepare']
        prepare(source, output)
        packed = gzip.compress(output.read_bytes(), compresslevel=9, mtime=0)
        verify(packed)
        return packed


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', type=Path)
    args = parser.parse_args()
    if args.apk:
        with zipfile.ZipFile(args.apk) as apk:
            verify(apk.read(ASSET))
            for name in ('lib/arm64-v8a/libvivoAe.so', 'assets/vivo-aec/observer.js',
                         'assets/vivo-aec/policy-fix.sh', 'assets/vivo-aec/COPYING.frida'):
                if not apk.read(name):
                    raise ValueError('Empty AE artifact: ' + name)
        print('PASS: APK stock AE runtime and pinned injector SHA-256')
        return
    output = Path(__file__).resolve().parents[1] / 'app/src/main' / ASSET
    if output.is_file():
        verify(output.read_bytes())
    else:
        packed = fetch_runtime()
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(packed)
    print('PASS: pinned AE injector SHA-256')


if __name__ == '__main__':
    main()
