#!/usr/bin/env python3
from pathlib import Path
import argparse
import shutil
import subprocess
import tempfile
from check_vivo_vcf2_device import STUBS, ROOT

CHECK = r'''import java.io.*; import java.lang.reflect.*; import java.nio.file.*; import java.util.*;
public class Check {
 static Method copy; static int checks;
 static void check(boolean value){checks++;if(!value)throw new AssertionError();}
 static byte[] read(int fd,int size)throws Exception {
  try{return (byte[])copy.invoke(null,fd,size);}
  catch(InvocationTargetException e){throw (Exception)e.getCause();}
 }
 interface Task{void run()throws Exception;}
 static void rejects(Task task)throws Exception {
  try{task.run();}catch(IOException expected){checks++;return;}throw new AssertionError();
 }
 public static void main(String[] args)throws Exception {
  Class<?> reader=Class.forName("com.particlesdevs.photoncamera.capture.VivoVcf2Device$NativeReader");
  copy=reader.getDeclaredMethod("copy",int.class,int.class);copy.setAccessible(true);
  Field rawFd=FileDescriptor.class.getDeclaredField("fd");rawFd.setAccessible(true);
  Path path=Paths.get(args[0]);byte[] bytes=Files.readAllBytes(path);
  int closed;
  try(FileInputStream input=new FileInputStream(path.toFile())){
   int fd=rawFd.getInt(input.getFD());closed=fd;
   input.read();input.read(); // Mapping must start at zero, not this shared FD offset.
   check(Arrays.equals(bytes,read(fd,bytes.length)));
   check(input.read()==(bytes[2]&255)); // Copy must not move/close the borrowed FD.
   check(Arrays.equals(bytes,read(fd,bytes.length)));
   rejects(()->read(fd,bytes.length+1));rejects(()->read(fd,3));
   rejects(()->read(fd,-1));rejects(()->read(fd,Integer.MAX_VALUE));
   check(input.getFD().valid());
  }
  rejects(()->read(closed,bytes.length));rejects(()->read(-1,bytes.length));
  Files.write(path,new byte[]{0,1,2,3,4,5});
  try(FileInputStream input=new FileInputStream(path.toFile())){
   int fd=rawFd.getInt(input.getFD());rejects(()->read(fd,6));
  }
  System.out.println("PASS: "+checks+" real JNI/mmap byte-copy checks; not Android/HAL validation");
 }
}'''
parser = argparse.ArgumentParser()
parser.add_argument('--jni-include', type=Path,
                    default=Path(shutil.which('java')).resolve().parents[1]/'include')
args = parser.parse_args()
with tempfile.TemporaryDirectory(prefix='vcf2-buffer-') as directory:
    root = Path(directory)
    for name, source in {**STUBS, 'Check.java': CHECK}.items():
        target = root/name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source)
    subprocess.run(['g++', '-std=c++14', '-Wall', '-Wextra', '-Werror', '-shared', '-fPIC',
                    '-I'+str(args.jni_include), '-I'+str(args.jni_include/'linux'),
                    str(ROOT/'app/src/main/cpp/vivo-vcf-buffer.cpp'),
                    '-o', str(root/'libvivoVcfBuffer.so')], check=True)
    production = ROOT/'app/src/main/java/com/particlesdevs/photoncamera/capture/VivoVcf2Device.java'
    subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-d', directory,
                    *map(str, root.rglob('*.java')), str(production)], check=True)
    # Preserve embedded markers, zero bytes and trailing payloads without interpreting them.
    fixture = root/'payload.bin'
    fixture.write_bytes(b'\xff\xd8\xff\xe1'+bytes(range(256))*8192+b'\xff\xd9TRAILING\x00PAYLOAD')
    subprocess.run(['java', '--add-opens', 'java.base/java.io=ALL-UNNAMED',
                    '-Djava.library.path='+directory, '-cp', directory, 'Check', str(fixture)], check=True)
