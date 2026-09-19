package com.particlesdevs.photoncamera.processing.color;

import org.junit.Test;
import java.io.IOException;
import java.nio.*;
import static org.junit.Assert.*;

public class DcpProfileTest {
    private byte[] profile(ByteOrder order,boolean forward){
        int count=forward?3:2,start=8+2+count*12+4;
        ByteBuffer b=ByteBuffer.allocate(start+(forward?144:72)).order(order);
        b.put((byte)(order==ByteOrder.LITTLE_ENDIAN?'I':'M')).put((byte)(order==ByteOrder.LITTLE_ENDIAN?'I':'M')).putShort((short)0x4352).putInt(8).putShort((short)count);
        b.putShort((short)50778).putShort((short)3).putInt(1).putShort((short)23).putShort((short)0);
        b.putShort((short)50721).putShort((short)10).putInt(9).putInt(start);
        if(forward)b.putShort((short)50964).putShort((short)10).putInt(9).putInt(start+72);
        b.putInt(0);
        for(int i=0;i<9;i++)b.putInt(i%4==0?10000:0).putInt(10000);
        if(forward)for(int i=0;i<9;i++)b.putInt(i==0?9642:i==4?10000:i==8?8249:0).putInt(10000);
        return b.array();
    }
    private void neutral(float[] matrix,float[] neutral){float[] expected={.9642f,1,.8249f};for(int r=0;r<3;r++){float sum=0;for(int c=0;c<3;c++)sum+=matrix[r*3+c]*neutral[c];assertEquals(expected[r],sum,.0002f);}}
    @Test public void bothByteOrdersAndColorOnlyProfilesPreserveNeutral()throws Exception{for(ByteOrder o:new ByteOrder[]{ByteOrder.BIG_ENDIAN,ByteOrder.LITTLE_ENDIAN}){DcpProfile p=DcpProfile.parse(profile(o,false));float[] n={.6f,1,.8f};neutral(p.cameraToXyz(n),n);}}
    @Test public void forwardMatrixDoesNotDoubleWhiteBalance()throws Exception{DcpProfile p=DcpProfile.parse(profile(ByteOrder.LITTLE_ENDIAN,true));float[] n={.5f,1,.25f};neutral(p.cameraToXyz(n),n);}
    @Test public void acceptsTiffMagic()throws Exception{byte[] b=profile(ByteOrder.LITTLE_ENDIAN,false);b[2]=42;b[3]=0;assertNotNull(DcpProfile.parse(b));}
    @Test public void rejectsZeroDenominator()throws Exception{byte[] b=profile(ByteOrder.LITTLE_ENDIAN,false);ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(42,0);reject(b);}
    @Test public void rejectsUnsignedOffsetOutsideFile()throws Exception{byte[] b=profile(ByteOrder.LITTLE_ENDIAN,false);ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(30,-1);reject(b);}
    @Test public void rejectsIfdCycle()throws Exception{byte[] b=profile(ByteOrder.LITTLE_ENDIAN,false);ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(34,8);reject(b);}
    @Test public void rejectsTruncatedMatrices()throws Exception{byte[] b=profile(ByteOrder.LITTLE_ENDIAN,false);reject(java.util.Arrays.copyOf(b,b.length-1));}
    @Test public void rejectsNonFiniteNeutral()throws Exception{DcpProfile p=DcpProfile.parse(profile(ByteOrder.LITTLE_ENDIAN,false));try{p.cameraToXyz(new float[]{Float.NaN,1,1});fail();}catch(IllegalArgumentException expected){}}
    private void reject(byte[] b)throws Exception{try{DcpProfile.parse(b);fail("Malformed input accepted");}catch(IOException expected){}}
}
