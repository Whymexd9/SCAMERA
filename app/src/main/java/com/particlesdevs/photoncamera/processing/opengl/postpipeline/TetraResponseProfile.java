package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import java.nio.FloatBuffer;
import java.util.Arrays;

/** Robust scene-derived response estimate, NOT an EEPROM/crosstalk calibration. */
public final class TetraResponseProfile {
    private TetraResponseProfile() {}

    public static float[] estimate(FloatBuffer grid, int width, int height) {
        float[] gains = new float[64];
        Arrays.fill(gains, 1f);
        int tw = width / 4, th = height / 4, capacity = tw * th;
        if (tw < 8 || th < 8) return gains;
        // Each row is one independent 32x32 tile's normalized 16-site profile.
        float[][] profiles = new float[capacity][16];
        int[] zones = new int[capacity];
        for (int q = 0; q < 4; q++) {
            int count = 0;
            int[] zoneCounts = new int[4];
            for (int ty = 0; ty < th; ty++) for (int tx = 0; tx < tw; tx++) {
                float mean = 0;
                for (int k = 0; k < 16; k++) {
                    float value = grid.get(((ty * 4 + k / 4) * width + tx * 4 + k % 4) * 4 + q);
                    profiles[count][k] = value;
                    mean += value / 16f;
                }
                // Sums cover up to sixteen cells. Exclude dark/near-clipped tiles.
                if (!(mean > 0.32f && mean < 14f)) continue;
                boolean valid = true;
                for (int k = 0; k < 16; k++) {
                    profiles[count][k] /= mean;
                    if (!(profiles[count][k] > 0.75f && profiles[count][k] < 1.25f)) valid = false;
                }
                if (!valid) continue;
                int zone = (ty >= th / 2 ? 2 : 0) + (tx >= tw / 2 ? 1 : 0);
                zones[count] = zone;
                zoneCounts[zone]++;
                count++;
            }
            if (count < 64) continue;
            boolean supported = true;
            for (int n : zoneCounts) if (n < 8) supported = false;
            if (!supported) continue;
            float[] median = new float[16], work = new float[count];
            for (int k = 0; k < 16 && supported; k++) {
                for (int i = 0; i < count; i++) work[i] = profiles[i][k];
                median[k] = median(work, count);
                // A response pattern must repeat across all four spatial regions.
                // Reject the whole colour quadrant if scene structure disagrees.
                for (int zone = 0; zone < 4; zone++) {
                    int n = 0;
                    for (int i = 0; i < count; i++) if (zones[i] == zone) work[n++] = profiles[i][k];
                    if (Math.abs(median(work, n) - median[k]) > 0.03f) supported = false;
                }
            }
            if (!supported) continue;
            float mean = 0;
            for (float v : median) mean += v / 16f;
            for (int k = 0; k < 16; k++) {
                float gain = mean / median[k];
                if (!(gain >= 0.8f && gain <= 1.25f)) supported = false;
            }
            if (supported) for (int k = 0; k < 16; k++) gains[q * 16 + k] = mean / median[k];
        }
        return gains;
    }

    /** Smooth per-site field; nodes are interleaved by the 4x4 within-block phase. */
    public static final class GainMap {
        public static final int NX = 9, NY = 7;
        public final float[] values = new float[NX * 4 * NY * 4 * 4];
        public final boolean[] spatial = new boolean[4];
        public GainMap() { Arrays.fill(values, 1f); }
    }

