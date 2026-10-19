import struct, numpy as np, sys
def read(p):
    d=open(p,'rb').read(); assert d[:4]==b'SFD1'; n=struct.unpack_from('<I',d,4)[0]; o=8; out={}
    for _ in range(n):
        l=d[o]; o+=1; name=d[o:o+l].decode(); o+=l; dt,nd=d[o],d[o+1]; o+=2
        dims=struct.unpack_from('<%dI'%nd,d,o); o+=4*nd; cnt=int(np.prod(dims)); sz=2 if dt==1 else 4
        out[name]=np.frombuffer(d,'<f2' if dt==1 else '<f4',cnt,o).reshape(dims).astype(np.float64); o+=cnt*sz
    return out
if __name__=='__main__':
    for p in sys.argv[1:]:
        a=read(p)
        for k,v in a.items(): print(p.split('/')[-1],k,v.shape,'%.3g %.3g'%(np.nanmin(v),np.nanmax(v)))
