#!/usr/bin/env python3
"""P214k: the plausibility cap of the fixed-pattern outlier test on the GPU. The shipped programs kHybMean, kHybFlags (with
kHybFlagRank before it, as HybridGpu compiles it), kHybFlagCap and kHybFlagPrune of app/src/main/cpp/scam-hybrid.h, wired per strip
as HybridGpu::merge does (mean -> flags -> cap -> prune), on synthetic still bursts (RAW 10-bit, black 64, RGGB, 8 frames of the
same scene: a phone on a table).

The X7 Ultra's 50 MP still bursts flagged 11-25k sites/MP (the mean of 8 still frames keeps the texture, and the header noise model
of those frames is ~4x low in variance), against 95-134/MP on 12 MP night shots. Scenes (1024 x 768, two strips of 192 cell rows):
- texture: the top strip a dark colour texture (the same in every frame), the bottom strip flat dark; isolated hot pixels in both;
- model low: flat dark everywhere, the frames' real noise 4x the variance of the model the test uses; isolated hot pixels.
Checks:
- without the cap the flood is reproduced (texture > 5x the cap, the low model > 3x) in the strips it hits, and every hot pixel is
  flagged;
- with the cap a strip over it keeps at most its allowance (hotCap sites per MP of its own rows), every isolated hot pixel of 6 DN10
  or more stays flagged (weaker ones inside the texture or under the 4x noise may go: 1-2 sigma of one frame), and the flags on
  texture / noise drop to the allowance (texture survives: >= 10x fewer of its sites lose their sample);
- strong texture patches beside weak texture: the rank (excess over the noise and the neighbours' spread) keeps the hot pixels
  outside the patches;
- the model 4x low: ranked against the noise measured around the site (temporal variance of the hot frames) more weak hot
  pixels (4..8 DN10) stay than against the model noise;
- blinking (RTS) defects, whose mean excess in the hot frames can be as low as a noise peak's: every one the test flags stays
  flagged (their own temporal variance far above their ring's);
- a strip within the cap (flat, true model) keeps exactly the flags it had without the cap (bits 0 and 1, every site; the rank
  bits 8..15 are written only with the cap and no program reads them but kHybFlagPrune);
- the native flags program carries the same rank and histogram; the C++ defaults (hotCap 2000, its tuning key) and the cap
  block layout are those the programs use.
Usage: check_hybrid_outlier_cap.py
"""
from pathlib import Path
import re
import struct
import sys
import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
SRC = (ROOT / 'app/src/main/cpp/scam-hybrid.h').read_text(encoding='utf-8')

try:
    ctx = moderngl.create_standalone_context(backend='egl', require=430)
except Exception:
    ctx = moderngl.create_standalone_context(require=430)


def body(name):
    m = re.search(r'static const char\* ' + name + r'=R"\((.*?)\)";', SRC, re.S)
    assert m, name
    return m.group(1)


def program(*names):
    text = ''.join(body(n) for n in names)
    if not text.startswith('#version'):
        text = body('kHybCommon') + body('kHybHelpers') + text  # HybridGpu::compile: kHybCommon + helpers + body
    return ctx.compute_shader(text.replace('#version 310 es', '#version 430', 1))


MEAN = program('kHybMean')
FLAGS = program('kHybFlagRank', 'kHybFlags')
CAP = program('kHybFlagCap')
PRUNE = program('kHybFlagPrune')

