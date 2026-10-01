# SCAM HDR at night: tone and colour against the stock render (vivo X200 Ultra)

Scene: a ship's deck under sodium / warm LED lamps, main camera 1x, 1/60 s ISO 2560, the phone on a stand; reference shots
by the stock camera and LMC (`research/cct/room/ship*_{s,v,l}.jpg`, measured with the region script in the session notes).

## Tone: a night scene stays a night scene

`LinearExposure` sets the soft-tone key from the scene median. A deck whose green median sits at 0.6 % of full scale was lifted
x11 (median 70/255, 8 % of the picture clipped, the sea and the sky amplified to a noisy grey) while the stock keeps it nearly
black and LMC dim. Below `tone_night_p` (0.015) the key now falls with the scene's own median: `target *= (p50 / nightP) ^
tone_night_exp` (0.6). Result on the ship: mean Y 77 (before 105; LMC 79, stock 50), clipped 1 % (before 8 %).

## Colour temperature: from the shot's white balance, not from the DNG matrices

The DNG colour matrices of the X200 Ultra do not describe its sensors: the interpolation of the neutral point lands on a
reference illuminant for every scene (daylight and the lamp-lit deck both gave 4100 K), so the warm-cast retention of the
render (`castTint`) had nothing to work with and SCAM HDR rendered every lamp-lit scene fully neutral.

`SceneIlluminant` takes the shot's `SENSOR_NEUTRAL_COLOR_POINT` against the camera's daylight neutral (measured on an overcast
noon, 2026-10-01: camera 3 main `0.3945 1 0.6396`, camera 4 ultra wide `0.3896 1 0.6709`, camera 5 tele / ISZ
`0.4668 1 0.6904`), maps that white through the ISP colour transform of the capture result and reads the correlated colour
temperature of the result (McCamy). The ship scene gives 2580 K (neutral `0.76 1 0.31`). `Parameters.sceneCct` carries it;
the DNG estimate stays for cameras without a daylight neutral (and the OPPO tuned estimate on the 8 Gen 3 is untouched).

## Warmth kept and the warm-light look

* `pref_vivo_nice_warm_retention` auto (-1) is now 35 % on vivo (was 0): the Planckian colour of the scene light at the share
  kept, luminance preserved (`HeadroomRender.warmTint`).
* The cast is applied with two guards in `render.glsl` (`castRange`): it fades out below a display-linear luminance of
  0.015..0.10 (the dark sea and sky of a night scene are lit by the sky, not by the lamps: they stay neutral, as in the stock
  render), and above a peak channel level of 0.35..0.75 it switches to the max-normalised tint, which darkens the other
  channels instead of pushing one past white (the lamp-lit white tower stays warm instead of clipping to white).
* Warm light (below `look_warm_lo` 3200 K, full weight; none above `look_warm_hi` 4800 K) rotates the warm hues of the colour
  look towards orange: `look_warm_shift` (-10 degrees) scaled per OKLab hue node by `look_wh0..11`
  (`{0.3, 0.7, 1, 0.8, 0.3, 0, ..., 0.1}`, nodes every 30 degrees from +a). The daylight look (fitted on a cabin scene,
  +2..+4 degrees towards yellow on those nodes) is unchanged in daylight.

Measured on the ship (sRGB means of the same regions, stock | SCAMERA before | SCAMERA now):

| region | stock R/G, B/G, OKLab hue | before | now |
| --- | --- | --- | --- |
| grab (crane bucket) | 1.94, 0.27, 53° | 1.49, 0.29, 71° | 1.91, 0.23, 54° |
| deck | 1.33, 0.63, 69° | 1.16, 0.75, 80° | 1.41, 0.60, 64° |
| tower (lamp-lit white) | 1.30, 0.61, 73° | 1.15, 0.72, 83° | 1.28, 0.74, 64° |
| sea | 1.06, 0.85 | 1.05, 1.07 | 1.09, 1.08 |
| whole picture | 1.36, 0.66 | 1.19, 0.77 | 1.40, 0.69 |

The deck and the grabs are now the stock's orange, the white balance is a little warmer than the stock's (deck B/G 0.60
against 0.63), the sea stays neutral. Dev keys (`nice_dev.txt`): `warm_retention`, `look_warm_shift`, `look_warm_lo/hi`,
`look_wh<i>`, `warm_shadow_lo/hi`, `warm_peak_lo/hi`, `tone_night_p/exp`. Log lines: `Parameters: Scene light from the white
balance`, `HeadroomRender: scene light ... cast tint`, `HeadroomRender: warm light`.

Not measured yet: daylight and indoor cool-white scenes after the change (the cast and the hue shift are off above 4800 K by
construction, the night damping is off above a median of 1.5 %).
