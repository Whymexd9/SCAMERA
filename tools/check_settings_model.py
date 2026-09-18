"""Run real, Android-independent settings logic using only a JDK; no SDK/device required."""
from pathlib import Path
import tempfile,subprocess,shutil
root=Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as d:
    out=Path(d);stubs=out/'stubs';p=stubs/'com/particlesdevs/photoncamera/app';p.mkdir(parents=True)
    (p/'PhotonCamera.java').write_text('package com.particlesdevs.photoncamera.app; public class PhotonCamera { public static com.particlesdevs.photoncamera.settings.SettingsManager getSettingsManagerStatic(){return null;} }')
    p=stubs/'com/particlesdevs/photoncamera/settings';p.mkdir(parents=True)
    (p/'SettingsManager.java').write_text('package com.particlesdevs.photoncamera.settings; public class SettingsManager { public static final String SCOPE_GLOBAL=""; public String getString(String a,String b,String c){return c;} }')
    production=root/'app/src/main/java/com/particlesdevs/photoncamera/settings'
    files=[production/(s+'.java') for s in ('PreferenceNumber','SettingsNumericRules','SettingsAvailability','RawTherapeeSettings')]
    javac=[shutil.which('javac')] if shutil.which('javac') else ['java','--module','jdk.compiler/com.sun.tools.javac.Main']
    subprocess.run(javac+['-d',str(out)]+[str(x) for x in files+list(stubs.rglob('*.java'))+[root/'tools/java/SettingsModelCheck.java']],check=True)
    subprocess.run(['java','-ea','-cp',str(out),'SettingsModelCheck'],check=True)
