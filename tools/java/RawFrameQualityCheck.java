import java.nio.*;
import java.util.*;
import com.particlesdevs.photoncamera.capture.RawFrameQuality;
import com.particlesdevs.photoncamera.capture.HexQuadZslSelector;
public class RawFrameQualityCheck {
    static ByteBuffer scene(int block,boolean blur,boolean noise) {
        int w=640,h=480,stride=w*2+32;ByteBuffer b=ByteBuffer.allocateDirect(stride*h).order(ByteOrder.LITTLE_ENDIAN);
        Random rng=new Random(43);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            double edge=blur ? Math.max(0,Math.min(1,((x%80)-30)/20.0)) : (x%80>=40?1:0);
            int c=((x/block)&1)+2*((y/block)&1);
            int value=noise?1200+rng.nextInt(400):800+(int)(edge*1500);
            value+=c*300; // CFA colour offsets must not masquerade as detail.
            b.putShort(y*stride+x*2,(short)value);
        }
        return b;
    }
    public static void main(String[] args) {
        for(int block:new int[]{1,2,4}) {
            ByteBuffer sharp=scene(block,false,false),blur=scene(block,true,false),noise=scene(block,false,true);
            int position=sharp.position();
            double a=RawFrameQuality.score(sharp,640,480,1312,2,block);
            double b=RawFrameQuality.score(blur,640,480,1312,2,block);
            double c=RawFrameQuality.score(noise,640,480,1312,2,block);
            System.out.println("CFA block="+block+" sharp="+a+" blur="+b+" noise="+c);
            assert a>b && a>c : "blur/noise ranked above real edges";
            assert sharp.position()==position && sharp.order()==ByteOrder.LITTLE_ENDIAN;
        }
        assert Double.isNaN(RawFrameQuality.score(ByteBuffer.allocate(20),640,480,1312,2,1));
        List<HexQuadZslSelector.Sample> f=new ArrayList<>();
        for(int i=0;i<12;i++)f.add(new HexQuadZslSelector.Sample(1_000_000_000L+i*33_000_000L,10_000_000L,800,i<9?1:.05));
        int[] chosen=HexQuadZslSelector.select(f,6,true);
        assert Arrays.equals(chosen,new int[]{3,4,5,6,7,8}):Arrays.toString(chosen);
        assert Arrays.equals(HexQuadZslSelector.select(f,6,false),new int[]{6,7,8,9,10,11});
        // Metadata is a hard boundary, even if an older frame looks sharper.
        f.set(4,new HexQuadZslSelector.Sample(f.get(4).timestamp,20_000_000L,800,100));
        chosen=HexQuadZslSelector.select(f,6,true);assert chosen[0]>=5;
        f.set(11,new HexQuadZslSelector.Sample(f.get(11).timestamp,10_000_000L,800,Double.NaN));
        assert Arrays.equals(HexQuadZslSelector.select(f,6,true),new int[]{6,7,8,9,10,11});
        // Long exposure series cannot drift several captures behind the shutter.
        f.clear();for(int i=0;i<12;i++)f.add(new HexQuadZslSelector.Sample(1_000_000_000L+i*300_000_000L,100_000_000L,800,i<9?1:.05));
        assert HexQuadZslSelector.select(f,6,true).length==0; // outside hard age bound
        System.out.println("RAW quality + ZSL window selection PASS");
    }
}
