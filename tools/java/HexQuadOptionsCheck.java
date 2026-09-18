import com.particlesdevs.photoncamera.settings.HexQuadOptions;
import java.nio.*;
import java.nio.file.*;
public class HexQuadOptionsCheck {
    static HexQuadOptions options(int iso,int model,boolean full,boolean auto,float common,float photon,float read){
        return new HexQuadOptions(iso,model,full,common,photon,read,50,100,auto,35,85,70,100,40);
    }
    static void near(float a,float b){assert Math.abs(a-b)<.00001f:a+" != "+b;}
    public static void main(String[] args)throws Exception{
        for(int iso:new int[]{50,100,200,400,800,1600,3200,12800}){
            HexQuadOptions manual=options(iso,2,false,false,1,1,1);near(manual.lumaPercent,50);near(manual.chromaPercent,100);
            HexQuadOptions auto=options(iso,2,false,true,1,1,1);
            float t=(float)Math.max(0,Math.min(5,Math.log(iso/100.)/Math.log(2)));
            near(auto.lumaPercent,35+7*t);near(auto.chromaPercent,85+3*t);
        }
        near(HexQuadOptions.interpolate(50,70,20),70);near(HexQuadOptions.interpolate(12800,70,20),20);
        HexQuadOptions base=options(800,2,false,false,1,1,1);
        String key=base.profileKey(800,3);
        assert !key.equals(options(800,1,false,false,1,1,1).profileKey(800,3));
        for(HexQuadOptions changed:new HexQuadOptions[]{options(800,2,false,false,1.1f,1,1),options(800,2,false,false,1,1.1f,1),options(800,2,false,false,1,1,1.1f)})assert !key.equals(changed.profileKey(800,3));
        assert !key.equals(base.profileKey(400,3));assert !key.equals(base.profileKey(800,0));
        assert base.outputBytes(4096,3072)==25165824L;
        HexQuadOptions full=options(800,2,true,true,.75f,1.25f,1.5f);
        assert full.outputBytes(4096,3072)==100663296L;
        assert !options(800,1,true,false,1,1,1).fullResolution;
        ByteBuffer h=full.header(288,288,800,3,64,1023,true,new float[]{.5f,1,.7f});
        assert h.remaining()==112;assert h.getInt(0)==0x32515848&&h.getInt(4)==3&&h.getInt(80)==2&&h.getInt(100)==1;
        near(h.getFloat(48),.56f);near(h.getFloat(52),.94f);near(h.getFloat(84),.75f);near(h.getFloat(88),1.25f);near(h.getFloat(92),1.5f);near(h.getFloat(96),.4f);
        for(int i=68;i<80;i++)assert h.get(i)==0;for(int i=104;i<112;i++)assert h.get(i)==0;
        for(float bad:new float[]{0,Float.NaN,Float.POSITIVE_INFINITY,2.01f}){
            boolean failed=false;try{options(800,2,false,false,bad,1,1);}catch(IllegalArgumentException e){failed=true;}assert failed;
        }
        HexQuadOptions gpu=new HexQuadOptions(800,2,true,.75f,1.25f,1.5f,50,100,true,35,85,70,100,40,true);
        ByteBuffer gh=gpu.header(288,288,800,3,64,1023,true,new float[]{.5f,1,.7f});
        assert gpu.gpu&&!full.gpu;assert gh.getInt(4)==4&&gh.getInt(104)==1&&gh.getInt(108)==0;
        assert full.profileKey(800,3).equals(gpu.profileKey(800,3)); // GPU does not change model conditioning
        if(args.length==1){byte[] gpuBurst=new byte[112+288*288*6*2];gh.get(gpuBurst,0,112);Files.write(Paths.get(args[0]+".gpu"),gpuBurst);byte[] burst=new byte[112+288*288*6*2];h.get(burst,0,112);Files.write(Paths.get(args[0]),burst);}
        System.out.println("HexQuad options: ISO stops/endpoints, manual policy, model/profile cache separation and v3 little-endian bytes PASS");
    }
}
