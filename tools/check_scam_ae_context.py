from pathlib import Path
import json
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com/particlesdevs/photoncamera/capture'
# UI language (i18n): the Russian text, as the real Lang gives on a Russian system
LANG = 'package com.particlesdevs.photoncamera.util; public final class Lang {public static boolean ru(){return true;}public static String t(String ru,String en){return ru;}public static String t(Object context,String ru,String en){return ru;}}'
CHECK = '''package com.particlesdevs.photoncamera.capture;
import java.nio.*;
import java.io.IOException;
public class ScamContextCheck {
 static void check(int mode,long scene,int flags,int type,int table,boolean expected)throws Exception {
  byte[] input=new byte[0xe8],state=new byte[0x930],plan=new byte[100];
  ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).putInt(0xc4,mode);
  ByteBuffer.wrap(state).order(ByteOrder.LITTLE_ENDIAN).putLong(0xb8,scene);
  ByteBuffer.wrap(plan).order(ByteOrder.LITTLE_ENDIAN).putInt(72,flags).putInt(76,type).putInt(80,table);
  try {
   ScamAeContext c=new ScamAeContext(input,state,plan);
   if(!expected)throw new AssertionError("accepted non-SCAM scene");
   if(c.runMode!=mode||c.sceneMode!=scene||c.hdrFlags!=flags||c.tableType!=type||c.tableId!=table)
    throw new AssertionError("lost provenance");
  }catch(IOException rejected){if(expected)throw rejected;}
 }
 public static void main(String[] args)throws Exception {
  FIXTURES
  check(14,0,0x10000,9,3,false);
  check(9,0xc80000,2,4,0,false);
  check(9,0,0x10000,4,0,false);
  for(byte[] broken:new byte[][]{null,new byte[99],new byte[101]}) {
   try {new ScamAeContext(new byte[0xe8],new byte[0x930],broken);
    throw new AssertionError("accepted malformed plan");}catch(IOException expected){}
  }
 }
}'''


def main():
    cases = json.loads((ROOT / 'tools/fixtures/scam-ae-context.json').read_text(encoding='utf-8'))
    calls = '\n'.join(f"check({x['runMode']},{x['sceneMode']}L,{x['hdrFlags']},"
                      f"{x['tableType']},{x['tableId']},{str(x['stockScam']).lower()});" for x in cases)
    with tempfile.TemporaryDirectory(prefix='scam-context-') as directory:
        check = Path(directory) / 'ScamContextCheck.java'
        check.write_text(CHECK.replace('FIXTURES', calls), encoding='utf-8')
        lang = Path(directory) / 'Lang.java'
        lang.write_text(LANG, encoding='utf-8')
        source = ROOT / 'app/src/main/java' / PACKAGE / 'ScamAeContext.java'
        subprocess.run(['javac', '-encoding', 'UTF-8', '-d', directory, str(source), str(check), str(lang)], check=True)
        subprocess.run(['java', '-cp', directory, PACKAGE.replace('/', '.') + '.ScamContextCheck'], check=True)
    print('PASS: stock SCAM context accepted; both measured Camera2 generic contexts and malformed contexts rejected')


if __name__ == '__main__':
    main()
