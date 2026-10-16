#!/usr/bin/env python3
"""Add locally supplied, pinned QNN assets to the CI template and re-sign it.
No network downloads and no vendor binaries are committed to the repository.
"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
# The worker executable for the su launcher (the app process spawns lib/arm64-v8a/libscamera_worker.so).
PREFIX = 'assets/scam-neural/arm64-v8a/'
# Worker without root: executable + QNN/CRE runtime installed into nativeLibraryDir.
LIB_PREFIX = 'lib/arm64-v8a/'
LIB_WORKER = 'libscamera_worker.so'


def pinned_assets(manifest, expected):
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


def native_libraries(*manifests):
    """P175: every packaged runtime file is a native library (the neural remosaic's networks are no longer packaged)."""
    result = {}
    for manifest in manifests:
        for name, sha in manifest.items():
            if not name.endswith('.so'):
                raise ValueError('Packaged runtime entry is not a native library: ' + name)
            if name in result:
                raise ValueError('Duplicate native library: ' + name)
            result[name] = sha
    return result


def verify_bundle(apk, libraries):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate APK entries')
        check_worker(archive.read(PREFIX + 'scam-neural-worker'))
        if archive.read(LIB_PREFIX + LIB_WORKER) != archive.read(PREFIX + 'scam-neural-worker'):
            raise ValueError('Native-library worker differs from the asset worker')
        for name, sha in libraries.items():
            with archive.open(LIB_PREFIX + name) as stream:
                if hashlib.file_digest(stream, 'sha256').hexdigest() != sha:
                    raise ValueError('Native library hash mismatch: ' + name)
        if archive.testzip() is not None:
            raise ValueError('Invalid APK CRC')


def signing_key():
    """The SCAMERA release key: SCAMERA_KEYSTORE / SCAMERA_KEYSTORE_PASSWORD / SCAMERA_KEY_ALIAS (CI), else
    SCAMERA-PC/signing/signing.properties next to the repository. The repository is public: the key is never in it."""
    keystore = os.environ.get('SCAMERA_KEYSTORE')
    if keystore:
        return keystore, os.environ.get('SCAMERA_KEY_ALIAS', 'scamera'), os.environ.get('SCAMERA_KEYSTORE_PASSWORD', '')
    props = ROOT.parent / 'signing' / 'signing.properties'
    if props.is_file():
        values = dict(line.split('=', 1) for line in props.read_text(encoding='utf-8').splitlines()
                      if '=' in line and not line.lstrip().startswith('#'))
        return str(props.parent / values['storeFile']), values.get('keyAlias', 'scamera'), values['storePassword']
    raise SystemExit('SCAMERA signing key missing: set SCAMERA_KEYSTORE and SCAMERA_KEYSTORE_PASSWORD, or create ' + str(props))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--template', type=Path, required=True)
    # P175: the tele576 set (the bundle group) is no longer packaged; the option stays accepted for older build scripts.
    parser.add_argument('--bundle-dir', type=Path, help='ignored (the tele576 neural remosaic set is no longer packaged)')
    parser.add_argument('--hexquad-dir', type=Path, required=True)
    parser.add_argument('--scam-dir', type=Path, required=True)
    parser.add_argument('--apksigner', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    # The SHDR job's QNN runtime (the hexquad group of the private bundle keeps its name); P175: no HexQuad / Quad networks
    hex_assets = pinned_assets('HEX_FILES', 4)
    # Every pinned file is required (bundle v2 carries the CRE runtime): an APK without them is not published (the v1
    # bundle lacked them and the Actions APK silently differed from local builds).
    # SHDR's bundled CRE motion (libvivo_nice_cre.so, libc++_shared.so, 3 compat stubs); P129: no SCAM HDR networks
    scam_assets = pinned_assets('SCAM_FILES', 5)
    libraries = native_libraries(hex_assets, scam_assets)
    sources = ((args.hexquad_dir, hex_assets), (args.scam_dir, scam_assets))
    missing = sorted(str(d / n) for d, m in sources for n in m if not (d / n).is_file())
    if missing:
        raise ValueError('Required private assets missing (no incomplete APK is published): ' + ', '.join(missing))
    for directory, manifest in sources:
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
            for directory, manifest in sources:
                for name in manifest:
                    # The QNN / CRE runtime ships only as native libraries (mapped executable in the app sandbox, also
                    # used by the su launcher).
                    if LIB_PREFIX + name in archive.namelist():
                        raise ValueError('Template already contains ' + LIB_PREFIX + name)
                    archive.write(directory / name, LIB_PREFIX + name)
            archive.writestr(LIB_PREFIX + LIB_WORKER, archive.read(PREFIX + 'scam-neural-worker'))
        keystore, alias, password = signing_key()
        # The password reaches apksigner through its environment, never the command line.
        subprocess.run(['java', '-jar', str(args.apksigner), 'sign', '--ks', keystore, '--ks-key-alias', alias,
                        '--ks-pass', 'env:SCAMERA_APKSIGNER_PASS', '--key-pass', 'env:SCAMERA_APKSIGNER_PASS',
                        '--out', str(signed), str(unsigned)], check=True,
                       env=dict(os.environ, SCAMERA_APKSIGNER_PASS=password))
        subprocess.run(['java', '-jar', str(args.apksigner), 'verify', '--verbose', '--print-certs', str(signed)], check=True)
        verify_bundle(signed, libraries)
        signed.rename(args.output)
    print('BUNDLED APK:', args.output)
    print('SHA256:', digest_file(args.output))


if __name__ == '__main__':
    main()
