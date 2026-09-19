"""Execute the exact bundled model on host CPU; pip install ai-edge-litert numpy."""
from pathlib import Path
import hashlib
import numpy as np
from ai_edge_litert.interpreter import Interpreter
model=Path('app/src/main/assets/models/arkcam_saliency.tflite')
assert hashlib.sha256(model.read_bytes()).hexdigest()=='a0bf6e5c324ad3a24b345970beb861bbe53f0f99223ec61900183ff639bc864a'
i=Interpreter(model_path=str(model),num_threads=2);i.allocate_tensors()
a=i.get_input_details()[0];o=i.get_output_details()[0]
assert list(a['shape'])==[1,384,512,3] and list(o['shape'])==[1,384,512,1]
outputs=[]
for object_present in [False,True]:
    x=np.full(a['shape'],.15,np.float32)
    if object_present:x[:,100:280,190:340,:]=.8
    i.set_tensor(a['index'],x);i.invoke();y=i.get_tensor(o['index']);outputs.append(y.copy())
    assert np.isfinite(y).all() and y.min()>=0 and y.max()<=1
assert np.abs(outputs[1]-outputs[0]).mean()>.001,'Model does not react to input'
assert outputs[1][0,120:260,210:320,0].mean()>outputs[1][0,:50,:,0].mean(),'Object not distinguished from background'
print('Google saliency model PASS: exact bytes, tensor contract, CPU inference, finite mask, object response')