W, H = 1024, 768
W2, H2 = W // 2, H // 2
BLACK, RANGE = 64.0, 959.0
SLOPE, OFFSET = 7.2e-5, 2.2e-7      # the test's model (binned X7U frames: slope 0.000072)
N = 8
HOT_SIGMA, BASE_SIGMA, CROSS, MAX_LEVEL = 5.0, 7.0, 0.35, 0.03
CAP_PER_MP = 2000.0
CAP_WORDS = 260
FRAMES_WORDS = 3                    # Sums: per-frame weights 0..N-1 (unused here), fixed / base / rim counters at N, N+1, N+2
CAP0 = N + 3 + 2                    # natSum0 + 2 with natSum0 = frames + 3
STRIPS = [(0, H2 // 2), (H2 // 2, H2)]

fails = []


def check(cond, msg):
    print(('PASS ' if cond else 'FAIL ') + msg)
    if not cond:
        fails.append(msg)


def setu(prog, name, value):
    if name in prog:
        prog[name].value = value


def frame_table():
    geo = np.zeros((128, 4), np.uint32)
    for f in range(N):
        geo[f] = (f * W * H, 0, H, struct.unpack('<I', struct.pack('<f', 1.0))[0])
    rest = np.zeros((5, 128, 4), np.float32)
    rest[2, :, 0] = 1.0  # fParam.x = 1 (base units)
    return geo.tobytes() + rest.tobytes()


def run(frames, cap, variance=True):
    """frames: N x H x W uint16 RAW codes. Returns (flags H x W uint32 of every strip's own rows, sums, per-strip info).
    variance False: the rank takes the model noise (the flag programs' hotU.w = 0), for the comparison of scenario 4."""
    fb = ctx.buffer(np.ascontiguousarray(frames, '<u2').tobytes())
    ft = ctx.buffer(frame_table())
    sums = ctx.buffer(np.zeros(CAP0 + 2 * CAP_WORDS, np.uint32).tobytes())
    fb.bind_to_storage_buffer(0)
    ft.bind_to_uniform_block(0)
    sums.bind_to_storage_buffer(9)
    for p in (MEAN, FLAGS):
        setu(p, 'size', (W, H)); setu(p, 'frameCount', N); setu(p, 'cfaShift', (0, 0))
        setu(p, 'black', (BLACK,) * 4); setu(p, 'inv', (1.0 / RANGE,) * 4); setu(p, 'phaseColor', (0, 1, 1, 2))
        setu(p, 'baseNoise', (SLOPE, OFFSET)); setu(p, 'clipLevel', 0.98); setu(p, 'markU', 0); setu(p, 'chunkU', 0)
    MEAN['hotList'].value = tuple(range(N)) + (0,) * (16 - N)
    setu(FLAGS, 'hotSigU', (HOT_SIGMA, BASE_SIGMA, CROSS, MAX_LEVEL))
    out = np.zeros((H, W), np.uint32)
    info = []
    for cy0, cy1 in STRIPS:
        ry0, ry1 = max(0, cy0 - 3), min(H2, cy1 + 3)
        words = (2 * (ry1 - ry0) + 4) * W  # the mean; with the cap the sites' temporal variance follows (HybridGpu::merge)
        mean = ctx.buffer(reserve=words * 4 * (2 if cap else 1))
        setu(MEAN, 'hotU', (N, words if cap else 0, 0, 0))
        setu(FLAGS, 'hotU', (N, 3, CAP0 if cap else 0, words if cap and variance else 0))
        flags = ctx.buffer(reserve=2 * (ry1 - ry0) * W * 4)
        mean.bind_to_storage_buffer(1)
        flags.bind_to_storage_buffer(2)
        for p in (MEAN, FLAGS):
            setu(p, 'ry0', ry0); setu(p, 'ry1', ry1)
        setu(FLAGS, 'cy0', cy0); setu(FLAGS, 'cy1', cy1)
        MEAN.run((W + 7) // 8, (2 * (ry1 - ry0) + 4 + 7) // 8, 1)
        ctx.memory_barrier()
        FLAGS.run((W + 7) // 8, (2 * (ry1 - ry0) + 7) // 8, 1)
        ctx.memory_barrier()
        own0, own1 = 2 * (cy0 - ry0), 2 * (cy1 - ry0)
        allowed = int(np.floor(CAP_PER_MP * W * (own1 - own0) / 1e6))  # HybridGpu capStrip
        if cap:
            CAP['capU'].value = (CAP0, allowed)
            CAP.run(1, 1, 1)
            ctx.memory_barrier()
            PRUNE['pruneU'].value = (W, 2 * (ry1 - ry0), own0, own1)
            PRUNE['capBase'].value = CAP0
            setu(PRUNE, 'chunkU', 0)
            PRUNE.run((W + 7) // 8, (2 * (ry1 - ry0) + 7) // 8, 1)
            ctx.memory_barrier()
        f = np.frombuffer(flags.read(), '<u4').reshape(2 * (ry1 - ry0), W)
        out[2 * cy0:2 * cy1] = f[own0:own1]
        s = np.frombuffer(sums.read(), '<u4')
        info.append(dict(allowed=allowed, threshold=int(s[CAP0 + 256]), hist=int(s[CAP0:CAP0 + 256].sum())))
        mean.release(); flags.release()
    s = np.frombuffer(sums.read(), '<u4').copy()
    for b in (fb, ft, sums):
        b.release()
    return out, s, info


def make(scene, k_noise, hot, seed, blink=()):
    """scene H x W normalised; hot: list of (y, x, excess); the frames' noise k_noise x the model variance. blink: (y, x, p, high)
    sites that are `high` over the scene in a frame with probability p, else at the scene (RTS)."""
    rng = np.random.default_rng(seed)
    clean = scene.copy()
    for y, x, e in hot:
        clean[y, x] += e
    var = k_noise * (SLOPE * np.maximum(clean, 0) + OFFSET)
    frames = []
    for _ in range(N):
        v = clean + rng.standard_normal((H, W)) * np.sqrt(var)
        for y, x, p, hi in blink:
            v[y, x] += hi if rng.random() < p else 0.0
        frames.append(np.clip(np.floor(BLACK + v * RANGE + 0.5), 0, 1023).astype(np.uint16))
    return np.stack(frames)


def hot_sites(rng, count, rows, margin=8, lo=0.004, hi=0.08):
    """isolated hot pixels: not within 6 px of each other, away from the borders, excess lo..hi (default 4..77 DN10)."""
    out = []
    while len(out) < count:
        y, x = int(rng.integers(rows[0] + margin, rows[1] - margin)), int(rng.integers(margin, W - margin))
        if all(abs(y - a) > 6 or abs(x - b) > 6 for a, b, _ in out):
            out.append((y, x, float(np.exp(rng.uniform(np.log(lo), np.log(hi))))))
    return out


def texture(rng, patches=0.0):
    """a dark colour texture: per-site lognormal detail around 0.012 of white, ~1.4 sigma of one frame (chromatic, so the other
    colours do not follow): the mean of 8 still frames flags ~27k sites/MP of it, as the X7U table bursts. patches > 0: twelve
    80 x 120 px patches of that log amplitude (strong texture beside weak: ranked by the plain excess / sigma, scenario 3 kept 27 of
    40 hot pixels and lost some outside the patches; the shipped rank keeps 32, all 23 outside). Returns the scene and the mask."""
    t = rng.standard_normal((H, W))
    t = 0.5 * t + 0.25 * (np.roll(t, 1, 0) + np.roll(t, 1, 1))  # a little correlation between neighbours
    amp = np.full((H, W), 0.12 if patches <= 0 else 0.08)
    if patches > 0:
        for _ in range(12):
            y0, x0 = int(rng.integers(0, H // 2)), int(rng.integers(0, W))
            amp[max(0, y0 - 40):y0 + 40, max(0, x0 - 60):x0 + 60] = patches
    return 0.012 * np.exp(amp * t), amp > 0.12


def bit0(flags, rows=None):
    f = (flags & 1) != 0
    return f if rows is None else f[rows[0]:rows[1]]


rng = np.random.default_rng(214)
TOP, BOTTOM = (0, H // 2), (H // 2, H)
mp_strip = W * (H // 2) / 1e6

# 1. texture in the top strip, flat dark below; the model true; hot pixels in both strips
scene1 = np.full((H, W), 0.010)
scene1[TOP[0]:TOP[1]] = texture(rng)[0][TOP[0]:TOP[1]]
hot_top, hot_bottom = hot_sites(rng, 40, TOP), hot_sites(rng, 40, BOTTOM)
burst1 = make(scene1, 1.0, hot_top + hot_bottom, 1)
off1, sums_off1, _ = run(burst1, False)
on1, sums_on1, info1 = run(burst1, True)
tex_mask = np.zeros((H, W), bool); tex_mask[TOP[0] + 8:TOP[1] - 8, 8:W - 8] = True
for y, x, _ in hot_top:
    tex_mask[y - 2:y + 3, x - 2:x + 3] = False
flood = bit0(off1, TOP).sum() / mp_strip
check(flood > 5 * CAP_PER_MP, f'texture strip without the cap: {flood:.0f} fixed-pattern sites/MP (the flood, > 5x {CAP_PER_MP:.0f})')
check(all(off1[y, x] & 1 for y, x, _ in hot_top + hot_bottom), 'without the cap every hot pixel is flagged')
kept_top = int(bit0(on1, TOP).sum())
check(info1[0]['threshold'] > 0 and kept_top <= info1[0]['allowed'],
      f'texture strip with the cap: {kept_top} sites kept of {int(bit0(off1, TOP).sum())} (allowance {info1[0]["allowed"]}, lowest rank kept {info1[0]["threshold"]})')
missing = [(y, x, round(e, 4)) for y, x, e in hot_top if not on1[y, x] & 1]
check(not [h for h in missing if h[2] >= 0.006],
      f'texture strip with the cap: every isolated hot pixel of 6 DN10 or more stays flagged ({len(hot_top) - len(missing)} of {len(hot_top)} kept; lost {missing})')
before_tex, after_tex = int((bit0(off1) & tex_mask).sum()), int((bit0(on1) & tex_mask).sum())
check(after_tex * 10 <= before_tex, f'texture survives: its sites flagged {before_tex} -> {after_tex} ({100.0 * before_tex / tex_mask.sum():.2f} % -> {100.0 * after_tex / tex_mask.sum():.3f} %)')
check(np.array_equal(on1[BOTTOM[0]:BOTTOM[1]] & 3, off1[BOTTOM[0]:BOTTOM[1]] & 3) and info1[1]['threshold'] == 0,
      f'flat strip within the cap ({bit0(off1, BOTTOM).sum() / mp_strip:.0f}/MP): its flags (bits 0 and 1) unchanged, rank 0 kept')
check(all(on1[y, x] & 1 for y, x, _ in hot_bottom), 'flat strip: every hot pixel flagged with the cap')
check(int(sums_on1[CAP0 + 257]) == 1, f'one strip over the cap counted (got {int(sums_on1[CAP0 + 257])})')
cleared = int(bit0(off1).sum() - bit0(on1).sum())
check(int(sums_on1[CAP0 + 258]) == cleared, f'cleared sites counted: {int(sums_on1[CAP0 + 258])} = {cleared}')
check(int(sums_on1[N]) == int(sums_off1[N]) == int(bit0(off1).sum()), 'the fixed-pattern count before the cap is the one without it')
check(all(i['hist'] == 0 for i in info1), 'the cap clears its histogram for the next strip')
check(np.array_equal(on1 & 2, off1 & 2), 'base transient flags (bit 1) unchanged by the cap')

# 2. the model 4x low in variance, flat dark: noise peaks pass 5 sigma of the model
scene2 = np.full((H, W), 0.010)
scene2[:, W // 2:] = 0.020
hot2 = hot_sites(rng, 40, TOP) + hot_sites(rng, 40, BOTTOM)
burst2 = make(scene2, 4.0, hot2, 2)
off2, _, _ = run(burst2, False)
on2, sums_on2, info2 = run(burst2, True)
for name, rows, inf in (('top', TOP, info2[0]), ('bottom', BOTTOM, info2[1])):
    before, after = int(bit0(off2, rows).sum()), int(bit0(on2, rows).sum())
    check(before / mp_strip > 3 * CAP_PER_MP and after <= inf['allowed'],
          f'model 4x low, {name} strip: {before / mp_strip:.0f} -> {after / mp_strip:.0f} sites/MP (allowance {inf["allowed"]} sites)')
lost2 = [(y, x, round(e, 4)) for y, x, e in hot2 if not on2[y, x] & 1]
weak = [h for h in lost2 if h[2] >= 0.006]
check(not weak, f'model 4x low: every hot pixel of 6 DN10 or more stays flagged ({len(hot2) - len(lost2)} of {len(hot2)} kept; lost {lost2})')
check(all(off2[y, x] & 1 for y, x, _ in hot2), 'model 4x low: every hot pixel flagged without the cap')

# 3. strong texture patches beside weak texture (top strip), flat below
tex3, strong = texture(rng, 0.4)
scene3 = np.full((H, W), 0.010)
scene3[TOP[0]:TOP[1]] = tex3[TOP[0]:TOP[1]]
strong[TOP[1]:] = False
hot3 = hot_sites(rng, 40, TOP)
burst3 = make(scene3, 1.0, hot3, 3)
off3, _, _ = run(burst3, False)
on3, _, info3 = run(burst3, True)
lost3 = [(y, x, round(e, 4), bool(strong[max(0, y - 3):y + 4, max(0, x - 3):x + 4].any())) for y, x, e in hot3 if not on3[y, x] & 1]
in3 = sum(bool(strong[max(0, y - 3):y + 4, max(0, x - 3):x + 4].any()) for y, x, _ in hot3)
check(not [h for h in lost3 if h[2] >= 0.006 and not h[3]] and len(lost3) * 10 <= len(hot3) * 3,
      f'strong texture patches: every hot pixel of 6 DN10 or more outside them stays flagged, at least 70 % kept '
      f'({len(hot3) - len(lost3)} of {len(hot3)}, {in3} inside a patch; lost (y, x, excess, in a patch) {lost3})')
before3, after3 = int(bit0(off3, TOP).sum()), int(bit0(on3, TOP).sum())
check(after3 <= info3[0]['allowed'] and after3 * 10 <= before3, f'strong texture patches: sites {before3} -> {after3} (allowance {info3[0]["allowed"]})')

# 4. the rank against the noise measured at the site (the temporal variance kHybMean writes after the mean) and against the model
# noise, the model 4x low: weak hot pixels (4..8 DN10) kept
kept_measured = kept_model = 0
for seed in (11, 12):
    r4 = np.random.default_rng(seed)
    scene4 = np.full((H, W), 0.010); scene4[:, W // 2:] = 0.020
    hot4 = hot_sites(r4, 40, TOP, lo=0.004, hi=0.008) + hot_sites(r4, 40, BOTTOM, lo=0.004, hi=0.008)
    burst4 = make(scene4, 4.0, hot4, seed)
    m4, _, _ = run(burst4, True)
    o4, _, _ = run(burst4, True, variance=False)
    kept_measured += sum(1 for y, x, _ in hot4 if m4[y, x] & 1)
    kept_model += sum(1 for y, x, _ in hot4 if o4[y, x] & 1)
check(kept_measured > kept_model and kept_measured >= 0.9 * 160,
      f'model 4x low, hot pixels of 4..8 DN10: {kept_measured} of 160 kept ranked against the measured noise, {kept_model} against the model')

# 5. blinking (RTS) defects: +16 DN10 over the scene in a quarter of the frames at random (the X7U's blinking Quad blocks: 53..87
# DN10 from frame to frame), the model 4x low, texture in the top strip: a mean excess in the hot frames near the noise peaks' or
# the texture's, a variance far above its ring's. Ranked without the variance ratio, 35 of these 47 stayed (12 dots).
r5 = np.random.default_rng(15)
blinks = [(y, x, 0.25, 0.0167) for y, x, _ in hot_sites(r5, 30, TOP) + hot_sites(r5, 30, BOTTOM)]
scene5 = np.full((H, W), 0.010); scene5[:, W // 2:] = 0.020
scene5[TOP[0]:TOP[1]] = texture(r5)[0][TOP[0]:TOP[1]]
burst5 = make(scene5, 4.0, [], 5, blinks)
on5, _, _ = run(burst5, True)
off5, _, _ = run(burst5, False)
flagged5 = sum(1 for y, x, _, _ in blinks if off5[y, x] & 1)
kept5 = sum(1 for y, x, _, _ in blinks if on5[y, x] & 1 and off5[y, x] & 1)
check(kept5 == flagged5 and flagged5 >= 40, f'blinking defects: {kept5} of the {flagged5} the test flags (of {len(blinks)}) stay flagged with the cap')

# 6. the native flags carry the same rank and histogram; the C++ side matches the programs
nat = body('kHybNatFlags')
check(nat.count('vec2 m=hotU.w>0?capMeasured(capRing(x,y,green,b),vAt(x,y),float(hotU.x),sd):vec2(sd,0.0);') == 1
      and body('kHybFlags').count('vec2 m=hotU.w>0?capMeasured(capRing(x,y,green,1),vAt(x,y),float(hotU.x),sd):vec2(sd,0.0);') == 1
      and nat.count('bits|=capRank(p,green,ex,m.x,m.y)<<8u;') == 1 and body('kHybFlags').count('bits|=capRank(p,green,ex,m.x,m.y)<<8u;') == 1,
      'both flag programs rank their fixed-pattern sites (capRank, bits 8..15) against the noise measured around them')
check(body('kHybMean').count('meanv[uint(hotU.y+r*size.x+x)]=q/float(max(hotU.x-1,1));') == 1
      and body('kHybNatMean').count('meanv[uint(hotU.y+r*natU.y+x)]=q/float(max(hotU.x-1,1));') == 1,
      'both mean programs write the temporal variance after the mean')
check(nat.count('atomicAdd(sums[uint(hotU.z)+((bits>>8)&255u)],1u);') == 1, 'the native flags fill the strip histogram')
check('(std::string(kHybNatAccess)+kHybFlagRank+kHybNatFlags)' in SRC and '(std::string(kHybFlagRank)+kHybFlags)' in SRC,
      'HybridGpu compiles the rank before both flag programs')
check(re.search(r'float hotCap=2000\.f;', SRC) is not None and 'set("hotCap",&t.hotCap)' in SRC, 'hotCap: default 2000 sites/MP, tuning key "hotCap"')
check(re.search(r'constexpr size_t kHybridCapWords=260;', SRC) is not None and 'capSum0+258' in SRC and 'sums[h+257u]' in body('kHybFlagCap'),
      'cap block: 256 bins, lowest rank kept, strips capped, sites cleared within kHybridCapWords')
check('const size_t capSum0=natSum0+2;' in SRC and 'capOn?capSum0+2*kHybridCapWords:natSum0+(nat?2:0)' in SRC,
      'the cap blocks follow the native counters; Sums as before without the cap')

if fails:
    print(f'{len(fails)} check(s) FAILED')
    sys.exit(1)
print('check_hybrid_outlier_cap: OK')
