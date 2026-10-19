"""export.py <out dir>: the spektrafilm data our port needs (CC BY-SA 4.0 profiles, Andrea Volpato,
https://github.com/andreavolpato/spektrafilm), one little-endian container per stock plus shared.bin and index.json.
Container: magic 'SFD1', count u32, then per array: name len u8, name, dtype u8 (0 f32, 1 f16), ndim u8, dims u32..., data."""
import sys, os, json, struct
sys.path.insert(0, r'C:/Users/MECHREVO/Downloads/x200u/SCAMERA-PC/evidence/spektrafilm/src/src')
sys.path.append(r'C:/Users/MECHREVO/AppData/Local/Temp/claude/C--Users-MECHREVO-Downloads-x200u/b81dc619-c8a9-41ca-bbae-ac070a033320/scratchpad/sf/stubs')
import numpy as np
import colour
from spektrafilm.runtime.api import init_params, digest_params
from spektrafilm.runtime.pipeline import SimulationPipeline
from spektrafilm.model.couplers import compute_dir_couplers_matrix
from spektrafilm.model.illuminants import standard_illuminant
from spektrafilm.model.color_filters import custom_dichroic_filters
from spektrafilm.utils.spectral_upsampling import _illuminant_to_xy
from spektrafilm.utils.morph_curves import apply_print_curves_morph
from spektrafilm.utils.io import read_neutral_print_filters

OUT = sys.argv[1]
SCAN_N = int(sys.argv[2]) if len(sys.argv) > 2 else 33
os.makedirs(OUT, exist_ok=True)
PROF = r'C:/Users/MECHREVO/Downloads/x200u/SCAMERA-PC/evidence/spektrafilm/src/src/spektrafilm/data/profiles'
stocks = sorted(f[:-5] for f in os.listdir(PROF) if f.endswith('.json'))


def write(path, arrays):
    with open(path, 'wb') as f:
        f.write(b'SFD1'); f.write(struct.pack('<I', len(arrays)))
        for name, a, dt in arrays:
            a = np.ascontiguousarray(np.asarray(a, np.float16 if dt == 1 else np.float32))
            nb = name.encode()
            f.write(struct.pack('<B', len(nb))); f.write(nb)
            f.write(struct.pack('<BB', dt, a.ndim)); f.write(struct.pack('<%dI' % a.ndim, *a.shape))
            f.write(a.astype('<f2' if dt == 1 else '<f4').tobytes())


def pipeline(film, prnt, scan_film=False):
    p = init_params(film_profile=film, print_profile=prnt)
    p.debug.lut_mode = True
    p.io.input_color_space = 'sRGB'; p.io.input_cctf_decoding = False
    p.io.scan_film = scan_film
    p = digest_params(p)
    return p, SimulationPipeline(p)


def scan_lut(pl, dmin, dmax):
    g = [np.linspace(dmin[c], dmax[c], SCAN_N) for c in range(3)]
    # index order [b][g][r] like our 3D LUTs: r fastest
    B, G, R = np.meshgrid(g[2], g[1], g[0], indexing='ij')
    cmy = np.stack([R, G, B], -1).reshape(1, -1, 3)
    rgb = pl._scanning_stage._density_to_rgb(cmy, use_lut=False)
    rgb = pl._scanning_stage._apply_cctf_encoding(rgb)
    return np.asarray(rgb, np.float64).reshape(SCAN_N, SCAN_N, SCAN_N, 3)


index = {'films': [], 'prints': []}
neutral = read_neutral_print_filters()
films = [s for s in stocks if __import__('spektrafilm.profiles.io', fromlist=['x']).load_profile(s).info.support == 'film'
         and __import__('spektrafilm.profiles.io', fromlist=['x']).load_profile(s).info.stage != 'print'] if False else None
from spektrafilm.profiles.io import load_profile
profs = {s: load_profile(s) for s in stocks}
prints = [s for s in stocks if profs[s].info.support == 'paper' or s in ('kodak_2383', 'kodak_2393')]
films = [s for s in stocks if s not in prints]
print('films', films); print('prints', prints)

