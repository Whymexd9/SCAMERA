#!/usr/bin/env python3
"""P402 (owner, 2026-10-29: «dezipper нам также перенеси»): the merge's dezipper (HybridTuning::dezipper, kHybDezip put into
kHybMergeCommon by hybridDezipSource only when it is on). Checks on app/src/main/cpp/scam-hybrid.h:
- off (the default): kHybCommon, kHybHelpers, kHybMergeCommon and kHybMergeMain1 are the sources before P402 (their SHA-256; no
  line of the merge program changed), hybridPartialSpec adds DEZIP only for dezipper > 0, the app sends no line for 0;
- on: kHybMergeCommon with the dezipper as hybridDezipSource builds it (before frameSamples, its call after each role line)
  compiles as the merge program with kHybMergeMain1 on the host GPU (moderngl, GLSL 4.30);
- the base frame's weight (kHybDezip's dezip, mirrored): 1 with every donor accepted, the dezipper with every donor rejected,
  max(1, dezipper - accepted) at 70 % or less of the donors, a smooth ramp between (98 % .. 70 %), never below 1.
Usage: check_hybrid_dezipper.py
"""
from pathlib import Path
import re
import hashlib
import sys

import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
H = ROOT / 'app/src/main/cpp/scam-hybrid.h'
SRC = H.read_text(encoding='utf-8')
JAVA = (ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/settings/PreferenceKeys.java').read_text(encoding='utf-8')
FAIL = []
MERGE_SOURCES = 'c656cf0ed3db51c663ac95b895a53a2b66bfd2d588e8c228705a6b40cdcc49d0'


def check(cond, msg):
    print(('PASS ' if cond else 'FAIL ') + msg)
    if not cond:
        FAIL.append(msg)


def body(src, name):
    m = re.search(r'static const char\* ' + name + r'=R"\((.*?)\)";', src, re.S)
    assert m, name
    return m.group(1)


def dezip_source(common):
    """hybridDezipSource."""
    head = "void frameSamples(inout Acc a,int f,vec2 O,float r,float cover,vec3 P,bool win){\n"
    assert common.count(head) == 1
    common = common.replace(head, body(SRC, 'kHybDezip') + head)
    role = "    int role=int(fParam[f].w);\n"
    for fn in ("void frameSamples(inout Acc a,", "void frameSamplesPlain(inout Acc a,"):
        f = common.find(fn)
        r = common.find(role, f)
        assert 0 <= r - f < 200, fn
        common = common[:r + len(role)] + "    r=dezip(f,role,r,cover);\n" + common[r + len(role):]
    return common


def dezip(donors, expected, t):
    hole = np.clip((0.98 - donors / max(expected, 1.0)) / 0.28, 0.0, 1.0)
    return 1.0 + (max(1.0, t - donors) - 1.0) * hole


def main():
    # the four sources of the merge program before P402 (fc15982a), as one stream: unchanged while the dezipper is off
    # P415: noiseU's deep-shadow read term (its uniform and one line, a no-op while darkReadU.x is 0: noiseDarkRead off) is the only
    # change since; both lines must be there exactly once and are taken out of the stream
    p415 = ('uniform vec3 darkReadU;      // P415 noiseDarkRead: x the read term\'s extra factor in noiseU, full below y, none from z (0: off)\n',
            '    if(darkReadU.x>0.0)var+=darkReadU.x*fNoiseP[f].xy.y*g*g*(1.0-smoothstep(darkReadU.y,darkReadU.z,l));\n')
    stream = ''.join(body(SRC, n) for n in ('kHybCommon', 'kHybHelpers', 'kHybMergeCommon', 'kHybMergeMain1'))
    check(all(stream.count(line) == 1 for line in p415), 'P415: the deep-shadow read term in noiseU, once')
    for line in p415:
        stream = stream.replace(line, '')
    got = hashlib.sha256(stream.encode()).hexdigest()
    check(got == MERGE_SOURCES, 'the merge program\'s sources unchanged without the dezipper (%s)' % got[:12])
    spec = re.search(r'inline std::string hybridPartialSpec\(.*?\n\}', SRC, re.S).group(0)
    check('+(t.dezipper>0.f?"#define DEZIP 1\\n":"")' in spec, 'hybridPartialSpec: DEZIP only for dezipper > 0')
    check('float dezipper=0.f;' in SRC and 'set("dezipper",&t.dezipper)' in SRC, 'HybridTuning::dezipper 0 by default, its tuning key')
    check('if (dezipper > 0f) out.append("dezipper ")' in JAVA, 'the app sends no line for 0')
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    text = body(SRC, 'kHybCommon') + body(SRC, 'kHybHelpers') + dezip_source(body(SRC, 'kHybMergeCommon')) + body(SRC, 'kHybMergeMain1')
    try:
        ctx.compute_shader(text.replace('#version 310 es', '#version 430', 1))
        ok = True
    except Exception as e:
        print(str(e)[:2000])
        ok = False
    check(ok, 'the merge program with the dezipper compiles')
    t, n = 4.5, 9
    check(dezip(9.0, n, t) == 1.0 and dezip(8.9, n, t) == 1.0, 'every donor accepted: the base weighs 1')
    check(abs(dezip(0.0, n, t) - 4.5) < 1e-12, 'every donor rejected: the dezipper (%.2f)' % dezip(0.0, n, t))
    check(abs(dezip(0.7 * n, n, t) - 1.0) < 1e-12 and abs(dezip(2.0, n, t) - 2.5) < 1e-12, 'max(1, dezipper - accepted) at 70 % or less')
    ws = [dezip(d, n, t) for d in np.linspace(0, n, 901)]
    check(min(ws) >= 1.0 and np.abs(np.diff(ws)).max() < 0.02, 'never below 1, no step (%.4f)' % np.abs(np.diff(ws)).max())
    print('hybrid dezipper check %s' % ('FAIL' if FAIL else 'PASS'))
    return 1 if FAIL else 0


if __name__ == '__main__':
    sys.exit(main())
