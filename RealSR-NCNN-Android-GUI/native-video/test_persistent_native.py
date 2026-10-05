"""Exercise production C++ engines with pinned NCNN20250916 on Windows; not Android proof."""
from pathlib import Path
import subprocess, unittest
import numpy as np,cv2
H=Path(__file__).resolve().parent;TC=Path('F:/videoimageupscaler/toolchain');M=H.parent/'app/src/main/assets/realsr'
EXE=TC/'build-ncnn-lightweight-host/Release/lightweight_native_test.exe'
class PersistentNativeTest(unittest.TestCase):
 def run_engine(self,kind,model,stem,backend,noise=0,data=None):
  self.assertTrue(EXE.exists(),'Production C++ native runner missing')
  if data is None:data=np.random.default_rng(41).integers(0,256,(29,37,3),dtype=np.uint8)
  f=TC/f'{kind}-{model}-{stem}-{backend}.rgb';f.write_bytes(data.tobytes())
  subprocess.run([str(EXE),kind,str(M/model),stem,backend,str(noise),str(f)],check=True)
  out=np.frombuffer(f.with_suffix('.out').read_bytes(),np.uint8)
  return data,out
 def test_fsrcnn_cpu_matches_tensorflow_and_opencv(self):
  import tensorflow as tf
  rgb,actual=self.run_engine('fsrcnn','models-FSRCNN-small','x2','cpu')
  g=tf.compat.v1.GraphDef();g.ParseFromString((H/'model-source/fsrcnn/models_FSRCNN-small_x2.pb').read_bytes())
  graph=tf.Graph()
  with graph.as_default():tf.import_graph_def(g,name='')
  ycc=cv2.cvtColor(rgb,cv2.COLOR_RGB2YCrCb)
  with tf.compat.v1.Session(graph=graph) as sess:
   y=sess.run(graph.get_tensor_by_name('NHWC_output:0'),{graph.get_tensor_by_name('IteratorGetNext:0'):(ycc[:,:,0].astype(np.float32)/255)[None,:,:,None]})[0,:,:,0]
  cr=cv2.resize(ycc[:,:,1],(74,58),interpolation=cv2.INTER_CUBIC);cb=cv2.resize(ycc[:,:,2],(74,58),interpolation=cv2.INTER_CUBIC)
  ref=cv2.cvtColor(np.stack(((y*255).clip(0,255).astype(np.uint8),cr,cb),axis=2),cv2.COLOR_YCrCb2RGB)
  diff=np.abs(actual.reshape(58,74,3).astype(int)-ref.astype(int));print('FSRCNN production C++ vs TF/OpenCV max byte error',diff.max())
  self.assertLessEqual(diff.max(),2)
 def test_all_waifu_official_cpu_models_repeat_and_tile_equivalence(self):
  for model in ['models-upconv_7_anime_style_art_rgb','models-upconv_7_photo']:
   for noise in [-1,0,1,2,3]:
    stem='scale2.0x_model' if noise==-1 else f'noise{noise}_scale2.0x_model'
    rgb,out=self.run_engine('waifu',model,stem,'cpu',noise);self.assertEqual(out.size,rgb.size*4)
    self.assertFalse(np.array_equal(out.reshape(58,74,3),cv2.resize(rgb,(74,58),interpolation=cv2.INTER_CUBIC)))
 def test_general_x4_existing_realsr_graph_and_process(self):
  rgb,out=self.run_engine('general','models-RealeSR-general-v3','x4','cpu');self.assertEqual(out.size,rgb.size*16)
 def test_waifu_black_and_dark_edges_saturate_cpu_vulkan(self):
  for dark_edge in [False,True]:
   data=np.zeros((29,37,3),np.uint8)
   if dark_edge:data[:,18:]=8
   _,cpu=self.run_engine('waifu','models-upconv_7_anime_style_art_rgb','scale2.0x_model','cpu',-1,data)
   _,gpu=self.run_engine('waifu','models-upconv_7_anime_style_art_rgb','scale2.0x_model','vulkan',-1,data)
   difference=np.abs(cpu.astype(int)-gpu.astype(int)).max()
   self.assertLessEqual(difference,2)
   if not dark_edge:self.assertEqual(int(gpu.max()),0)
   print('Waifu black/dark-edge CPU/Vulkan max difference',difference)
 def test_new_engines_real_host_vulkan_repeat(self):
  for kind,model,stem in [('fsrcnn','models-FSRCNN-small','x2'),('general','models-RealeSR-general-v3','x4')]:
   self.run_engine(kind,model,stem,'vulkan')
  for model in ['models-upconv_7_anime_style_art_rgb','models-upconv_7_photo']:
   for noise in [-1,0,1,2,3]:
    stem='scale2.0x_model' if noise==-1 else f'noise{noise}_scale2.0x_model'
    self.run_engine('waifu',model,stem,'vulkan',noise)
if __name__=='__main__':unittest.main()
