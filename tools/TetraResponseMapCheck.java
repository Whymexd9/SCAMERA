import java.nio.*;
import java.nio.file.*;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.TetraResponseProfile;

/** Tests the production fitter against known spatial response, not fitted coefficients. */
public class TetraResponseMapCheck {
    public static void main(String[] args) throws Exception {
        if(args.length==4) {
            int w=Integer.parseInt(args[1]),h=Integer.parseInt(args[2]);
            FloatBuffer input=ByteBuffer.wrap(Files.readAllBytes(Paths.get(args[0])))
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            TetraResponseProfile.GainMap map=TetraResponseProfile.estimateMap(input,w,h);
            ByteBuffer output=ByteBuffer.allocate(map.values.length*4).order(ByteOrder.LITTLE_ENDIAN);
            output.asFloatBuffer().put(map.values);Files.write(Paths.get(args[3]),output.array());
            System.out.println("spatial support="+java.util.Arrays.toString(map.spatial));
            return;
        }
        final int w=512,h=384;
        FloatBuffer input=FloatBuffer.allocate(w*h*4);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)for(int q=0;q<4;q++) {
            float px=2f*(x/4+.5f)/(w/4)-1,py=2f*(y/4+.5f)/(h/4)-1;
            int k=(y%4)*4+x%4;
            float response=1f+(k%2==0?1:-1)*(.07f+.025f*px+.01f*py*py);
            float signal=(2f+.4f*px+.3f*py)*response;
            input.put((y*w+x)*4+q,signal);
        }
        TetraResponseProfile.GainMap map=TetraResponseProfile.estimateMap(input,w,h);
        for(boolean used:map.spatial)if(!used)throw new AssertionError("supported field rejected");
        double max=0;
        for(int y=0;y<28;y++)for(int x=0;x<36;x++)for(int q=0;q<4;q++) {
            float px=2f*(x/4)/8-1,py=2f*(y/4)/6-1;
            int k=(y%4)*4+x%4;
            float response=1f+(k%2==0?1:-1)*(.07f+.025f*px+.01f*py*py);
            max=Math.max(max,Math.abs(map.values[(y*36+x)*4+q]*response-1));
        }
        if(max>.002)throw new AssertionError("known response residual="+max);
        for(int i=0;i<input.capacity();i++)input.put(i,2f);
        map=TetraResponseProfile.estimateMap(input,w,h);
        for(float gain:map.values)if(Math.abs(gain-1)>1e-6)throw new AssertionError("flat altered");
        for(boolean used:map.spatial)if(used)throw new AssertionError("false spatial correction");
        for(float gain:TetraResponseProfile.estimateMap(FloatBuffer.allocate(16*16*4),16,16).values)
            if(gain!=1)throw new AssertionError("unsupported image altered");
        System.out.println("PASS: spatial response, neutral bypass, unsupported fallback; max residual="+max);
    }
}