for s in films:
    pr = profs[s]
    tp = pr.info.target_print if pr.info.target_print in prints else 'kodak_portra_endura'
    positive = pr.info.type == 'positive'
    p, pl = pipeline(s, tp, scan_film=positive)
    sens = np.nan_to_num(10 ** np.asarray(pr.data.log_sensitivity))
    tc = pl._lut_service.get_filming_tc_lut(sens)
    xy = _illuminant_to_xy(pr.info.reference_illuminant)
    M = colour.RGB_to_XYZ(np.eye(3), colourspace='sRGB', apply_cctf_decoding=False, illuminant=xy,
                          chromatic_adaptation_transform='CAT16').T
    dirm = compute_dir_couplers_matrix(p.film_render.dir_couplers) * p.film_render.dir_couplers.amount
    dc = np.asarray(pr.data.density_curves, float)
    arrays = [('M', M, 0), ('tc', tc, 1), ('le', pr.data.log_exposure, 0), ('dc', dc, 0),
              ('cd', pr.data.channel_density, 0), ('bd', pr.data.base_density, 0), ('dir', dirm, 0),
              ('dmin', p.film_render.grain.density_min, 0), ('dcl', pr.data.density_curves_layers, 0)]
    meta = {'id': s, 'name': pr.info.name, 'type': pr.info.type, 'use': pr.info.use, 'antihalation': pr.info.antihalation,
            'target_print': tp, 'reference_illuminant': pr.info.reference_illuminant,
            'halation_sigma_um': list(p.film_render.halation.halation_first_sigma_um) if False else None}
    p2 = digest_params(init_params(film_profile=s, print_profile=tp))  # the halation preset without lut_mode
    meta['halation_sigma_um'] = list(map(float, p2.film_render.halation.halation_first_sigma_um))
    meta['halation_strength'] = list(map(float, p2.film_render.halation.halation_strength))
    if positive:
        dmin = -np.asarray(p.film_render.grain.density_min, float)
        dmax = np.nanmax(dc, axis=0)
        arrays += [('scan', scan_lut(pl, dmin, dmax), 1), ('scanmin', dmin, 0), ('scanmax', dmax, 0)]
    write(os.path.join(OUT, s + '.bin'), arrays)
    index['films'].append(meta)
    print('film', s, tc.shape, positive)

for s in prints:
    pr = profs[s]
    p, pl = pipeline('kodak_portra_400', s)
    sens = np.nan_to_num(10 ** np.asarray(pr.data.log_sensitivity))
    le = np.asarray(pr.data.log_exposure, float)
    dcm = apply_print_curves_morph(le, pr.data.density_curves_model, p.print_render.density_curves_morph, profile_type=pr.info.type)
    dc = np.asarray(pr.data.density_curves, float)
    dmin, dmax = np.nanmin(dc, axis=0), np.nanmax(dc, axis=0)
    arrays = [('sens', sens, 0), ('le', le, 0), ('dcm', dcm, 0), ('cd', pr.data.channel_density, 0), ('bd', pr.data.base_density, 0),
              ('scan', scan_lut(pl, dmin, dmax), 1), ('scanmin', dmin, 0), ('scanmax', dmax, 0)]
    write(os.path.join(OUT, s + '.bin'), arrays)
    filt = neutral.get(s, {}).get('TH-KG3', {})
    index['prints'].append({'id': s, 'name': pr.info.name, 'use': pr.info.use, 'viewing_illuminant': pr.info.viewing_illuminant,
                            'neutral_filters': {k: list(map(float, v)) for k, v in filt.items()}})
    print('print', s, len(filt))

d0 = digest_params(init_params())
neu = np.zeros((len(prints), len(films), 3))
for i, ps in enumerate(prints):
    for j, fs in enumerate(films):
        v = neutral.get(ps, {}).get('TH-KG3', {}).get(fs)
        neu[i, j] = v if v is not None else (d0.enlarger.c_filter_neutral, d0.enlarger.m_filter_neutral, d0.enlarger.y_filter_neutral)
write(os.path.join(OUT, 'shared.bin'), [('thkg3', standard_illuminant('TH-KG3'), 0), ('dichroic', custom_dichroic_filters.filters, 0), ('neutral', neu, 0)])
d = digest_params(init_params())
index['defaults'] = {'y_filter_neutral': d.enlarger.y_filter_neutral, 'm_filter_neutral': d.enlarger.m_filter_neutral,
                     'c_filter_neutral': d.enlarger.c_filter_neutral, 'scan_n': SCAN_N}
json.dump(index, open(os.path.join(OUT, 'index.json'), 'w'), indent=1)
print('done')
