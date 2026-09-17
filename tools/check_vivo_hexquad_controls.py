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
    assert len(elements) == 1 and elements[0] in list(screen)
    assert elements[0].get(a+'defaultValue') == default
    assert f'RawTherapeeSettings.number("{key}",{default},0,100)' in settings
    assert method in (java/'processing/opengl/postpipeline/HexQuadBurst.java').read_text()
assert next(e for e in screen if e.get(a+'key') == 'hexquad_post_denoise').get(a+'defaultValue') == 'false'
burst = (java/'processing/opengl/postpipeline/HexQuadBurst.java').read_text()
assert 'ByteBuffer.allocate(80)' in burst and 'putInt(0x32515848).putInt(2)' in burst
assert '.putFloat(lumaPercent/100f).putFloat(chromaPercent/100f)' in burst
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
