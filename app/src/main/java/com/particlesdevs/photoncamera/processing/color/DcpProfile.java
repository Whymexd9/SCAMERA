package com.particlesdevs.photoncamera.processing.color;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;

/** Matrix-only DNG camera profile. Matrices are row-major; output is camera RAW to XYZ D50. */
public final class DcpProfile {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private float[] color1, color2, forward1, forward2;
    private int light1 = 21, light2 = 21;
    public boolean hasLookTables;
    private static final float[] D50 = {.9642f, 1, .8249f};
    private static final float[] BRADFORD = {.8951f,.2664f,-.1614f,-.7502f,1.7135f,.0367f,.0389f,-.0685f,1.0296f};

    public static DcpProfile parse(byte[] bytes) throws IOException {
        if (bytes.length < 8 || bytes.length > MAX_BYTES) throw invalid("Размер файла");
        ByteOrder order;
        if (bytes[0]=='I' && bytes[1]=='I') order=ByteOrder.LITTLE_ENDIAN;
        else if (bytes[0]=='M' && bytes[1]=='M') order=ByteOrder.BIG_ENDIAN;
        else throw invalid("Заголовок TIFF/DCP");
        ByteBuffer b=ByteBuffer.wrap(bytes).order(order);
        int magic=b.getShort(2)&65535;
        if (magic!=42 && magic!=0x4352) throw invalid("Сигнатура DCP");
        DcpProfile p=new DcpProfile();
        long offset=u32(b,4); Set<Long> visited=new HashSet<>();
        while(offset!=0) {
            if (visited.size()>=16 || !visited.add(offset)) throw invalid("Цикл IFD");
            int pos=range(offset,2,bytes.length), count=b.getShort(pos)&65535;
            range(offset+2,12L*count+4,bytes.length);
            for(int i=0;i<count;i++) {
                int e=pos+2+12*i,tag=b.getShort(e)&65535,type=b.getShort(e+2)&65535;
                long n=u32(b,e+4);
                if(tag==50937 || tag==50938 || tag==50939 || tag==50940 || tag==50981 || tag==50982) p.hasLookTables=true;
                if(tag==50778 || tag==50779) {
                    if(type!=3 || n!=1) throw invalid("Тип источника освещения");
                    int light=b.getShort(e+8)&65535;
                    temperature(light); // reject unsupported custom illuminants, never guess silently
                    if(tag==50778)p.light1=light;else p.light2=light;
                }
                if(tag==50721 || tag==50722 || tag==50964 || tag==50965) {
                    if(n!=9 || (type!=5 && type!=10)) throw invalid("Требуется матрица 3×3");
                    int at=range(u32(b,e+8),72,bytes.length); float[] matrix=new float[9];
                    for(int j=0;j<9;j++) {
                        double num=type==10?b.getInt(at+j*8):u32(b,at+j*8);
                        double den=type==10?b.getInt(at+j*8+4):u32(b,at+j*8+4);
                        if(den==0 || !Double.isFinite(num/den) || Math.abs(num/den)>100)throw invalid("Число в матрице");
                        matrix[j]=(float)(num/den);
                    }
                    try { inverse(matrix); } catch(IllegalArgumentException ex) { throw invalid("Вырожденная матрица"); }
                    if(tag==50721)p.color1=matrix;else if(tag==50722)p.color2=matrix;
                    else if(tag==50964)p.forward1=matrix;else p.forward2=matrix;
                }
            }
            offset=u32(b,pos+2+count*12);
        }
        if(p.color1==null)throw invalid("Нет ColorMatrix1");
        if(p.color2==null){p.color2=p.color1;p.light2=p.light1;p.forward2=p.forward1;}
        return p;
    }
    private static IOException invalid(String why){return new IOException("Неверный DCP: "+why);}
    private static long u32(ByteBuffer b,int p){return b.getInt(p)&0xffffffffL;}
    private static int range(long p,long n,int limit)throws IOException{
        if(p<0 || n<0 || p>limit || n>limit-p)throw invalid("Данные за границей файла");return (int)p;
    }
    private static int temperature(int light)throws IOException{
        switch(light){case 1:case 21:return 6504;case 17:case 3:return 2856;case 18:return 4874;
            case 19:return 6774;case 20:return 5503;case 22:return 7504;case 23:return 5003;
            case 9:return 5500;case 10:return 6500;case 11:return 7500;case 12:return 6430;
            case 13:return 5000;case 14:return 4230;case 15:return 3450;case 16:return 2925;
            default:throw invalid("Неизвестный источник освещения: "+light);}
    }
    /** Camera neutral before white balance, e.g. [green/red,1,green/blue]. */
    public float[] cameraToXyz(float[] neutral) {
        if(neutral==null||neutral.length!=3)throw new IllegalArgumentException("neutral");
        for(float v:neutral)if(!Float.isFinite(v)||v<=0)throw new IllegalArgumentException("neutral");
        double t=.5;
        try {
            double a=temperature(light1),z=temperature(light2);
            for(int i=0;i<16 && a!=z;i++) {
                float[] xyz=map(inverse(lerp(color1,color2,t)),neutral);
                double sum=xyz[0]+xyz[1]+xyz[2],x=xyz[0]/sum,y=xyz[1]/sum;
                double n=(x-.3320)/(.1858-y);
                double kelvin=Math.max(1667,Math.min(25000,449*n*n*n+3525*n*n+6823.3*n+5520.33));
                double next=(1/kelvin-1/a)/(1/z-1/a);
                if(!Double.isFinite(next))throw new IllegalArgumentException("Invalid white point");
                next=Math.max(0,Math.min(1,next));
                if(Math.abs(t-next)<.0001){t=next;break;}t=(t+next)/2;
            }
        }catch(IOException ex){throw new IllegalArgumentException(ex);}
        if(forward1!=null && forward2!=null){
            float[] f=lerp(forward1,forward2,t);
            // A forward matrix maps a balanced neutral to D50. Normalize small profile rounding errors.
            for(int r=0;r<3;r++){
                float sum=f[r*3]+f[r*3+1]+f[r*3+2];
                if(Math.abs(sum)<1e-6)throw new IllegalArgumentException("Invalid forward white point");
                for(int c=0;c<3;c++)f[r*3+c]*=D50[r]/sum/neutral[c];
            }
            return f;
        }
        float[] toXyz=inverse(lerp(color1,color2,t)),white=map(toXyz,neutral);
        float[] cone=map(BRADFORD,white),target=map(BRADFORD,D50);
        float[] scale=new float[9];
        for(int i=0;i<3;i++){if(cone[i]<=1e-6)throw new IllegalArgumentException("Invalid profile neutral");scale[i*4]=target[i]/cone[i];}
        return multiply(multiply(inverse(BRADFORD),multiply(scale,BRADFORD)),toXyz);
    }
    private static float[] lerp(float[] a,float[] b,double t){float[] r=new float[9];for(int i=0;i<9;i++)r[i]=(float)(a[i]*(1-t)+b[i]*t);return r;}
    public static float[] multiply(float[] a,float[] b){float[] r=new float[9];for(int y=0;y<3;y++)for(int x=0;x<3;x++)for(int k=0;k<3;k++)r[y*3+x]+=a[y*3+k]*b[k*3+x];return r;}
    private static float[] map(float[] a,float[] v){return new float[]{a[0]*v[0]+a[1]*v[1]+a[2]*v[2],a[3]*v[0]+a[4]*v[1]+a[5]*v[2],a[6]*v[0]+a[7]*v[1]+a[8]*v[2]};}
    private static float[] inverse(float[] m){
        float[] r={m[4]*m[8]-m[5]*m[7],m[2]*m[7]-m[1]*m[8],m[1]*m[5]-m[2]*m[4],
            m[5]*m[6]-m[3]*m[8],m[0]*m[8]-m[2]*m[6],m[2]*m[3]-m[0]*m[5],
            m[3]*m[7]-m[4]*m[6],m[1]*m[6]-m[0]*m[7],m[0]*m[4]-m[1]*m[3]};
        float det=m[0]*r[0]+m[1]*r[3]+m[2]*r[6];
        if(!Float.isFinite(det)||Math.abs(det)<1e-8)throw new IllegalArgumentException("Singular matrix");
        for(int i=0;i<9;i++)r[i]/=det;return r;
    }
}
