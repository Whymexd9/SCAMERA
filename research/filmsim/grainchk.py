import sys
sys.path.insert(0, r'C:/Users/MECHREVO/Downloads/x200u/SCAMERA-PC/evidence/spektrafilm/src/src'); sys.path.append(r'C:/Users/MECHREVO/AppData/Local/Temp/claude/C--Users-MECHREVO-Downloads-x200u/b81dc619-c8a9-41ca-bbae-ac070a033320/scratchpad/sf/stubs')
import numpy as np
from spektrafilm.profiles.io import load_profile
from spektrafilm.model.grain import apply_grain_to_density_layers
from spektrafilm.model.density_curves import interp_density_cmy_layers
from spektrafilm.runtime.params_schema import GrainParams
pr=load_profile('kodak_portra_400'); g=GrainParams()
dc=np.asarray(pr.data.density_curves); dc=dc-dc.min(0); dcl=np.asarray(pr.data.density_curves_layers)
print('dcl shape',dcl.shape)
um=6.0
for d in [0.2,0.7,1.2,1.8]:
    dens=np.full((256,256,3),d)
    lay=interp_density_cmy_layers(dens,dc,dcl)
    out=apply_grain_to_density_layers(lay.copy(),density_max_layers=np.nanmax(dcl,axis=0),pixel_size_um=um,particle_area_um2=g.particle_area_um2,
        particle_scale=g.particle_scale,particle_scale_layers=g.particle_scale_layers,density_min=g.density_min,grain_uniformity=g.uniformity,
        grain_blur=0.0,grain_blur_dye_clouds_um=0.0,grain_micro_structure=(0,0))
    # formula
    dml=np.nanmax(dcl,axis=0); tot=dml.sum(0); frac=dml/tot; dminl=frac*np.array(g.density_min)[None,:]; dmaxl=dml+dminl
    nl=um*um*frac/(g.particle_area_um2*np.array(g.particle_scale)[None,:]*np.array(g.particle_scale_layers)[:,None])
    x=lay[0,0]+dminl  # layers x rgb
    p=np.clip(x/dmaxl,1e-6,1-1e-6); sat=1-p*np.array(g.uniformity)[None,:]*(1-1e-6); od=dmaxl/nl
    var=(x*od*sat).sum(0)
    print(d,'mc std',out.std((0,1)).round(4),'formula',np.sqrt(var).round(4),'mean',out.mean((0,1)).round(3))
