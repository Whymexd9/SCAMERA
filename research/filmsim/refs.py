"""refs.py <spektrafilm src dir> <out.json> [out.txt]: spektrafilm outputs for test colours (linear sRGB in, sRGB-encoded out),
per-pixel regime (spatial and stochastic effects off). The optional out.txt is app/src/test/resources/film/refs.txt (FilmSpectralTest):
the colour count, the colours, the case count, then per case a header "film paper scan ev print_exposure m_shift y_shift [dir]" and
its outputs. P380: the cases with 'dir' set spektrafilm's DIR couplers amount (dir_couplers.amount, couplers.py: the couplers
matrix times the amount, the curves before the couplers computed with it); the header's 8th field, absent for the amount 1."""
import sys, json
sys.path.insert(0, sys.argv[1])
import numpy as np
from spektrafilm.runtime.api import init_params, simulate

rng = np.random.default_rng(7)
grey = np.array([[v, v, v] for v in 0.184 * 2.0 ** np.arange(-7, 4.5, 0.5)])
rnd = rng.uniform(0, 1.2, (60, 3)) ** 1.5
sat = np.array([[1, 0, 0], [0, 1, 0], [0, 0, 1], [1, 1, 0], [0, 1, 1], [1, 0, 1], [0.5, 0.2, 0.05], [0.05, 0.2, 0.6], [0.02, 0.3, 0.05]])
X = np.concatenate([grey, rnd, sat])
CASES = [
    ('kodak_portra_400', 'kodak_portra_endura', {}),
    ('kodak_ektar_100', 'kodak_endura_premier', {}),
    ('kodak_vision3_500t', 'kodak_2383', {}),
    ('kodak_gold_200', 'kodak_ektacolor_edge', {}),
    ('fujifilm_c200', 'fujifilm_crystal_archive_typeii', {}),
    ('kodak_portra_800_push2', 'kodak_supra_endura', {}),
    ('fujifilm_velvia_100', 'kodak_portra_endura', {'scan_film': True}),
    ('kodak_kodachrome_64', 'kodak_portra_endura', {'scan_film': True}),
    ('kodak_portra_400', 'kodak_portra_endura', {'ev': 1.0}),
    ('kodak_portra_400', 'kodak_portra_endura', {'ev': -1.5}),
    ('kodak_portra_400', 'kodak_portra_endura', {'print_exposure': 1.3}),
    ('kodak_portra_400', 'kodak_portra_endura', {'m_shift': 10.0, 'y_shift': -8.0}),
    ('kodak_vision3_250d', 'kodak_2393', {'ev': 0.7, 'print_exposure': 0.8}),
    # P380: the DIR couplers amount (our «Цветоразделение (DIR)» 0 / 50 / 150 %). Above about 150 % some stocks' log exposure
    # before the couplers (log_exposure - curves @ matrix) stops rising, so the curves before the couplers are not single-valued:
    # np.interp of a falling xp and spektrafilm-ofx's bisection (makeDirCorrectedDensityCurves) then differ - no case there.
    ('kodak_portra_160', 'kodak_endura_premier', {'dir': 0.0}),
    ('kodak_portra_160', 'kodak_endura_premier', {'dir': 0.5}),
    ('kodak_portra_400', 'kodak_portra_endura', {'dir': 0.0}),
    ('kodak_vision3_250d', 'kodak_2383', {'dir': 0.5}),
    ('kodak_gold_200', 'kodak_portra_endura', {'dir': 1.5}),
    ('fujifilm_velvia_100', 'kodak_portra_endura', {'scan_film': True, 'dir': 0.0}),
    ('kodak_ektachrome_100', 'kodak_portra_endura', {'scan_film': True, 'dir': 0.5}),
]
out = {'x': X.tolist(), 'cases': []}
for film, prnt, o in CASES:
    p = init_params(film_profile=film, print_profile=prnt)
    p.io.input_color_space = 'sRGB'; p.io.input_cctf_decoding = False
    p.io.scan_film = o.get('scan_film', False)
    p.camera.auto_exposure = False
    p.debug.deactivate_spatial_effects = True
    p.debug.deactivate_stochastic_effects = True
    p.film_render.halation.boost_ev = 0.0
    p.camera.exposure_compensation_ev = o.get('ev', 0.0)
    p.enlarger.print_exposure_compensation = False
    p.enlarger.print_exposure = o.get('print_exposure', 1.0)
    p.enlarger.m_filter_shift = o.get('m_shift', 0.0)
    p.enlarger.y_filter_shift = o.get('y_shift', 0.0)
    if 'dir' in o:
        p.film_render.dir_couplers.amount = o['dir']
    y = simulate(X[None], p)[0]
    out['cases'].append({'film': film, 'print': prnt, 'opts': o, 'y': np.asarray(y).tolist()})
    print(film, prnt, o, np.round(y[9], 4))
json.dump(out, open(sys.argv[2], 'w'))


def g(v):
    return np.format_float_positional(np.float32(v), trim='-') if abs(v) >= 1e-4 or v == 0 else repr(float(np.float32(v)))


if len(sys.argv) > 3:
    with open(sys.argv[3], 'w', newline='\n') as f:
        f.write('%d\n' % len(X))
        for x in X: f.write(' '.join(g(v) for v in x) + '\n')
        f.write('%d\n' % len(CASES))
        for c in out['cases']:
            o = c['opts']
            h = [c['film'], c['print'], '1' if o.get('scan_film') else '0', g(o.get('ev', 0.0)), g(o.get('print_exposure', 1.0)),
                 g(o.get('m_shift', 0.0)), g(o.get('y_shift', 0.0))]
            if 'dir' in o: h.append(g(o['dir']))
            f.write(' '.join(h) + '\n')
            for y in c['y']: f.write(' '.join(g(v) for v in y) + '\n')
