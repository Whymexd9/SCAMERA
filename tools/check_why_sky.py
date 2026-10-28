#!/usr/bin/env python3
"""P378: the WHY tone's sky term on the host GPU - the photo's passes with the sky mask (WhySky: why/skycells.glsl, WhySkyMask's
decision in Java, why/skymask.glsl; why/lpyr.glsl and why/combine.glsl with SKY 1) against the same passes without it (no define:
the photo before P378).

The programs are built as GLInterface.readProgram builds them and drawn as GLProg draws them (check_why_pyramid.Gl); whyLow and the
detail reference come from why/low.glsl on a full-size image, the fusion is WhyFusion's (the pyramid, the guided filter's share above
white), the highlight landing's mask is why/hlmask.glsl's, the decision is WhySkyMask.java itself (compiled with javac, run on the
cells the GPU read back). Checks:
- the cells of why/skycells.glsl = WhySkyMask.Cells.of (the CPU twin) within float rounding;
- overcast (owner's flats in kind: soft grey-white clouds with grey-blue bases and blue gaps over darker textured ground, a roof
  into the sky, a dark wire across it; the ceiling 1.2, no Bento: the flats' own sky levels): the sky found, not backlit; every
  pixel 2 whyLow px and more from the mask (the reach of the fused luma's B-spline) bit-identical (the ground: owner, «Нет, только
  небо»); the mid sky's linear Y x0.78 .. 0.93 (the reference's ~x0.86), its brightest clouds x0.9 and up; the clouds' OKLab
  chroma down (C1), the blue gaps' towards the reference's x0.87 (x0.81 .. 0.93); the clear sky's local contrast up; the wire and
  the roof out of the mask; P378 review: the sky on both sides of the wire (90 % of it in the mask, the mid sky above and below
  it within 3 %); «Контраст» 0.8 and 1.5: the mid sky down by about the same ratio (within 0.04 of 1.1's); the 50 MP mask (2 x 2
  whyLow px a pixel) the whyLow one at half size;
- backlit (a clipped sun in the sky, an indoor-dim subject; P378 review: a sun clipped without ultrashort frames at the N frames'
  ceiling 1.2 over an outdoor subject at 1/500, 1/1000, 1/2000 s, the wire dark in front of its glow; a branch under a sun; theta^-2
  aureoles K 4, 16, 40; a window behind a person, the room at EV 7 - 8.5): backlit, the sky's mean and median linear Y not lower
  (owner: «наш тонмапинг не должен затемнять небо в контровых сценах»), the ground bit-identical;
- P378 review: a front-lit face against the sky, a light facade and bluish snow in shade joined below it, a light tower beside it
  rising to the top edge out of the mask, their pixels as before; a sky over 60, 80, 85 and 90 % of the frame's height found and
  lowered;
- P378 review (P301): the viewfinder - why/combine POINT_FUSED 1 + SKY 1 on whyLow as its tone frame, its cells decided on the
  meter's quads (WhySkyMask.Cells.ofQuads): its mid sky within 0.05 of the photo's ratio, the clouds' chroma down, the ground as
  without the term bit for bit;
- an interior: no sky (no mask, no define);
- glslc: combine SKY 1 alone and with the other defines (and the viewfinder's POINT_FUSED / RAW_SOURCE forms), lpyr SKY 1, the
  cells, the mask and the Ultra HDR ratio.
--ab <whyLow.npy> <out dir> <EV of white> <rotation>: a before / after render of a whyLow (float32 H x W x 3, sensor layout) at its
own AE (the harness's WhyAe driver when present), PNGs turned upright and the sky's statistics.
Usage: check_why_sky.py [--report] | --ab ...
"""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile

import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders'
JAVA = ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/WhySkyMask.java'
sys.path.insert(0, str(ROOT / 'tools'))
import check_why_pyramid as cwp  # noqa: E402  (Gl, read_program, levels_for, guided)

REPORT = '--report' in sys.argv
LUMA = np.array([0.2126, 0.7152, 0.0722])
# the photo's defaults (WhyTone): the fusion without Bento headroom, the combine's uniforms
FUSION = {'expU': (0.125, 1.0, 0.354, 3.03), 'layerU': (0.8, 1.0, 0.1, 1.0), 'centerU': 0.6, 'smoothU': 0.25, 'gammaInvU': 1 / 2.2,
          'toeU': 0.05, 'monoU': 0.04, 'satLightU': (0.0, 0.0, 0.0, 0.0), 'satLevelU': (0.0, 0.0), 'warmU': (0.1, 0.25)}
GUIDED_MIX = (0.8, 1.6)


