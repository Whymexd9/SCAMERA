import java.nio.FloatBuffer;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.TetraResponseProfile;

/** Standalone regression gate; no Android runtime or mock metadata required. */
public class TetraResponseProfileCheck {
    private static final int W=64, H=64;
    private static FloatBuffer grid(boolean pattern, boolean inconsistent, boolean dark) {
        FloatBuffer f=FloatBuffer.allocate(W*H*4);
        for(int y=0;y<H;y++)for(int x=0;x<W;x++)for(int q=0;q<4;q++) {
            int k=(y%4)*4+x%4;
            float ratio=pattern ? 1f+(k%2==0?0.08f:-0.08f) : 1f;
            if(inconsistent && x>=W/2)ratio=1f;
            float brightness=dark?0f:1f+((x/4+y/4)%7)*0.2f;
            f.put((y*W+x)*4+q,brightness*ratio);
        }
        return f;
    }
    private static void near(float a,float b) {
        if(Math.abs(a-b)>0.00001f)throw new AssertionError(a+" != "+b);
    }
    public static void main(String[] args) {
        for(float g:TetraResponseProfile.estimate(grid(false,false,false),W,H))near(g,1f);
        float[] corrected=TetraResponseProfile.estimate(grid(true,false,false),W,H);
        for(int i=0;i<64;i++)near(corrected[i],1f/(i%2==0?1.08f:0.92f));
        for(float g:TetraResponseProfile.estimate(grid(true,true,false),W,H))near(g,1f);
        for(float g:TetraResponseProfile.estimate(grid(true,false,true),W,H))near(g,1f);
        for(float g:TetraResponseProfile.estimate(FloatBuffer.allocate(16*16*4),16,16))near(g,1f);
        System.out.println("PASS: flat, known response, spatial disagreement, dark and insufficient support");
    }
}
