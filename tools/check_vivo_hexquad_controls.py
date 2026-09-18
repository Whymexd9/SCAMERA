"""Cross-language wiring checks: XML -> settings -> captured transport/policy -> consumers."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
java = root / 'app/src/main/java/com/particlesdevs/photoncamera'
xml = ET.parse(root / 'app/src/main/res/xml/preferences.xml').getroot()
a = '{http://schemas.android.com/apk/res/android}'
screen = next(e for e in xml if e.get(a+'key') == 'hexquad_denoise_screen')
settings = (java/'settings/PreferenceKeys.java').read_text()
for key, default, method in [('hexquad_luma', '50', 'getHexQuadLuma'), ('hexquad_chroma', '100', 'getHexQuadChroma')]:
    elements = [e for e in xml.iter() if e.get(a+'key') == key]
    assert len(elements) == 1 and elements[0] in list(screen.iter())
    assert elements[0].get(a+'defaultValue') == default
    assert f'RawTherapeeSettings.number("{key}",{default},0,100)' in settings
    assert method+'()' in settings
assert next(e for e in screen.iter() if e.get(a+'key') == 'hexquad_post_denoise').get(a+'defaultValue') == 'false'
burst = (java/'processing/opengl/postpipeline/HexQuadBurst.java').read_text()
options=(java/'settings/HexQuadOptions.java').read_text()
assert 'HEADER_BYTES=112' in options and 'putInt(0x32515848).putInt(gpu?4:3)' in options
assert 'options.header(width,height,iso,red,black,white,response,neutral)' in burst
assert '.putFloat(lumaPercent/100f).putFloat(chromaPercent/100f)' in options
assert 'p.hexQuadProcessed=true;p.hexQuadPostDenoise=burst.postDenoise' in burst
pipeline = (java/'processing/opengl/postpipeline/PostPipeline.java').read_text()
assert pipeline.count('!mParameters.hexQuadProcessed || mParameters.hexQuadPostDenoise') == 2
assert re.search(r'hexQuadPostDenoise\).*?add\(new ESD3D2', pipeline, re.S)
assert re.search(r'hexQuadPostDenoise\).*?add\(new RawTherapeeDenoise', pipeline, re.S)
hdr = (java/'processing/processor/HdrxProcessor.java').read_text()
assert '!processingParameters.hexQuadProcessed || processingParameters.hexQuadPostDenoise' in hdr
assert 'if (allowPostDenoise && PreferenceKeys.isAiDenoiseEnabled()' in hdr
assert 'params.hexQuadPostDenoise = hexQuadPostDenoise;' in (java/'processing/render/Parameters.java').read_text()
print('HexQuad controls: visible screen, matching defaults, versioned header, captured policy, three NR consumers PASS')

expected={'hexquad_model':'2','hexquad_full_resolution':'false','hexquad_noise_overall':'1','hexquad_noise_photon':'1','hexquad_noise_readout':'1','hexquad_auto_iso':'false','hexquad_texture':'0','hexquad_iso_low_luma':'35','hexquad_iso_low_chroma':'85','hexquad_iso_high_luma':'70','hexquad_iso_high_chroma':'100'}
activity=(java/'ui/settings/SettingsActivity.java').read_text()
for key,default in expected.items():
    es=[e for e in xml.iter() if e.get(a+'key')==key]
    assert len(es)==1 and es[0] in list(screen.iter()) and es[0].get(a+'defaultValue')==default
    assert key in settings and key in activity
assert 'burst.options.profileKey(burst.iso,burst.red)' in (java/'processing/opengl/postpipeline/VivoNeuralClient.java').read_text()
assert 'burst.options.outputBytes(w,h)' in (java/'processing/opengl/postpipeline/VivoNeuralClient.java').read_text()
assert 'width=processingParameters.rawSize.x;height=processingParameters.rawSize.y;' in hdr
print('HexQuad v14: model, output dimensions, ISO policy, profile cache, texture and UI wiring PASS')

# UniversalSeekBar stepPerUnit is steps per ONE UNIT, not the step size.
for key in ['hexquad_noise_overall','hexquad_noise_photon','hexquad_noise_readout']:
    e=next(e for e in screen.iter() if e.get(a+'key')==key)
    app='{http://schemas.android.com/apk/res-auto}'
    assert e.get(app+'stepPerUnit')=='20' and e.get(app+'isFloat')=='true'
    assert float(e.get(app+'maxValue'))-float(e.get(app+'minValue'))==1.5

es=[e for e in xml.iter() if e.get(a+'key')=='hexquad_compute']
assert len(es)==1 and es[0] in list(screen.iter()) and es[0].get(a+'defaultValue')=='cpu'
assert '"hexquad_compute","cpu"' in settings
assert '"gpu".equals(' in settings and 'hexquad_compute' in activity
assert 'gpu?4:3' in options and 'putInt(gpu?1:0)' in options
print('HexQuad GPU: opt-in selector, captured transport and CPU default PASS')
