"""Compile pinned TF FSRCNN-small graph to NCNN; preserve genuine FP32 tensors.
Run with the private fsrcnn-convert environment. No training/random weights.
The final shared bias is replicated before PixelShuffle (algebraically identical).
"""
from pathlib import Path
import struct,hashlib,json
import numpy as np
import tensorflow as tf
H=Path(__file__).resolve().parent
source=H/'model-source/fsrcnn/models_FSRCNN-small_x2.pb'
assert hashlib.sha256(source.read_bytes()).hexdigest()=='429e4793d049c1ae16ddbbc322fd11c3c08831c0c20137390b4d098976a2b0d9', 'Pinned pretrained TensorFlow graph checksum mismatch'
g=tf.compat.v1.GraphDef();g.ParseFromString(source.read_bytes())
a={n.name:tf.make_ndarray(n.attr['value'].tensor) for n in g.node if n.op=='Const'}
assert [a[f'f{i}'].shape for i in range(1,6)]==[(5,5,1,32),(1,1,32,5),(3,3,5,5),(1,1,5,32),(1,1,32,4)]
assert next(n for n in g.node if n.name=='DepthToSpace').attr['block_size'].i==2
lines=['Input input 0 1 input'];data=bytearray();previous='input'
for i in range(1,6):
 w=a[f'f{i}'].transpose(3,2,0,1).astype('<f4');oc,ic,k,_=w.shape
 bias=a[f'b{i}'] if i<5 else np.repeat(a['b5'],4)
 blob=f'conv{i}'
 lines.append(f'Convolution conv{i} 1 1 {previous} {blob} 0={oc} 1={k} 4={k//2} 5=1 6={w.size}')
 data.extend(struct.pack('<I',0));data.extend(w.tobytes());data.extend(np.asarray(bias,dtype='<f4').tobytes())
 previous=blob
 if i<5:
  blob=f'prelu{i}';lines.append(f'PReLU prelu{i} 1 1 {previous} {blob} 0={oc}')
  data.extend(a[f'alpha{i}'].astype('<f4').tobytes());previous=blob
lines.append(f'PixelShuffle shuffle 1 1 {previous} output 0=2')
model=H.parent/'app/src/main/assets/realsr/models-FSRCNN-small';model.mkdir(parents=True,exist_ok=True)
(model/'x2.param').write_text('7767517\n'+f'{len(lines)} {len(lines)}\n'+'\n'.join(lines)+'\n',encoding='ascii')
(model/'x2.bin').write_bytes(data)
manifest={'upstream':'https://github.com/Saafke/FSRCNN_Tensorflow','commit':'6a4812c4ef1c4f5947d79beafa32a05a6eb4a94d','source_path':'models/FSRCNN-small_x2.pb','license':'Apache-2.0 (upstream repository LICENSE)','architecture':'d=32,s=5,m=1, normalized Y, subpixel 2x; NOT transposed-convolution FSRCNN','input':'input: planar float32 Y / 255 from RGB-to-YCrCb (8-bit rounded Y)','output':'output: planar float32 Y, native 2x; clip*255 truncate, bicubic 8-bit Cr/Cb, YCrCb-to-RGB','compiler':'convert_fsrcnn.py; TensorFlow 2.20.0, numpy 2.2.6; HWIO->OIHW, PReLU, final bias folded before PixelShuffle','sha256':{p.relative_to(H.parent).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in [source,model/'x2.param',model/'x2.bin']}}
(model/'PROVENANCE.json').write_text(json.dumps(manifest,indent=2)+'\n')
(model/'LICENSE').write_bytes((H/'model-source/fsrcnn/LICENSE').read_bytes())
print(json.dumps(manifest,indent=2))
