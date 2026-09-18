import runpy,re
from pathlib import Path
x=runpy.run_path('tools/checks/preview_gl.py')
base=Path('app/src/main/assets/shaders')
initial=(base/'Initial/initial.glsl').read_text()
defs={k:v for k,v in re.findall(r'^#define ([A-Z0-9_]+) (.*)$',initial,re.M) if k.startswith(('ACES_','DT_')) or k=='DARKTABLE_ENABLED'}
defs.update(PHOTO_LOOK_ENABLED='1',PHOTO_GAMMA='2.2',PHOTO_TONE_MIX='0.5',PHOTO_SATURATION='1.0',GAMMAX1='7.1896',GAMMAX2='-50.8195',GAMMAX3='129.3564',TONEMAPX1='-0.15',TONEMAPX2='2.55',TONEMAPX3='-1.6',PHOTO_HIGH_SATURATION='1.0',SATURATIONRED='1.0',CONTRAST='1.0',SHADOWS='0.0',LTMMIX='0.0')
look=(base/'preview/photo_look.glsl').read_text().replace('/*PHOTO_HSV*/',(base/'utils/import_photohsv.glsl').read_text()).replace('/*PHOTO_SATURATION*/',(base/'utils/import_photosaturation.glsl').read_text()).replace('/*PHOTO_ACES*/',(base/'utils/import_photoaces.glsl').read_text()).replace('/*PHOTO_DARKTABLE*/',(base/'utils/import_photodarktable.glsl').read_text().replace('vec2(INSIZE)','photoViewport'))
for aces in [0,1]:
 for curve in (range(12) if aces else [0]):
  defs['ACES_ENABLED']=str(aces);defs['ACES_TONE_CURVE']=str(curve);defs['DARKTABLE_ENABLED']='1'
  fs=x['fs'].replace('#version 300 es','#version 300 es\n'+'\n'.join('#define '+k+' '+v for k,v in defs.items())).replace('/*PHOTO_LOOK*/',look)
  x['program'](fs)
print('PASS photo look: SDR and 12 ACES curves with darktable compile/link')
# Draw photo look, then verify shadow changes and ACES selection affect output.
C=x['C'];F=x['F'];gl=x['gl'];U=x['U'];I=x['I'];ptr=x['ptr']
x['tex'](4,1024,1,0x822d,0x1903,0x1406,(F*1024)(*[i/1023 for i in range(1024)]))
outputs=[]
for aces,shadows in [(0,0),(0,-.6),(1,0)]:
 defs.update(ACES_ENABLED=str(aces),ACES_TONE_CURVE='3',SHADOWS=str(float(shadows)),DARKTABLE_ENABLED='0')
 shader=x['fs'].replace('#version 300 es','#version 300 es\n'+'\n'.join('#define '+k+' '+v for k,v in defs.items())).replace('/*PHOTO_LOOK*/',look)
 p=x['program'](shader);stride=x['upload']([[100 if col<4 else 700 for col in range(8)]for row in range(8)],32)
 x['setup'](p,32,1023,stride);x['ui'](p,'photoExposureCurve',4);x['uf'](p,'photoWhitePoint',1)
 gl('glUniform2f',None,[I,F,F])(x['loc'](p,'photoViewport'),8,8)
 out=x['render']();assert max(out[0::4])>50;outputs.append(out)
assert outputs[0]!=outputs[1], 'shadow setting does not change preview'
assert outputs[0]!=outputs[2], 'ACES selection does not change preview'
print('PASS real photo look renders; shadows and ACES affect pixels')
