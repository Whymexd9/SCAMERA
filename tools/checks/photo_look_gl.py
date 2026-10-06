import runpy,re
from pathlib import Path
x=runpy.run_path('tools/checks/preview_gl.py')
base=Path('app/src/main/assets/shaders')
# The production defines of the live RAW viewfinder (PreviewPhotoLook.DEFINES).
src=Path('app/src/main/java/com/particlesdevs/photoncamera/ui/camera/views/viewfinder/PreviewPhotoLook.java').read_text()
defs=dict(re.findall(r'#define ([A-Z0-9_]+) ([-0-9.]+)',src))
assert defs.get('PHOTO_LOOK_ENABLED')=='1' and 'GAMMAX1' in defs and 'TONEMAPX3' in defs, defs
look=(base/'preview/photo_look.glsl').read_text().replace('/*PHOTO_HSV*/',(base/'utils/import_photohsv.glsl').read_text()).replace('/*PHOTO_SATURATION*/',(base/'utils/import_photosaturation.glsl').read_text())
C=x['C'];F=x['F'];gl=x['gl'];U=x['U'];I=x['I'];ptr=x['ptr']
x['tex'](4,1024,1,0x822d,0x1903,0x1406,(F*1024)(*[i/1023 for i in range(1024)]))
shader=x['fs'].replace('#version 300 es','#version 300 es\n'+'\n'.join('#define '+k+' '+v for k,v in defs.items())).replace('/*PHOTO_LOOK*/',look)
p=x['program'](shader)
outputs=[]
for level in [100,400,700]:
 stride=x['upload']([[level for col in range(8)]for row in range(8)],32)
 x['setup'](p,32,1023,stride);x['ui'](p,'photoExposureCurve',4);x['uf'](p,'photoWhitePoint',1)
 gl('glUniform2f',None,[I,F,F])(x['loc'](p,'photoViewport'),8,8)
 out=x['render']();outputs.append(sum(out[0::4])/len(out[0::4]))
assert outputs[0]<outputs[1]<outputs[2] and outputs[2]>50, outputs
print('PASS photo look: production defines compile, link and render monotone', [round(v,1) for v in outputs])
