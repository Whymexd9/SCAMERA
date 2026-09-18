import java.util.*;
import com.particlesdevs.photoncamera.capture.HexQuadZslSelector;
public class HexQuadZslSelectorCheck {
    static List<HexQuadZslSelector.Sample> samples(int n){
        List<HexQuadZslSelector.Sample> a=new ArrayList<>();
        for(int i=0;i<n;i++)a.add(new HexQuadZslSelector.Sample(1_000_000_000L+i*33_000_000L,10_000_000L,800));return a;
    }
    public static void main(String[] args){
        assert HexQuadZslSelector.select(samples(5)).length==0;
        assert Arrays.equals(HexQuadZslSelector.select(samples(8)),new int[]{2,3,4,5,6,7});
        List<HexQuadZslSelector.Sample> a=samples(8);
        a.set(7,new HexQuadZslSelector.Sample(a.get(7).timestamp,0,0));
        assert Arrays.equals(HexQuadZslSelector.select(a),new int[]{1,2,3,4,5,6});
        for(int kind=0;kind<5;kind++){
            a=samples(8);HexQuadZslSelector.Sample old=a.get(4);
            a.set(4,new HexQuadZslSelector.Sample(kind==2?a.get(3).timestamp:old.timestamp,
                kind==0?9_000_000L:kind==3?0:old.exposure,kind==1?400:kind==4?20000:800));
            assert HexQuadZslSelector.select(a).length==0;
        }
        a=samples(6);a.set(0,new HexQuadZslSelector.Sample(1L,10_000_000L,800));
        assert HexQuadZslSelector.select(a).length==0;
        System.out.println("HexQuad ZSL selection: six distinct chronological equal measured exposures; unpaired, changing AE, stale frames rejected PASS");
    }
}