    /**
     * Fits a low-order spatial response field to independent robust tile groups.
     * This estimates a response from the scene; it does not decode Samsung OTP.
     * Alternating spatial bins validate the fit before any local correction is used.
     */
    public static GainMap estimateMap(FloatBuffer grid, int width, int height) {
        GainMap result = new GainMap();
        float[] global = estimate(grid, width, height);
        int tw = width / 4, th = height / 4;
        final int bx = 8, by = 6;
        for (int q = 0; q < 4; q++) {
            double[][] observations = new double[bx * by][];
            for (int cy = 0; cy < by; cy++) for (int cx = 0; cx < bx; cx++) {
                int x0=cx*tw/bx, x1=(cx+1)*tw/bx, y0=cy*th/by, y1=(cy+1)*th/by;
                float[][] samples = new float[Math.max((x1-x0)*(y1-y0),1)][16];
                int n=0;
                for(int ty=y0;ty<y1;ty++)for(int tx=x0;tx<x1;tx++) {
                    float mean=0;
                    for(int k=0;k<16;k++) {
                        float v=grid.get(((ty*4+k/4)*width+tx*4+k%4)*4+q);
                        samples[n][k]=v; mean+=v/16f;
                    }
                    if(!(mean>.32f && mean<14f))continue;
                    boolean valid=true;
                    for(int k=0;k<16;k++) {
                        samples[n][k]/=mean;
                        if(!(samples[n][k]>.75f && samples[n][k]<1.25f))valid=false;
                    }
                    if(valid)n++;
                }
                if(n<12)continue;
                double[] o=new double[18];
                o[0]=2.0*(cx+.5)/bx-1; o[1]=2.0*(cy+.5)/by-1;
                float[] work=new float[n];
                for(int k=0;k<16;k++) {
                    for(int i=0;i<n;i++)work[i]=samples[i][k];
                    o[k+2]=median(work,n);
                }
                observations[cy*bx+cx]=o;
            }
            double[][] fit = new double[16][];
            boolean supported=true;
            double before=0,after=0;
            int train=0,test=0;
            for(int b=0;b<observations.length;b++)if(observations[b]!=null) {
                if(((b/bx+b%bx)&1)==0)train++;else test++;
            }
            supported=train>=16 && test>=16;
            for(int k=0;k<16 && supported;k++) {
                double[] provisional=fit(observations,bx,k,true);
                if(provisional==null){supported=false;break;}
                for(int b=0;b<observations.length;b++) {
                    double[] o=observations[b];
                    if(o==null || ((b/bx+b%bx)&1)==0)continue;
                    double actual=o[k+2], g=1.0/global[q*16+k];
                    before+=Math.abs(actual-g);
                    after+=Math.abs(actual-evaluate(provisional,o[0],o[1]));
                }
                fit[k]=fit(observations,bx,k,false);
                if(fit[k]==null)supported=false;
            }
            // Ignore weak/non-repeatable spatial trends and retain global fallback.
            supported &= before > test*16*.002 && after < before*.65;
            for(int ny=0;ny<GainMap.NY;ny++)for(int nx=0;nx<GainMap.NX;nx++) {
                float[] node=new float[16];float mean=0;
                for(int k=0;k<16;k++) {
                    float base=1f/global[q*16+k];
                    float value=supported?(float)evaluate(fit[k],2.0*nx/(GainMap.NX-1)-1,2.0*ny/(GainMap.NY-1)-1):base;
                    // Bounded innovation prevents local scene structure erasing texture.
                    node[k]=Math.max(base*.94f,Math.min(base*1.06f,value));mean+=node[k]/16f;
                }
                for(int k=0;k<16;k++) {
                    float gain=Math.max(.8f,Math.min(1.25f,mean/node[k]));
                    int pos=(((ny*4+k/4)*(GainMap.NX*4)+nx*4+k%4)*4+q);
                    result.values[pos]=gain;
                }
            }
            result.spatial[q]=supported;
        }
        return result;
    }

    private static double[] basis(double x,double y) {
        return new double[]{1,x,y,x*y,x*x-1.0/3,y*y-1.0/3};
    }
    private static double evaluate(double[] coeff,double x,double y) {
        double[] b=basis(x,y);double sum=0;
        for(int i=0;i<6;i++)sum+=coeff[i]*b[i];
        return sum;
    }
    private static double[] fit(double[][] observations,int width,int site,boolean training) {
        double[][] a=new double[6][7];
        for(int b=0;b<observations.length;b++) {
            double[] o=observations[b];
            if(o==null || (training && ((b/width+b%width)&1)!=0))continue;
            double[] v=basis(o[0],o[1]);
            for(int i=0;i<6;i++) {
                for(int j=0;j<6;j++)a[i][j]+=v[i]*v[j];
                a[i][6]+=v[i]*o[site+2];
            }
        }
        for(int i=0;i<6;i++) {
            int pivot=i;for(int j=i+1;j<6;j++)if(Math.abs(a[j][i])>Math.abs(a[pivot][i]))pivot=j;
            if(Math.abs(a[pivot][i])<1e-8)return null;
            double[] row=a[i];a[i]=a[pivot];a[pivot]=row;
            double d=a[i][i];for(int j=i;j<=6;j++)a[i][j]/=d;
            for(int r=0;r<6;r++)if(r!=i) {
                double scale=a[r][i];for(int j=i;j<=6;j++)a[r][j]-=scale*a[i][j];
            }
        }
        double[] result=new double[6];for(int i=0;i<6;i++)result[i]=a[i][6];return result;
    }

    private static float median(float[] values, int count) {
        Arrays.sort(values, 0, count);
        return (values[(count - 1) / 2] + values[count / 2]) * 0.5f;
    }
}