def shipped():
    """WhyTone's defaults of the sky term (skyTone: gain, macro, room x; SKY_GLOW), read from the source so the check renders them."""
    import re
    src = (ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/WhyTone.java').read_text(encoding='utf-8')
    tone = tuple(float(re.search(r'value\("%s", ([\d.]+)f\)' % k, src).group(1)) for k in ('sky_gain', 'sky_macro', 'sky_top'))
    return tone, float(re.search(r'SKY_GLOW = ([\d.]+)f;', src).group(1)), float(re.search(r'SKY_VIBRANCE = ([\d.]+)f;', src).group(1))


SKY_TONE, SKY_GLOW, SKY_VIBRANCE = shipped()

DRIVER = r'''
package com.particlesdevs.photoncamera.processing.opengl.postpipeline;
import java.nio.*;
import java.nio.file.*;
/** args: w h evOffset top ceiling low.f32 (RGB) mode0.f32 mode1.f32 out.f32 -> the decision on the GPU cells, the CPU cells' gap. */
public class SkyDrv {
    static float[] read(String p) throws Exception {
        FloatBuffer b = ByteBuffer.wrap(Files.readAllBytes(Paths.get(p))).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float[] v = new float[b.remaining()]; b.get(v); return v;
    }
    public static void main(String[] a) throws Exception {
        if (a[0].equals("quads")) {
            // quads mx my evOffset top ceiling quads.f32 out.f32: the viewfinder's decision (LiveWhyTone) on the meter's quads
            int mx = Integer.parseInt(a[1]), my = Integer.parseInt(a[2]);
            float ceiling = Float.parseFloat(a[5]);
            WhySkyMask.Cells c = WhySkyMask.Cells.ofQuads(read(a[6]), mx, my, WhySkyMask.CLIP_LEVEL * ceiling);
            WhySkyMask.Decision d = WhySkyMask.decide(c, new WhySkyMask.Scene(Float.parseFloat(a[3]), Integer.parseInt(a[4]), ceiling));
            System.out.println("gap 0");
            write(d, a[7]);
            return;
        }
        int w = Integer.parseInt(a[0]), h = Integer.parseInt(a[1]);
        float ev = Float.parseFloat(a[2]), ceiling = Float.parseFloat(a[4]);
        WhySkyMask.Cells gpu = WhySkyMask.Cells.fromGpu(w, h, read(a[6]), read(a[7]));
        WhySkyMask.Cells cpu = WhySkyMask.Cells.of(read(a[5]), w, h, 3, WhySkyMask.CLIP_LEVEL * ceiling);
        float gap = 0f;
        for (int i = 0; i < gpu.nx * gpu.ny; i++) {
            gap = Math.max(gap, Math.abs(gpu.l[i] - cpu.l[i]));
            gap = Math.max(gap, Math.abs(gpu.sd[i] - cpu.sd[i]));
            gap = Math.max(gap, Math.abs(gpu.tex[i] - cpu.tex[i]));
            gap = Math.max(gap, Math.abs(gpu.g[i] - cpu.g[i]) / Math.max(cpu.g[i], 1e-6f));
            gap = Math.max(gap, Math.abs(gpu.max[i] - cpu.max[i]) / Math.max(cpu.max[i], 1e-6f));
            gap = Math.max(gap, Math.abs(gpu.clip[i] - cpu.clip[i]));
        }
        WhySkyMask.Decision d = WhySkyMask.decide(gpu, new WhySkyMask.Scene(ev, Integer.parseInt(a[3]), ceiling));
        System.out.println("gap " + gap);
        write(d, a[8]);
    }
    static void write(WhySkyMask.Decision d, String out) throws Exception {
        System.out.println("log " + d.log);
        if (d.mask == null) { System.out.println("mask none"); return; }
        float[] tops = d.mask.tops();
        System.out.println("mask " + d.mask.nx + " " + d.mask.ny + " " + (d.mask.backlit ? 1 : 0) + " " + d.mask.coverage + " " + tops[0] + " " + tops[1] + " " + d.mask.keep);
        ByteBuffer o = ByteBuffer.allocate(4 * d.mask.cells.length).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : d.mask.cells) o.putFloat(v);
        Files.write(Paths.get(out), o.array());
    }
}
'''


class Java:
    def __init__(self):
        self.dir = tempfile.mkdtemp(prefix='check_why_sky_')
        src = Path(self.dir) / 'src'
        pkg = src / 'com/particlesdevs/photoncamera/processing/opengl/postpipeline'
        pkg.mkdir(parents=True)
        (pkg / 'SkyDrv.java').write_text(DRIVER)
        shutil.copy(JAVA, pkg / 'WhySkyMask.java')
        javac = shutil.which('javac')
        if not javac:
            raise SystemExit('javac not found (the decision is WhySkyMask.java itself)')
        subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(Path(self.dir) / 'classes'), str(pkg / 'SkyDrv.java'), str(pkg / 'WhySkyMask.java')],
                       check=True, capture_output=True)

    def decide(self, low, mode0, mode1, ev, top, ceiling):
        d = Path(self.dir)
        np.ascontiguousarray(low[..., :3], '<f4').tofile(d / 'low.f32')
        np.ascontiguousarray(mode0, '<f4').tofile(d / 'm0.f32')
        np.ascontiguousarray(mode1, '<f4').tofile(d / 'm1.f32')
        h, w = low.shape[:2]
        return self.parse(['java', '-cp', str(d / 'classes'), 'com.particlesdevs.photoncamera.processing.opengl.postpipeline.SkyDrv',
                           str(w), str(h), repr(float(ev)), str(top), repr(float(ceiling)), str(d / 'low.f32'), str(d / 'm0.f32'),
                           str(d / 'm1.f32'), str(d / 'out.f32')])

    def decide_quads(self, quads, mx, my, ev, top, ceiling):
        """The viewfinder's decision (LiveWhyTone.decideSky) on the meter's quads (my x mx x 12 floats)."""
        d = Path(self.dir)
        np.ascontiguousarray(quads, '<f4').tofile(d / 'q.f32')
        return self.parse(['java', '-cp', str(d / 'classes'), 'com.particlesdevs.photoncamera.processing.opengl.postpipeline.SkyDrv',
                           'quads', str(mx), str(my), repr(float(ev)), str(top), repr(float(ceiling)), str(d / 'q.f32'), str(d / 'out.f32')])

    def parse(self, cmd):
        d = Path(self.dir)
        r = subprocess.run(cmd, check=True, capture_output=True, text=True)
        out = {}
        for line in r.stdout.splitlines():
            k, _, v = line.partition(' ')
            out[k] = v
        res = {'gap': float(out['gap']), 'log': out['log'], 'mask': None}
        if out.get('mask', 'none') != 'none':
            nx, ny, backlit, cov, t0, t1, keep = out['mask'].split()
            res.update(mask=np.fromfile(d / 'out.f32', '<f4').reshape(int(ny), int(nx), 4), backlit=backlit == '1', coverage=float(cov),
                       tops=(float(t0), float(t1)), keep=float(keep))
        return res

    def close(self):
        shutil.rmtree(self.dir, ignore_errors=True)


