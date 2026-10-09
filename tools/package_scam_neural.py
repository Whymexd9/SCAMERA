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
PREFIX = 'assets/scam-neural/arm64-v8a/'
HEX_PREFIX = 'assets/scam-hexquad/arm64-v8a/'
SCAM_PREFIX = 'assets/scam/arm64-v8a/'
# Worker without root: executable + QNN/CRE runtime installed into nativeLibraryDir.
LIB_PREFIX = 'lib/arm64-v8a/'
LIB_WORKER = 'libscamera_worker.so'


def pinned_assets(manifest='FILES', expected=5):
    """expected=None skips the entry-count check (manifests that grew after the private bundle)."""
    source = ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/ScamNeuralWorker.java'
    block = re.search(r'\b' + re.escape(manifest) + r'\s*=\s*\{(.*?)\n    \};', source.read_text(), re.S)
    if block is None:
        raise ValueError('Missing bundled asset manifest: ' + manifest)
    pairs = re.findall(r'\{"([^"/]+)","([a-f0-9]{64})"\}', block.group(1))
    result = dict(pairs)
    if expected is not None and (len(result) != expected or len(pairs) != expected):
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


def verify_bundle(apk, assets, hex_assets, scam_assets):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate APK entries')
        check_worker(archive.read(PREFIX + 'scam-neural-worker'))
        for prefix, manifest in ((PREFIX, assets), (HEX_PREFIX, hex_assets), (SCAM_PREFIX, scam_assets)):
            for name, sha in manifest.items():
                if name.endswith('.so') and prefix != PREFIX:
                    continue  # shipped as lib/arm64-v8a/<name>, verified below
                with archive.open(prefix + name) as stream:
                    if hashlib.file_digest(stream, 'sha256').hexdigest() != sha:
                        raise ValueError('APK asset hash mismatch: ' + prefix + name)
        if archive.read(LIB_PREFIX + LIB_WORKER) != archive.read(PREFIX + 'scam-neural-worker'):
            raise ValueError('Native-library worker differs from the asset worker')
        for directory_manifest in (hex_assets, scam_assets):
            for name, sha in directory_manifest.items():
                if name.endswith('.so'):
                    with archive.open(LIB_PREFIX + name) as stream:
                        if hashlib.file_digest(stream, 'sha256').hexdigest() != sha:
                            raise ValueError('Native library hash mismatch: ' + name)
        if archive.testzip() is not None:
            raise ValueError('Invalid APK CRC')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--template', type=Path, required=True)
    parser.add_argument('--bundle-dir', type=Path, required=True)
    parser.add_argument('--hexquad-dir', type=Path, required=True)
    parser.add_argument('--scam-dir', type=Path, required=True)
    parser.add_argument('--apksigner', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assets = pinned_assets()
    hex_assets = pinned_assets('HEX_FILES', 6)
    # 2x2 Quad (2x ISZ) models: main IMX06C and tele HP9 ROI; runs on the same QNN runtime as HexQuad.
    hex_assets.update(pinned_assets('QUAD_FILES', 3))
    # Every pinned file is required (bundle v2 carries the Quad/VSR contexts and the CRE runtime): an APK without
    # them is not published (the v1 bundle lacked them and the Actions APK silently differed from local builds).
    # SCAM model + bundled CRE motion (libvivo_nice_cre.so, libc++_shared.so, 3 compat stubs)
    scam_assets = pinned_assets('SCAM_FILES', 6)
    scam_assets.update(pinned_assets('SCAM_TONE_FILES', 5))
    missing = sorted(str(d / n) for d, m in ((args.bundle_dir, assets), (args.hexquad_dir, hex_assets), (args.scam_dir, scam_assets))
                     for n in m if not (d / n).is_file())
    if missing:
        raise ValueError('Required private assets missing (no incomplete APK is published): ' + ', '.join(missing))
    for directory, manifest in ((args.bundle_dir, assets), (args.hexquad_dir, hex_assets), (args.scam_dir, scam_assets)):
        for name, sha in manifest.items():
            if digest_file(directory / name) != sha:
                raise ValueError('Source asset hash mismatch: ' + str(directory / name))
    if args.output.exists():
        raise ValueError('Output already exists; choose a new filename')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='scamera-bundle-', dir=args.output.parent) as tmp:
        unsigned = Path(tmp) / 'unsigned.apk'
        signed = Path(tmp) / 'signed.apk'
        shutil.copyfile(args.template, unsigned)
        with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            check_worker(archive.read(PREFIX + 'scam-neural-worker'))
            for directory, prefix, manifest in ((args.bundle_dir, PREFIX, assets),
                                                (args.hexquad_dir, HEX_PREFIX, hex_assets),
                                                (args.scam_dir, SCAM_PREFIX, scam_assets)):
                for name in manifest:
                    # The hexquad / scam runtime libraries ship only as native libraries (mapped
                    # executable in the app sandbox, also used by the su launcher); the tele576
                    # set has a different QNN build and stays an asset.
                    if name.endswith('.so') and prefix != PREFIX:
                        if LIB_PREFIX + name in archive.namelist():
                            raise ValueError('Template already contains ' + LIB_PREFIX + name)
                        archive.write(directory / name, LIB_PREFIX + name)
                        continue
                    if prefix + name in archive.namelist():
                        raise ValueError('Template already contains ' + prefix + name)
                    archive.write(directory / name, prefix + name)
            archive.writestr(LIB_PREFIX + LIB_WORKER, archive.read(PREFIX + 'scam-neural-worker'))
        subprocess.run(['java', '-jar', str(args.apksigner), 'sign', '--ks', str(ROOT / 'key/PcamLeak.jks'),
                        '--ks-key-alias', 'key0', '--ks-pass', 'pass:photoncamera',
                        '--key-pass', 'pass:photoncamera', '--out', str(signed), str(unsigned)], check=True)
        subprocess.run(['java', '-jar', str(args.apksigner), 'verify', '--verbose', '--print-certs', str(signed)], check=True)
        verify_bundle(signed, assets, hex_assets, scam_assets)
        signed.rename(args.output)
    print('BUNDLED APK:', args.output)
    print('SHA256:', digest_file(args.output))


if __name__ == '__main__':
    main()
