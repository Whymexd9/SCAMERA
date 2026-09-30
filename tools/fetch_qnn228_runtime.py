#!/usr/bin/env python3
"""Stage the Hexagon v75 (Snapdragon 8 Gen 3) NICE assets for tools/package_vivo_neural.py.

* QAIRT 2.28 runtime (libQnnHtp / V75 stub / V75 skel) from the public Maven Central
  qnn-runtime AAR, pinned by SHA-256 (libQnnHtp.so is staged as libQnnHtp228.so so it never
  collides with the vivo build's libQnnHtp.so).
* The distilled fp16 NICE student compiled for v75 (tools/nice-student/nice-student-v75.bin).
"""
import argparse
import hashlib
import io
import re
import shutil
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AAR_URL = 'https://repo1.maven.org/maven2/com/qualcomm/qti/qnn-runtime/2.28.0/qnn-runtime-2.28.0.aar'
AAR_SHA256 = 'a3b2891e456dbb758fd887e3f6099322cbcdbf5cb4a6996c700714055fcebc4a'
STAGE = {  # name inside the AAR -> staged name
    'jni/arm64-v8a/libQnnHtp.so': 'libQnnHtp228.so',
    'jni/arm64-v8a/libQnnHtpV75Stub.so': 'libQnnHtpV75Stub.so',
    'jni/arm64-v8a/libQnnHtpV75Skel.so': 'libQnnHtpV75Skel.so',
}


def pinned():
    source = ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/VivoNeuralWorker.java'
    block = re.search(r'NICE75_FILES\s*=\s*\{(.*?)\n    \};', source.read_text(encoding='utf-8'), re.S)
    return dict(re.findall(r'\{"([^"/]+)","([a-f0-9]{64})"\}', block.group(1)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True, help='directory passed to package_vivo_neural.py --nice-dir')
    parser.add_argument('--aar', type=Path, help='use a local copy of the AAR instead of downloading it')
    args = parser.parse_args()
    data = args.aar.read_bytes() if args.aar else urllib.request.urlopen(AAR_URL, timeout=120).read()
    if hashlib.sha256(data).hexdigest() != AAR_SHA256:
        raise SystemExit('qnn-runtime AAR SHA-256 mismatch')
    expected = pinned()
    args.output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for source, name in STAGE.items():
            contents = archive.read(source)
            if hashlib.sha256(contents).hexdigest() != expected[name]:
                raise SystemExit('QAIRT runtime hash differs from the manifest: ' + name)
            (args.output / name).write_bytes(contents)
    student = ROOT / 'tools/nice-student/nice-student-v75.bin'
    if hashlib.sha256(student.read_bytes()).hexdigest() != expected['nice-student-v75.bin']:
        raise SystemExit('nice-student-v75.bin differs from the manifest')
    shutil.copyfile(student, args.output / student.name)
    print('Staged Hexagon v75 NICE assets in ' + str(args.output))


if __name__ == '__main__':
    main()