class Sky:
    """The photo's WHY passes with and without the sky term (WhyStats' whyLow .. WhyCombine) on the host GPU."""

    def __init__(self):
        self.gl = cwp.Gl()
        self.java = Java()
        self.progs = {}

    def prog(self, name, defines=()):
        key = (name, tuple(defines))
        if key not in self.progs:
            src = cwp.read_program((SH / (name + '.glsl')).read_text(), list(defines))
            self.progs[key] = self.gl.ctx.program(vertex_shader=cwp.VS, fragment_shader=src)
        return self.progs[key]

    @staticmethod
    def clamped(t, linear):
        # GL_CLAMP_TO_EDGE as every texture of the pipeline (moderngl repeats by default: a bilinear tap at the last row wrapped
        # to the first)
        t.repeat_x = t.repeat_y = False
        if linear:
            t.filter = (moderngl.LINEAR, moderngl.LINEAR)
        return t

    def tex(self, a, dtype='f4', linear=False):
        return self.clamped(self.gl.tex(a, dtype), linear)

    def draw(self, name, defines, w, h, comps, dtype, textures, uniforms, linear=False):
        out = self.clamped(self.gl.target(w, h, comps, dtype), linear)
        self.gl.draw(self.prog(name, defines), out, textures, uniforms)
        return out

    def low(self, full, ae):
        """why/low.glsl: whyLow (factor 2, RGBA16F) and the bounded detail reference (DETAIL_REF 1, R32F)."""
        h, w = full.shape[:2]
        lw, lh = (w + 1) // 2, (h + 1) // 2
        t_full = self.tex(np.concatenate([full, np.ones(full.shape[:2] + (1,))], -1).astype(np.float32))
        ones = self.tex(np.ones((1, 1, 4), np.float32), linear=True)
        eye = tuple(np.eye(3).flatten())
        u = {'sensorToIntermediate': eye, 'intermediateToSRGB': eye, 'neutralPointU': (1, 1, 1), 'inScaleU': 1.0, 'factorU': 2,
             'detailClipU': ae}
        low = self.draw('why/low', (), lw, lh, 4, 'f2', [('InputBuffer', t_full), ('GainMap', ones)], u)
        ref = self.draw('why/low', (('DETAIL_REF', '1'),), lw, lh, 1, 'f4', [('InputBuffer', t_full), ('GainMap', ones)], u)
        return t_full, ones, low, ref

    def cells(self, low, ceiling):
        """why/skycells.glsl MODE 0 / 1 (WhySky.build's two read-backs)."""
        h, w = low.height, low.width
        cell = max(4, -(-max(w, h) // 64))
        nx, ny = -(-w // cell), -(-h // cell)
        out = []
        for mode in (0, 1):
            t = self.draw('why/skycells', (('MODE', str(mode)),), nx, ny, 4, 'f4', [('InputBuffer', low)],
                          {'cellU': cell, 'stepU': max(1, cell // 32), 'clipU': 0.9 * ceiling})
            out.append(self.gl.read(t))
        return out, cell

    def mask(self, low, cells, cell, box=1):
        """why/skymask.glsl (RG16F; WhySky.maskBox: 1 up to 12 MP, 2 at 50 MP)."""
        ny, nx = cells.shape[:2]
        t_cells = self.tex(cells.astype(np.float32), 'f2', linear=True)
        return self.draw('why/skymask', (), -(-low.width // box), -(-low.height // box), 2, 'f2', [('InputBuffer', low), ('SkyCells', t_cells)],
                         {'cellScaleU': (1.0 / (cell * nx), 1.0 / (cell * ny)), 'boxU': box}, linear=True)

    def fusion(self, low, ae, uni, sky=None, coarse=48):
        """WhyFusion.pyramid: lpyr MODE 0 .. 5 with the guided filter's share above white (P349); SKY 1 with a mask."""
        u = dict(uni, aeU=ae)
        low_np = self.gl.read(low)
        h, w = low_np.shape[:2]
        n = cwp.levels_for(w, h, coarse)
        sizes, L, W, F = [(w, h)], [None], [None], [None]
        for l in range(1, n + 1):
            pw, ph = sizes[-1]
            sizes.append(((pw + 1) // 2, (ph + 1) // 2))
            L.append(self.gl.target(*sizes[l], 4, 'f2'))
            W.append(self.gl.target(*sizes[l], 4, 'f2'))
            F.append(self.gl.target(*sizes[l], 1, 'f4'))
        self.gl.draw(self.prog('why/lpyr', (('MODE', '0'),)), L[1], [('InputBuffer', low)], u)
        self.gl.draw(self.prog('why/lpyr', (('MODE', '1'),)), W[1], [('InputBuffer', low)], u)
        for l in range(2, n + 1):
            for p in (L, W):
                self.gl.draw(self.prog('why/lpyr', (('MODE', '2'),)), p[l], [('InputBuffer', p[l - 1])], {})
        self.gl.draw(self.prog('why/lpyr', (('MODE', '3'),)), F[n], [('LevelL', L[n]), ('LevelW', W[n])], {})
        for l in range(n - 1, 0, -1):
            self.gl.draw(self.prog('why/lpyr', (('MODE', '4'),)), F[l], [('LevelL', L[l]), ('LevelW', W[l]), ('CoarseL', L[l + 1]),
                                                                         ('CoarseF', F[l + 1])], {})
        g = cwp.guided(self.gl, low_np, u)
        t_g = self.tex(np.repeat(g[..., None], 4, -1).astype(np.float32))
        defines = (('MODE', '5'),) + ((('SKY', '1'),) if sky is not None else ())
        textures = [('InputBuffer', low), ('CoarseL', L[1]), ('CoarseF', F[1]), ('GuidedF', t_g)]
        if sky is not None:
            textures.append(('WhySky', sky))
        fused = self.draw('why/lpyr', defines, w, h, 1, 'f4', textures, dict(u, guidedMixU=GUIDED_MIX, skyGuidedU=1.0))
        return fused

    def hlmask(self, low, ceiling):
        """WhyCombine's highlight landing mask (why/hlmask.glsl: the share of the ceiling, a gaussian of 1.2 % of the width)."""
        w, h = low.width, low.height
        a = self.draw('why/hlmask', (), w, h, 1, 'f2', [('InputBuffer', low)], {'invClipU': 1.0 / ceiling, 'dirU': (0, 0)}, linear=True)
        sigma = max(1.0, 0.012 * w)
        b = self.draw('why/hlmask', (), w, h, 1, 'f2', [('InputBuffer', a)], {'dirU': (1, 0), 'sigmaU': sigma}, linear=True)
        return self.draw('why/hlmask', (), w, h, 1, 'f2', [('InputBuffer', b)], {'dirU': (0, 1), 'sigmaU': sigma}, linear=True)

    def combine(self, t_full, ones, low, ref, fused, hl, ae, clip, sky=None, keep=1.0, hr=0.0, terms=(1.0, 1.0, 1.0, 1.0), tops=(0.0, 1.0),
                macro=1.1):
        h, w = t_full.height, t_full.width
        defines = [('HL_LANDING', '1')]
        if hr > clip:
            defines.append(('HEADROOM_WIDE', '1'))
        if sky is not None:
            defines.append(('SKY', '1'))
        eye = tuple(np.eye(3).flatten())
        u = {'sensorToIntermediate': eye, 'intermediateToSRGB': eye, 'neutralPointU': (1, 1, 1), 'inScaleU': 1.0, 'fU': 2, 'colourFU': 2,
             'signedColourU': 0, 'signedHueU': 1, 'aeU': ae, 'clipU': clip, 'toeU': 0.05, 'gammaInvU': 1 / 2.2, 'macroU': macro,
             'vibU': (0.0, 0.4, 0.2), 'vibHlU': 1.0, 'chromaDnU': 0.0, 'clarityU': 0.0, 'flatProtectU': 0.0, 'detailGainU': 1.0,
             'detailRefU': 1, 'sharpU': 0, 'fullInputU': 1, 'sharpToneU': 1.0, 'deltaChromaU': 1.0, 'filmToeU': 0.1,
             'agxAU': (2.7, 1.35, 1.6, 1.0), 'agxBU': (-8.5, 3.5, 0.3, 4.0), 'ditherU': 1, 'headroomU': 1, 'hlWhiteU': 0.15, 'hlSoftU': 0.7,
             'hrU': hr, 'hrKneeU': 0.5, 'hrRoomU': 0.4, 'hrDetailU': 0.35,
             'skyU': (terms[0] * keep,) + SKY_TONE, 'skyMU': (terms[1], terms[2], terms[3] * SKY_GLOW, SKY_VIBRANCE),
             'skyTopU': tops}
        textures = [('InputBuffer', t_full), ('GainMap', ones), ('WhyLow', low), ('WhyColour', low), ('WhyFused', fused),
                    ('WhyDetailRef', ref), ('WhyLumaS', low), ('WhyHl', hl)]
        if sky is not None:
            textures.append(('WhySky', sky))
        out = self.draw('why/combine', tuple(defines), w, h, 4, 'f4', textures, u)
        return self.gl.read(out)[..., :3]

    def run(self, full, ae, clip, ev, top, uni=FUSION, hr=0.0, macro=1.1):
        """Both renders of a full-size linear image: (without, with the sky term or None, the decision, whyLow, the mask)."""
        t_full, ones, low, ref = self.low(full, ae)
        ceiling = max(clip, hr)
        (m0, m1), cell = self.cells(low, ceiling)
        low_np = self.gl.read(low)
        d = self.java.decide(low_np, m0, m1, ev, top, ceiling)
        hl = self.hlmask(low, ceiling)
        before = self.combine(t_full, ones, low, ref, self.fusion(low, ae, uni), hl, ae, clip, hr=hr, macro=macro)
        if d['mask'] is None:
            return before, None, d, low_np, None
        mask = self.mask(low, d['mask'], cell)
        self.box2 = self.mask(low, d['mask'], cell, 2)
        after = self.combine(t_full, ones, low, ref, self.fusion(low, ae, uni, mask), hl, ae, clip, mask, d['keep'], hr=hr, tops=d['tops'],
                             macro=macro)
        return before, after, d, low_np, self.gl.read(mask)

    def viewfinder(self, full, ae, clip, ev, top):
        """P378 review: LiveWhyTone's tone (vf_why 1, P314's passes) on whyLow as the tone frame (one colour a quad), without and
        with the sky term: why/combine POINT_FUSED 1 (+ SKY 1, the cells of the decision on the meter's quads: 128 x 96 cells of
        2 x 2 adjacent quads at their centres, LiveWhyMeter) - (without, with or None, the decision, the cells' mask r up to the
        frame's size)."""
        import check_vf_fused as cvf
        _, _, low, _ = self.low(full, ae)
        lin = self.gl.read(low)[..., :3]
        h, w = lin.shape[:2]
        pitch = max(2, -(-max(w, h) // 128))
        mx, my = w // pitch, h // pitch
        off = (pitch - 2) // 2
        quads = np.zeros((my, mx, 4, 3), np.float32)
        for q in range(4):
            ys = np.arange(my) * pitch + off + (q >> 1)
            xs = np.arange(mx) * pitch + off + (q & 1)
            quads[:, :, q] = lin[ys][:, xs]
        d = self.java.decide_quads(quads, mx, my, ev, top, clip)
        t_lin = self.tex(np.concatenate([lin, np.ones((h, w, 1), np.float32)], -1).astype(np.float32), 'f2')
        curve = cvf.fused_curve(FUSION['expU'], FUSION['layerU'])
        t_curve = self.tex(curve.reshape(1, -1, 1).astype(np.float32))
        ones = self.tex(np.ones((1, 1, 4), np.float32), linear=True)

        def tone(cells=None):
            defines = [('POINT_FUSED', '1')] + ([('SKY', '1')] if cells is not None else [])
            u = {'sensorToIntermediate': tuple(np.eye(3).flatten()), 'intermediateToSRGB': tuple(np.eye(3).flatten()),
                 'neutralPointU': (1.0, 1.0, 1.0), 'inScaleU': 1.0, 'fU': 1, 'colourFU': 1, 'fusedCurveU': (cvf.LOG2_MIN, float(cvf.PER_EV)),
                 'signedColourU': 0, 'signedHueU': 1, 'aeU': ae, 'clipU': clip, 'toeU': 0.05, 'gammaInvU': 1 / 2.2, 'macroU': 1.1,
                 'vibU': (0.0, 0.4, 0.2), 'vibHlU': 1.0, 'chromaDnU': 0.0, 'clarityU': 0.0, 'flatProtectU': 0.0, 'detailGainU': 0.0,
                 'detailRefU': 0, 'sharpU': 0, 'sharpToneU': 1.0, 'deltaChromaU': 1.0, 'filmToeU': 0.1, 'agxAU': (2.7, 1.35, 1.6, 1.0),
                 'agxBU': (-8.5, 3.5, 0.3, 4.0), 'ditherU': 1, 'headroomU': 0, 'hlWhiteU': 0.15}
            textures = [(k, t_lin) for k in ('InputBuffer', 'WhyLow', 'WhyColour', 'WhyFused', 'WhyDetailRef', 'WhyLumaS')]
            textures += [('GainMap', ones), ('FusedCurve', t_curve)]
            if cells is not None:
                textures.append(('WhySky', cells))
                u.update(skyU=(d['keep'],) + SKY_TONE, skyMU=(1.0, 1.0, SKY_GLOW, SKY_VIBRANCE), skyTopU=d['tops'])
            out = self.draw('why/combine', tuple(defines), w, h, 4, 'f4', textures, u)
            return self.gl.read(out)[..., :3]
        before = tone()
        if d['mask'] is None:
            return before, None, d, None
        t_cells = self.tex(d['mask'].astype(np.float32), 'f2', linear=True)
        after = tone(t_cells)
        # the cells' r over the frame as the shader samples them (bilinear at the pixel centres)
        ny, nx = d['mask'].shape[:2]
        yy, xx = (np.arange(h) + 0.5) / h * ny - 0.5, (np.arange(w) + 0.5) / w * nx - 0.5
        r = d['mask'][..., 0]
        y0, x0 = np.clip(np.floor(yy).astype(int), 0, ny - 1), np.clip(np.floor(xx).astype(int), 0, nx - 1)
        y1, x1 = np.clip(y0 + 1, 0, ny - 1), np.clip(x0 + 1, 0, nx - 1)
        r_any = np.maximum(np.maximum(r[y0][:, x0], r[y0][:, x1]), np.maximum(r[y1][:, x0], r[y1][:, x1]))
        return before, after, d, r_any


# ------------------------------------------------------------------------------------------------ scenes

def smooth_noise(rng, h, w, scales, amps):
    out = np.zeros((h, w))
    for s, a in zip(scales, amps):
        n = rng.standard_normal((h // s + 3, w // s + 3))
        # bilinear upsampling of the coarse noise: a smooth field at scale s
        yy, xx = np.mgrid[0:h, 0:w] / float(s)
        y0, x0 = yy.astype(int), xx.astype(int)
        fy, fx = yy - y0, xx - x0
        f = (n[y0, x0] * (1 - fy) * (1 - fx) + n[y0 + 1, x0] * fy * (1 - fx) + n[y0, x0 + 1] * (1 - fy) * fx + n[y0 + 1, x0 + 1] * fy * fx)
        out += a * f / max(np.std(f), 1e-9)
    return out


def overcast(seed=3, h=576, w=768, sky_rows=0.42, ground=0.012, sun=False):
    """Full-size linear RGB: soft clouds (grey-white tops, grey-blue bases, a few blue gaps) over darker textured ground, a roof
    into the sky and a dark wire across it; regions for the measurements."""
    rng = np.random.default_rng(seed)
    yy, xx = np.mgrid[0:h, 0:w]
    horizon = int(sky_rows * h)
    sky = yy < horizon
    roof = (xx > 0.62 * w) & (xx < 0.78 * w) & (yy > horizon - 0.12 * h)
    field = smooth_noise(rng, h, w, (96, 32, 10), (0.6, 0.3, 0.12))
    level = 0.55 * np.exp2(0.45 * field)                              # clouds 0.3 .. 1.0 of white
    gap = field < -1.15                                               # the darkest hollows: blue sky between the clouds
    t = np.clip((level - 0.35) / 0.5, 0, 1)[..., None]
    tint = (1 - t) * np.array((0.95, 1.0, 1.07)) + t * np.array((1.02, 1.0, 0.985))  # grey-blue bases, a slightly warm top
    rgb = level[..., None] * tint
    rgb = np.where(gap[..., None], 0.33 * np.array((0.72, 0.9, 1.3)) * np.exp2(0.1 * field)[..., None], rgb)
    g = ground * np.exp(0.55 * rng.standard_normal((h, w)) + 0.4 * smooth_noise(rng, h, w, (24,), (1.0,)))
    ground_rgb = g[..., None] * np.array((1.06, 1.0, 0.9))
    rgb = np.where((sky & ~roof)[..., None], rgb, ground_rgb)
    wire = (np.abs(yy - (0.18 * h + 0.08 * xx)) < 2.0) & sky & ~roof
    rgb = np.where(wire[..., None], 0.02, rgb)
    regions = {'sky': sky & ~roof & ~wire, 'gap': gap & sky & ~roof & ~wire, 'roof': roof & sky,
               'wire': wire & (np.abs(yy - (0.18 * h + 0.08 * xx)) < 0.5), 'ground': ~sky}
    if sun:
        r = np.hypot(xx - 0.3 * w, yy - 0.15 * h)
        glow = 3.0 * np.exp(-np.maximum(r - 14, 0) / 10.0)
        # P378 review: the wire stays dark in front of the glow (it was painted over: the fixture hid a sun the rings missed)
        rgb = np.where((sky & ~roof & ~wire & (r < 70))[..., None], rgb + glow[..., None] * np.array((1.05, 1.0, 0.92)), rgb)
        rgb = np.where((r < 14)[..., None], 4.0, rgb)
        regions['sun'] = r < 90
    return rgb.astype(np.float32), regions


def aureole(k, seed=4, h=576, w=768, line=None):
    """P378 review: overcast(seed, ground 0.03) with a sun of a theta^-2 aureole of strength {k} (no glow of its own) over the
    clouds, its core r 14 px; {line}: a dark 3 px line (a branch) that far under the sun's centre."""
    full, reg = overcast(seed=seed, ground=0.03)
    yy, xx = np.mgrid[0:h, 0:w]
    r = np.hypot(xx - 0.3 * w, yy - 0.15 * h)
    sky = (reg['sky'] | reg['gap'] | reg['wire']) & ~reg['roof']
    aur = 0.9 * (14.0 / np.maximum(r, 14.0)) ** 2 * k
    rgb = np.where(sky[..., None], full + aur[..., None] * np.array((1.05, 1.0, 0.92)), full)
    rgb = np.where((r < 14)[..., None], 50.0, rgb)
    if line is not None:
        rgb = np.where(((np.abs(yy - (0.15 * h + line)) < 1.5) & (yy < int(0.42 * h)))[..., None], 0.02, rgb)
    return rgb.astype(np.float32), reg


def interior(seed=5, h=576, w=768):
    rng = np.random.default_rng(seed)
    yy, xx = np.mgrid[0:h, 0:w]
    rgb = 0.03 * np.exp(0.5 * rng.standard_normal((h, w)))[..., None] * np.array((1.1, 1.0, 0.8))
    ceiling = yy < 0.2 * h
    rgb = np.where(ceiling[..., None], 0.3 * np.array((1.1, 1.0, 0.8)), rgb)
    win = (xx > 0.55 * w) & (xx < 0.85 * w) & (yy > 0.3 * h) & (yy < 0.7 * h)
    rgb = np.where(win[..., None], 0.9, rgb)
    return rgb.astype(np.float32)


# ------------------------------------------------------------------------------------------------ measures

def display_to_linear(out):
    return np.power(np.clip(out, 0, 1), 2.2)


def oklab(lin):
    m1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929], [0.2119034982, 0.6806995451, 0.1073969566],
                   [0.0883024619, 0.2817188376, 0.6299787005]])
    m2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468], [1.9779984951, -2.4285922050, 0.4505937099],
                   [0.0259040371, 0.7827717662, -0.8086757660]])
    return np.cbrt(np.maximum(lin @ m1.T, 0)) @ m2.T


def up2(a, h, w):
    return np.repeat(np.repeat(a, 2, 0), 2, 1)[:h, :w]


def dilate(a):
    p = np.pad(a, 1, mode='edge')
    out = a.copy()
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            out = np.maximum(out, p[1 + dy:1 + dy + a.shape[0], 1 + dx:1 + dx + a.shape[1]])
    return out


def block_cv(y, sel, b=16):
    h, w = y.shape
    vals = []
    for j in range(0, h - b + 1, b):
        for i in range(0, w - b + 1, b):
            if sel[j:j + b, i:i + b].all():
                blk = y[j:j + b, i:i + b]
                vals.append(blk.std() / max(blk.mean(), 1e-9))
    return float(np.mean(vals)) if vals else float('nan'), len(vals)


# ------------------------------------------------------------------------------------------------ checks

def glslc_part(failures):
    sys.path.insert(0, str(ROOT / 'tools'))
    from check_colour_output import expand_imports, set_define, find_glslc
    glslc = find_glslc()
    if not glslc:
        print('  glslc not found: compile skipped')
        return
    cases = [('why/combine', {'SKY': '1'}), ('why/combine', {'SKY': '1', 'HL_LANDING': '1', 'HEADROOM_WIDE': '1', 'SUN_BLOOM': '1'}),
             ('why/combine', {'SKY': '1', 'P3_OUT': '1', 'COLOUR_LOOK': '1', 'TONE_OP': '1', 'FILM_LUT': '1'}),
             ('why/combine', {'SKY': '1', 'POINT_FUSED': '1'}), ('why/combine', {'SKY': '1', 'POINT_FUSED': '1', 'RAW_SOURCE': '1'}),
             ('why/combine', {'SKY': '1', 'POINT_FUSED': '1', 'COLOUR_LOOK': '1', 'FILM_LUT': '1', 'P3_OUT': '1'}),
             ('why/lpyr', {'SKY': '1', 'MODE': '5'}), ('why/skycells', {'MODE': '0'}), ('why/skycells', {'MODE': '1'}), ('why/skymask', {}),
             ('why/skyratio', {}),
             # window burst: the macro contrast's toe (WhyCombine and the viewfinder set MACRO_TOE 1) with and without the sky
             ('why/combine', {'MACRO_TOE': '1'}), ('why/combine', {'MACRO_TOE': '1', 'SKY': '1', 'POINT_FUSED': '1', 'RAW_SOURCE': '1'})]
    for name, defines in cases:
        src = (SH / (name + '.glsl')).read_text(encoding='utf-8')
        for k, v in defines.items():
            src = set_define(src, k, v)
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / 'x.frag'
            f.write_text('#version 310 es\n' + expand_imports(src), encoding='utf-8')
            r = subprocess.run([glslc, '--target-env=opengl', '-fshader-stage=fragment', '-std=310es', '-fauto-map-locations',
                                '-fauto-bind-uniforms', '-o', os.devnull, str(f)], capture_output=True, text=True)
            if r.returncode != 0:
                failures.append('glslc: %s %s does not compile:\n%s' % (name, defines, r.stderr))
            elif REPORT:
                print('  glslc %s %s: OK' % (name, defines))


def mid_ratio(before, after, sel):
    """The mid sky's linear Y ratio: the median of after over the median of before, on the 25th - 75th percentile of before."""
    yb, ya = display_to_linear(before) @ LUMA, display_to_linear(after) @ LUMA
    q25, q75 = np.percentile(yb[sel], [25, 75])
    mid = sel & (yb >= q25) & (yb <= q75)
    return float(np.median(ya[mid]) / np.median(yb[mid]))


def portrait(h=576, w=768):
    """P378 review: a front-lit light-skin face (~0.8 stop under the sky) against an overcast sky, dark hair, a dark jacket."""
    rng = np.random.default_rng(2)
    yy, xx = np.mgrid[0:h, 0:w]
    field = smooth_noise(rng, h, w, (96, 32, 10), (0.6, 0.3, 0.12))
    rgb = (0.45 * np.exp2(0.25 * field))[..., None] * np.array((0.95, 1.0, 1.07))
    g = 0.03 * np.exp(0.5 * rng.standard_normal((h, w)))
    rgb = np.where((yy >= int(0.40 * h))[..., None], g[..., None] * np.array((1.05, 1.0, 0.9)), rgb)
    dx, dy = (xx - 384) / 84.0, (yy - 150) / 96.0
    r = np.hypot(dx, dy)
    head = r < 1.0
    hair = head & (dy < -0.55)
    face = head & ~hair
    jacket = (yy > 240) & (np.abs(xx - 384) < 210)
    rgb = np.where(face[..., None], (0.26 * (1 - 0.15 * r * r))[..., None] * np.array((1.45, 0.95, 0.72)), rgb)
    rgb = np.where(hair[..., None], 0.01, rgb)
    rgb = np.where(jacket[..., None], (0.02 * np.exp(0.5 * rng.standard_normal((h, w))))[..., None], rgb)
    return rgb.astype(np.float32), {'face': face & (r < 0.8)}


def facade(h=576, w=768):
    """P378 review: an overcast sky over the top 25 %, a plain white facade (0.30, 0.6 stop under it) joined below it to 45 %,
    dark textured ground under that."""
    rng = np.random.default_rng(7)
    yy, xx = np.mgrid[0:h, 0:w]
    field = smooth_noise(rng, h, w, (96, 32, 10), (0.6, 0.3, 0.12))
    sky = (0.45 * np.exp2(0.25 * field))[..., None] * np.array((0.95, 1.0, 1.06))
    wall = (0.30 * (1 + 0.03 * rng.standard_normal((h, w))))[..., None] * np.array((1.0, 1.0, 0.98))
    g = (0.03 * np.exp(0.5 * rng.standard_normal((h, w))))[..., None] * np.ones(3)
    rgb = np.where((yy < 0.25 * h)[..., None], sky, np.where((yy < 0.45 * h)[..., None], wall, g))
    return rgb.astype(np.float32), {'wall': (yy >= 0.30 * h) & (yy < 0.43 * h)}


def window(room_ev, ev_off, h=576, w=768):
    """P378 review: a person in front of a big window - an overcast sky (EV 14) in its top, buildings 3 stops under it, the room and
    the person below at EV {room_ev}, no sun."""
    rng = np.random.default_rng(11)
    yy, xx = np.mgrid[0:h, 0:w]
    field = smooth_noise(rng, h, w, (96, 32, 10), (0.6, 0.3, 0.12))
    sky_lvl = 2 ** (14.0 - ev_off)
    sky = (sky_lvl * np.exp2(0.35 * field))[..., None] * np.array((0.95, 1.0, 1.06))
    room = 2 ** (room_ev - ev_off)
    g = (room * np.exp(0.5 * rng.standard_normal((h, w))))[..., None] * np.array((1.06, 1.0, 0.92))
    out = (sky_lvl / 8 * np.exp(0.4 * rng.standard_normal((h, w))))[..., None] * np.ones(3)
    rgb = np.where((yy < 0.30 * h)[..., None], sky, np.where((yy < 0.50 * h)[..., None], out, g))
    person = np.hypot((xx - 384) / 120.0, (yy - 330) / 200.0) < 1.0
    rgb = np.where(person[..., None], (0.6 * room * np.exp(0.3 * rng.standard_normal((h, w))))[..., None] * np.array((1.3, 1.0, 0.8)), rgb)
    return np.minimum(rgb, 1.2).astype(np.float32), {'sky': (yy < 0.30 * h) & ~person}


def snow(tint=(0.88, 1.0, 1.18), level=0.30, h=576, w=768):
    """P378 review: a smooth snow slope in shade (bluish, OKLab chroma ~0.024) joined below an overcast sky (as facade())."""
    rng = np.random.default_rng(7)
    yy, xx = np.mgrid[0:h, 0:w]
    field = smooth_noise(rng, h, w, (96, 32, 10), (0.6, 0.3, 0.12))
    sky = (0.45 * np.exp2(0.25 * field))[..., None] * np.array((0.95, 1.0, 1.06))
    slope = level * (1.0 - 0.25 * (yy - 0.25 * h) / (0.2 * h)) * (1 + 0.03 * rng.standard_normal((h, w)))
    g = (0.03 * np.exp(0.5 * rng.standard_normal((h, w))))[..., None] * np.ones(3)
    rgb = np.where((yy < 0.25 * h)[..., None], sky, np.where((yy < 0.45 * h)[..., None], slope[..., None] * np.array(tint), g))
    return rgb.astype(np.float32), {'snow': (yy >= 0.30 * h) & (yy < 0.43 * h)}


def tower(width, h=576, w=768):
    """P378 review: a smooth light-grey tower 0.8 stop under the sky rising to the top edge beside it, {width} px wide."""
    full, reg = overcast(seed=3)
    rng = np.random.default_rng(1)
    yy, xx = np.mgrid[0:h, 0:w]
    t = (xx >= 140) & (xx < 140 + width) & (yy < int(0.42 * h) + 10)
    lvl = 0.55 * 2 ** -0.8
    rgb = np.where(t[..., None], (lvl * (1 + 0.02 * rng.standard_normal((h, w))))[..., None] * np.array((1.0, 1.0, 0.98)), full)
    return rgb.astype(np.float32), {'tower': t & (xx >= 143) & (xx < 137 + width) & (yy < int(0.40 * h))}


def scene_part(sky, failures):
    ae, clip = 2.0, 1.2
    # ---- overcast: the darkening target (ISO 100, 1/3100 s, f/1.8 - the owner's flats)
    full, reg = overcast()
    h, w = full.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w]
    ev = float(np.log2(1.8 ** 2 * 3100) + np.log2(9.6))
    before, after, d, low, mask = sky.run(full, ae, clip, ev, 0)
    print('overcast: %s (cells GPU / CPU within %.1e)' % (d['log'], d['gap']))
    if d['gap'] > 2e-3:
        failures.append('why/skycells.glsl differs from WhySkyMask.Cells.of by %.2e' % d['gap'])
    if after is None:
        failures.append('overcast: no sky found (%s)' % d['log'])
        return
    if d['backlit']:
        failures.append('overcast: backlit (%s)' % d['log'])
    m_any = up2(dilate(dilate(np.maximum(mask[..., 0], mask[..., 1]))), h, w)
    zero = m_any == 0
    same = np.all(before == after, -1)
    if not same[zero].all():
        n = int((~same & zero).sum())
        failures.append('overcast: %d pixels outside the mask changed (the ground must stay bit for bit)' % n)
    if not zero[reg['ground'] & (yy > 0.5 * h)].all():
        failures.append('overcast: the mask reaches the ground')
    yb, ya = display_to_linear(before) @ LUMA, display_to_linear(after) @ LUMA
    full_sky = reg['sky'] & ~reg['gap'] & (up2(mask[..., 0], h, w) > 0.99)
    ratio = mid_ratio(before, after, full_sky)
    top = full_sky & (yb >= np.percentile(yb[full_sky], 97))
    top_ratio = float(np.median(ya[top]) / np.median(yb[top]))
    lab_b, lab_a = oklab(display_to_linear(before)), oklab(display_to_linear(after))
    cb, ca = np.hypot(lab_b[..., 1], lab_b[..., 2]), np.hypot(lab_a[..., 1], lab_a[..., 2])
    clouds = full_sky & (cb < 0.03)
    gaps = reg['gap'] & (up2(mask[..., 0], h, w) > 0.99)
    c_cloud, c_gap = float(ca[clouds].mean() / cb[clouds].mean()), float(ca[gaps].mean() / max(cb[gaps].mean(), 1e-9))
    clear = up2(mask[..., 1], h, w) > 0.99
    cv_b, n_b = block_cv(yb, clear & reg['sky'])
    cv_a, _ = block_cv(ya, clear & reg['sky'])
    tops = clear & reg['sky'] & (yb >= np.percentile(yb[clear & reg['sky']], 75))
    cvt_b, _ = block_cv(yb, dilate(tops.astype(np.uint8)).astype(bool) & tops, 8)
    cvt_a, _ = block_cv(ya, dilate(tops.astype(np.uint8)).astype(bool) & tops, 8)
    wire_mask = float(up2(mask[..., 0], h, w)[reg['wire']].max()) if reg['wire'].any() else 0.0
    roof_in = up2(mask[..., 0], h, w)[reg['roof'] & (yy > 0.36 * h)]
    print('  mid sky linear Y x%.3f (target ~x0.86: the reference app on the owner\'s flats), top x%.3f; cloud chroma x%.2f, blue gaps x%.2f '
          '(target x0.87); clear-sky local contrast (16 px cv) x%.2f over %d blocks, tops (8 px) x%.2f; wire mask %.2f, roof mask %.2f'
          % (ratio, top_ratio, c_cloud, c_gap, cv_a / cv_b, n_b, cvt_a / cvt_b if cvt_b == cvt_b else float('nan'), wire_mask,
             float(roof_in.max()) if roof_in.size else 0.0))
    # P378 review: the sky on both sides of the wire (the flood stopped at it: x0.84 above, x1.00 below)
    wire_line = 0.18 * h + 0.08 * xx
    away = reg['sky'] & ~reg['gap'] & (np.abs(yy - wire_line) > 8) & ~dilate(reg['roof'].astype(np.uint8)).astype(bool)
    above, below = away & (yy < wire_line), away & (yy > wire_line)
    in_mask = float((up2(mask[..., 0], h, w)[away] > 0.5).mean())
    # the same light on both sides (the term fades out over the clouds' tops: a ratio of its own at each level)
    lo, hi = np.percentile(yb[away], [30, 60])
    band = (yb >= lo) & (yb <= hi)
    ratio_px = ya / np.maximum(yb, 1e-9)
    r_above, r_below = float(np.median(ratio_px[above & band])), float(np.median(ratio_px[below & band]))
    print('  across the wire: %.1f %% of the sky in the mask; the same light (the 30th - 60th percentile of the sky) x%.3f above it, x%.3f below it'
          % (100 * in_mask, r_above, r_below))
    if in_mask < 0.9:
        failures.append('overcast: only %.0f %% of the sky in the mask (the wire cuts it)' % (100 * in_mask))
    if abs(r_above / r_below - 1) > 0.03:
        failures.append('overcast: the sky above the wire x%.3f, below it x%.3f (a seam along the wire)' % (r_above, r_below))
    # the 50 MP mask (2 x 2 whyLow px a pixel) is the 12 MP one at half size
    m2 = sky.gl.read(sky.box2)[..., :2]
    m1 = mask[: 2 * m2.shape[0], : 2 * m2.shape[1], :2]
    m1 = m1.reshape(m2.shape[0], 2, m2.shape[1], 2, 2).mean((1, 3))
    gap2 = float(np.abs(m2 - m1).mean())
    print('  the reduced mask (box 2) against the whyLow one averaged: mean |diff| %.3f' % gap2)
    if gap2 > 0.03:
        failures.append('overcast: the reduced mask (box 2) departs from the whyLow mask by %.3f' % gap2)
    if not 0.78 <= ratio <= 0.93:
        failures.append('overcast: the mid sky x%.3f, not ~x0.86' % ratio)
    if top_ratio < 0.9:
        failures.append('overcast: the brightest clouds x%.3f (the top fades towards x1)' % top_ratio)
    if c_cloud > 0.8:
        failures.append('overcast: the clouds\' chroma x%.2f (C1: neutral clouds)' % c_cloud)
    if not 0.81 <= c_gap <= 0.93:
        failures.append('overcast: the blue gaps\' chroma x%.2f (the reference\'s x0.87 of ours)' % c_gap)
    if not cv_a > 1.03 * cv_b:
        failures.append('overcast: the clear sky\'s local contrast x%.2f (M1: modelled clouds)' % (cv_a / cv_b))
    if wire_mask > 0.35:
        failures.append('overcast: the dark wire is in the mask (%.2f)' % wire_mask)
    if roof_in.size and roof_in.max() > 0.05:
        failures.append('overcast: the roof is in the mask (%.2f)' % float(roof_in.max()))
    # P378 review: «Контраст» 0.8 and 1.5 - the sky term's macro contrast a share of it, the mid sky down by about the same ratio
    for macro in (0.8, 1.5):
        b2, a2, d2, _, m2 = sky.run(full, ae, clip, ev, 0, macro=macro)
        r2 = mid_ratio(b2, a2, reg['sky'] & ~reg['gap'] & (up2(m2[..., 0], h, w) > 0.99))
        print('  «Контраст» %.1f: mid sky x%.3f (1.1: x%.3f)' % (macro, r2, ratio))
        if not (r2 < 0.95 and abs(r2 - ratio) <= 0.04):
            failures.append('overcast at «Контраст» %.1f: the mid sky x%.3f against x%.3f at 1.1' % (macro, r2, ratio))
    # P378 review (P301): the viewfinder's sky term against the photo's
    vb, va, vd, vr = sky.viewfinder(full, ae, clip, ev, 0)
    print('viewfinder (the meter\'s quads): %s' % vd['log'])
    if va is None:
        failures.append('viewfinder: no sky found (%s)' % vd['log'])
    else:
        lh, lw = vb.shape[:2]
        sky_low = reg['sky'][::2, ::2][:lh, :lw] & ~reg['gap'][::2, ::2][:lh, :lw]
        vsel = sky_low & (vr > 0.99)
        v_ratio = mid_ratio(vb, va, vsel)
        lab_vb, lab_va = oklab(display_to_linear(vb)), oklab(display_to_linear(va))
        vcb, vca = np.hypot(lab_vb[..., 1], lab_vb[..., 2]), np.hypot(lab_va[..., 1], lab_va[..., 2])
        vclouds = vsel & (vcb < 0.03)
        v_cloud = float(vca[vclouds].mean() / vcb[vclouds].mean())
        v_zero = dilate(dilate((vr > 0).astype(np.uint8))) == 0
        v_same = bool(np.all(vb == va, -1)[v_zero].all())
        print('  mid sky linear Y x%.3f (the photo x%.3f), cloud chroma x%.2f (the photo x%.2f); the ground as without the term: %s'
              % (v_ratio, ratio, v_cloud, c_cloud, v_same))
        if vd['backlit']:
            failures.append('viewfinder: backlit (%s)' % vd['log'])
        if abs(v_ratio - ratio) > 0.05:
            failures.append('viewfinder: the mid sky x%.3f against the photo\'s x%.3f' % (v_ratio, ratio))
        if v_cloud > 0.8:
            failures.append('viewfinder: the clouds\' chroma x%.2f' % v_cloud)
        if not v_same:
            failures.append('viewfinder: the ground changed')

    # ---- backlit: a clipped sun in the sky over an indoor-dim subject (ISO 100, 1/2000 s, f/1.8); P378 review: and a sun clipped
    # at the N frames' ceiling without ultrashort frames (1.2) over an outdoor subject at 1/500 - 1/2000 s
    cases = [('indoor-dim subject, Bento ceiling 4', overcast(seed=4, ground=0.0006, sun=True), 4.0, 2000)]
    for t in (500, 1000, 2000):
        f2, r2 = overcast(seed=4, ground=0.03, sun=True)
        cases.append(('outdoor subject, no Bento (ceiling 1.2), the wire dark in front of the glow, 1/%d s' % t,
                      (np.minimum(f2, 1.2).astype(np.float32), r2), 1.2, t))
    # P378 review: a branch under the sun, aureoles from weak to strong (clipped blobs of 4 .. 15 % of the frame)
    f2, r2 = aureole(8, line=40)
    cases.append(('a branch 40 px under a sun, 1/1000 s', (np.minimum(f2, 1.2).astype(np.float32), r2), 1.2, 1000))
    for k in (4, 16, 40):
        f2, r2 = aureole(k)
        cases.append(('a theta^-2 aureole K %d, 1/2000 s' % k, (np.minimum(f2, 1.2).astype(np.float32), r2), 1.2, 2000))
    # P378 review: a window behind a person - the room at EV 7 .. 8.5 is backlit (SHADE_EV's ramp: in full under EV 9, none from 10)
    for room_ev in (7.0, 8.0, 8.5):
        ev_w = float(np.log2(1.8 ** 2 * 2000) + np.log2(9.6))
        cases.append(('a window behind a person, the room at EV %.1f' % room_ev, window(room_ev, ev_w), 1.2, 2000))
    for name, (full, reg), ceiling, t in cases:
        ev = float(np.log2(1.8 ** 2 * t) + np.log2(9.6))
        before, after, d, low, mask = sky.run(full, ae, ceiling, ev, 0)
        print('backlit, %s: %s' % (name, d['log']))
        if after is None:
            failures.append('backlit, %s: no sky found (%s)' % (name, d['log']))
            continue
        if not d['backlit']:
            failures.append('backlit, %s: not backlit (%s)' % (name, d['log']))
        m_any = up2(dilate(dilate(np.maximum(mask[..., 0], mask[..., 1]))), h, w)
        zero = m_any == 0
        same = np.all(before == after, -1)
        if not same[zero].all():
            failures.append('backlit, %s: %d pixels outside the mask changed' % (name, int((~same & zero).sum())))
        yb, ya = display_to_linear(before) @ LUMA, display_to_linear(after) @ LUMA
        in_sky = reg['sky'] & (up2(mask[..., 0], h, w) > 0.5)
        mean_r = float(ya[in_sky].mean() / yb[in_sky].mean())
        med_r = float(np.median(ya[in_sky]) / np.median(yb[in_sky]))
        print('  the backlit sky\'s linear Y: mean x%.4f, median x%.4f' % (mean_r, med_r))
        if mean_r < 0.995 or med_r < 0.995:
            failures.append('backlit, %s: the sky went darker (mean x%.4f, median x%.4f)' % (name, mean_r, med_r))

    # ---- P378 review: light non-sky surfaces against the sky stay out (ISO 100, 1/2000 s)
    ev = float(np.log2(1.8 ** 2 * 2000) + np.log2(9.6))
    for name, (full, reg), key in (('a front-lit face', portrait(), 'face'), ('a white facade joined below the sky', facade(), 'wall')):
        before, after, d, low, mask = sky.run(full, ae, clip, ev, 0)
        print('%s: %s' % (name, d['log']))
        if after is None:
            failures.append('%s: no sky found beside it (%s)' % (name, d['log']))
            continue
        m = up2(mask[..., 0], h, w)[reg[key]]
        yb, ya = display_to_linear(before) @ LUMA, display_to_linear(after) @ LUMA
        r = float(np.median(ya[reg[key]] / np.maximum(yb[reg[key]], 1e-9)))
        print('  its mask max %.3f, its linear Y x%.4f' % (float(m.max()), r))
        if m.max() > 0.02 or abs(r - 1) > 0.005:
            failures.append('%s takes the sky term (mask max %.3f, x%.4f)' % (name, float(m.max()), r))

    # ---- P378 review: bluish snow in shade joined below the sky, a light tower beside it rising to the top edge (3 and 5 cells)
    for name, (full, reg), key in (('bluish snow in shade joined below the sky', snow(), 'snow'),
                                   ('a light tower 36 px wide beside the sky', tower(36), 'tower'),
                                   ('a light tower 60 px wide beside the sky', tower(60), 'tower')):
        before, after, d, low, mask = sky.run(full, ae, clip, ev, 0)
        print('%s: %s' % (name, d['log']))
        if after is None:
            continue
        yb, ya = display_to_linear(before) @ LUMA, display_to_linear(after) @ LUMA
        r = float(np.median(ya[reg[key]] / np.maximum(yb[reg[key]], 1e-9)))
        print('  its linear Y x%.4f' % r)
        if abs(r - 1) > 0.005:
            failures.append('%s takes the sky term (x%.4f)' % (name, r))

    # ---- P378 review: a sky over 60 - 90 % of the frame (the far half's median was the sky itself: no term from ~78 % up)
    ev = float(np.log2(1.8 ** 2 * 3100) + np.log2(9.6))
    for rows in (0.6, 0.8, 0.85, 0.9):
        full, reg = overcast(seed=5, sky_rows=rows)
        before, after, d, low, mask = sky.run(full, ae, clip, ev, 0)
        print('a sky over %.0f %% of the frame\'s height: %s' % (100 * rows, d['log']))
        if after is None:
            failures.append('a sky over %.0f %% of the frame\'s height: none found (%s)' % (100 * rows, d['log']))
            continue
        r = mid_ratio(before, after, reg['sky'] & ~reg['gap'] & (up2(mask[..., 0], h, w) > 0.99))
        print('  mid sky linear Y x%.3f' % r)
        if not r < 0.93:
            failures.append('a sky over %.0f %% of the frame\'s height: the mid sky x%.3f (not lowered)' % (100 * rows, r))

    # ---- an interior: no sky (ISO 1600, 1/50 s, f/1.8)
    ev = float(np.log2(1.8 ** 2 * 50) - np.log2(16) + np.log2(9.6))
    before, after, d, _, _ = sky.run(interior(), 1.5, 1.2, ev, 0)
    print('interior: %s' % d['log'])
    if after is not None:
        failures.append('interior: a sky found (%s)' % d['log'])


def ab(path, out_dir, ev, rotation):
    """--ab: before / after of a whyLow (sensor layout) taken as the full-size image, at the harness's AE when present."""
    from PIL import Image
    low = np.load(path).astype(np.float32)
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    sky = Sky()
    # the AE of the whyLow of this full-size image (the harness's WhyAe driver), else a fixed one
    ae, clip, hr, em = 1.0, float(min(max(low.max(), 1.0), 4.0)), 0.0, FUSION['expU']
    drv = Path(os.environ.get('SKY_AE_CLASSES', '')) if os.environ.get('SKY_AE_CLASSES') else None
    t_full, ones, low_t, ref = sky.low(low, 1.0)
    lw = sky.gl.read(low_t)
    if drv and drv.is_dir():
        with tempfile.NamedTemporaryFile(suffix='.f32', delete=False) as f:
            f.write(np.ascontiguousarray(lw, '<f4').tobytes())
        r = subprocess.run(['java', '-cp', str(drv), 'com.particlesdevs.photoncamera.processing.opengl.postpipeline.AeDrv', f.name,
                            str(lw.shape[1]), str(lw.shape[0]), '100', '6400', 'bento'], capture_output=True, text=True)
        os.unlink(f.name)
        v = [float(t) for t in r.stdout.split()]
        ae, clip, em, hr = v[0], v[3], tuple(v[5:9]), v[9] if len(v) > 9 else 0.0
        print('AE (WhyAe driver): ' + (r.stderr.strip().splitlines()[-1] if r.stderr.strip() else str(v)))
    uni = dict(FUSION, expU=em)
    if hr > clip:
        # WhyFusion with Bento headroom: the headroom weights and P310's saturated-light ramp
        uni.update(centerU=0.75, layerU=(0.4, 1.0, 0.1, 1.0), satLightU=(0.6, 0.8, 0.15, 0.95), satLevelU=(0.25, 0.6))
    top = {0: 0, 90: 1, 180: 2, 270: 3}[rotation]
    before, after, d, lowr, mask = sky.run(low, ae, clip, ev, top, uni, hr)
    print('decision: %s' % d['log'])
    k = {0: 0, 90: -1, 180: 2, 270: 1}[rotation]

    def save(a, name):
        img = (np.clip(np.rot90(a, k), 0, 1) * 255 + 0.5).astype(np.uint8)
        Image.fromarray(img).save(out_dir / name)
        return img
    b = save(before, 'before.png')
    if after is None:
        print('no sky: nothing changes')
        sky.gl.ctx.release()
        sky.java.close()
        return
    a = save(after, 'after.png')
    Image.fromarray(np.concatenate([b, a], 1)).save(out_dir / 'before_after.png')
    m = np.rot90(np.concatenate([mask[..., :2], np.zeros(mask.shape[:2] + (1,), np.float32)], -1), k)   # r the sky, g the clear sky
    Image.fromarray((np.clip(m, 0, 1) * 255).astype(np.uint8)).save(out_dir / 'mask.png')
    h, w = before.shape[:2]
    yb, ya = display_to_linear(before) @ LUMA, display_to_linear(after) @ LUMA
    in_sky = up2(mask[..., 0], h, w) > 0.5
    zero = up2(dilate(dilate(np.maximum(mask[..., 0], mask[..., 1]))), h, w) == 0
    print('sky %.1f %% of the frame, backlit %s: its linear Y mean x%.4f, median x%.4f, p10 x%.4f, p90 x%.4f; unchanged outside the mask: %s'
          % (100 * in_sky.mean(), d['backlit'], ya[in_sky].mean() / yb[in_sky].mean(), np.median(ya[in_sky]) / np.median(yb[in_sky]),
             np.percentile(ya[in_sky], 10) / np.percentile(yb[in_sky], 10), np.percentile(ya[in_sky], 90) / np.percentile(yb[in_sky], 90),
             bool(np.all(before == after, -1)[zero].all())))
    sky.gl.ctx.release()
    sky.java.close()


def main():
    if '--ab' in sys.argv:
        i = sys.argv.index('--ab')
        ab(sys.argv[i + 1], sys.argv[i + 2], float(sys.argv[i + 3]), int(sys.argv[i + 4]))
        return
    failures = []
    glslc_part(failures)
    sky = Sky()
    try:
        scene_part(sky, failures)
    finally:
        sky.java.close()
        sky.gl.ctx.release()
    if failures:
        for f in failures:
            print('FAIL: ' + f)
        sys.exit(1)
    print('PASS: the sky term changes the sky alone (the ground bit for bit), lowers the mid sky towards the reference at every «Контраст», '
          'neutralises the clouds, brings the blue gaps to the reference and models the clouds, across a wire; the viewfinder as the photo; '
          'a backlit sky keeps its level (a sun without ultrashort frames too, behind a wire or a branch, weak to strong aureoles, a window '
          'behind a person); a face, a joined facade, bluish snow and a tower stay out; a sky over 60 - 90 % of the frame is lowered; an '
          'interior has no sky')


if __name__ == '__main__':
    main()
