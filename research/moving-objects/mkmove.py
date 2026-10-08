"""Moving-object variant of a synthetic NCH burst: in every donor frame (index >= 1) the rectangles RECTS hold the frame's own
mosaic from 64*f px further right (CFA phase kept: a multiple of 8), as an object that moves between frames. Frame 0 (base) unchanged.
Usage: mkmove.py <syn_bN.nch> <out.nch> <dir of nch.py (research/quad14)> [KEEP]: the last KEEP donors stay static (partial motion)."""
import shutil, sys
import numpy as np
sys.path.insert(0, sys.argv[3])
import nch
src, dst = sys.argv[1], sys.argv[2]
RECTS = [(320, 320, 1024, 768), (2112, 1600, 1024, 512), (320, 2400, 1024, 384)]  # x, y, w, h (multiples of 8)
d = nch.read(src)
shutil.copyfile(src, dst)
n, w, h = len(d['frames']), d['w'], d['h']
off = 128 + 32 * n
mm = np.memmap(dst, dtype='<u2', mode='r+', offset=off, shape=(n, h, w))
KEEP = int(sys.argv[4]) if len(sys.argv) > 4 else 0
for f in range(1, n - KEEP):
    if d['frames'][f]['role'] != 1:
        continue
    s = 64 * f
    for x, y, rw, rh in RECTS:
        mm[f, y:y + rh, x:x + rw] = d['planes'][f, y:y + rh, x + s:x + s + rw]
mm.flush()
print('wrote', dst, n, 'frames', RECTS)
