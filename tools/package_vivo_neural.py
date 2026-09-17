#!/usr/bin/env python3
"""Add locally supplied, pinned QNN assets to the CI template and re-sign it.
No network downloads and no vendor binaries are committed to the repository.
"""
import argparse
import hashlib
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PREFIX = 'assets/vivo-neural/arm64-v8a/'


def pinned_assets():
    source = ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/VivoNeuralWorker.java'
    result = dict(re.findall(r'\{"([^"/]+)","([a-f0-9]{64})"\}', source.read_text()))
    if len(result) != 5:
        raise ValueError('Unexpected bundled asset manifest')
    return result


def digest_file(path):
    with open(path, 'rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def check_worker(data):
    if data[:6] != b'\x7fELF\x02\x01':
        raise ValueError('Worker is not a little-endian ELF64 executable')
    kind, machine = struct.unpack_from('<HH', data, 16)
    entry, phoff = struct.unpack_from('<QQ', data, 24)
    phsize, phnum = struct.unpack_from('<HH', data, 54)
    if kind != 3 or machine != 183 or entry == 0 or phsize != 56:
        raise ValueError('Worker must be AArch64 PIE with a real entry point')
    interpreter = None
    for index in range(phnum):
        offset = phoff + index * phsize
        if struct.unpack_from('<I', data, offset)[0] == 3:  # PT_INTERP
            start = struct.unpack_from('<Q', data, offset + 8)[0]
            length = struct.unpack_from('<Q', data, offset + 32)[0]
            interpreter = data[start:start + length]
    if interpreter != b'/system/bin/linker64\0':
        raise ValueError('Worker is not a standalone Android executable')


def verify_bundle(apk, assets):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate APK entries')
        check_worker(archive.read(PREFIX + 'vivo-neural-worker'))
        for name, sha in assets.items():
            with archive.open(PREFIX + name) as stream:
                if hashlib.file_digest(stream, 'sha256').hexdigest() != sha:
                    raise ValueError('APK asset hash mismatch: ' + name)
        if archive.testzip() is not None:
            raise ValueError('Invalid APK CRC')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--template', type=Path, required=True)
    parser.add_argument('--bundle-dir', type=Path, required=True)
    parser.add_argument('--apksigner', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assets = pinned_assets()
    for name, sha in assets.items():
        if digest_file(args.bundle_dir / name) != sha:
            raise ValueError('Source asset hash mismatch: ' + name)
    if args.output.exists():
        raise ValueError('Output already exists; choose a new filename')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='scamera-bundle-', dir=args.output.parent) as tmp:
        unsigned = Path(tmp) / 'unsigned.apk'
        signed = Path(tmp) / 'signed.apk'
        shutil.copyfile(args.template, unsigned)
        with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            check_worker(archive.read(PREFIX + 'vivo-neural-worker'))
            for name in assets:
                if PREFIX + name in archive.namelist():
                    raise ValueError('Template already contains ' + name)
                archive.write(args.bundle_dir / name, PREFIX + name)
        subprocess.run(['java', '-jar', str(args.apksigner), 'sign', '--ks', str(ROOT / 'key/PcamLeak.jks'),
                        '--ks-key-alias', 'key0', '--ks-pass', 'pass:photoncamera',
                        '--key-pass', 'pass:photoncamera', '--out', str(signed), str(unsigned)], check=True)
        subprocess.run(['java', '-jar', str(args.apksigner), 'verify', '--verbose', '--print-certs', str(signed)], check=True)
        verify_bundle(signed, assets)
        signed.rename(args.output)
    print('BUNDLED APK:', args.output)
    print('SHA256:', digest_file(args.output))


if __name__ == '__main__':
    main()
