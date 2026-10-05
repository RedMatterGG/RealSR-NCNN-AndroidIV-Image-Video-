"""Real TensorFlow frozen graph versus loaded NCNN graph, no synthetic weights."""
from pathlib import Path
import unittest
import numpy as np
import tensorflow as tf
import ncnn
HERE=Path(__file__).resolve().parent
MODEL=HERE.parent/'app/src/main/assets/realsr/models-FSRCNN-small'
class ConversionTest(unittest.TestCase):
 def test_pretrained_graph_matches_tensorflow_repeated_dynamic_shapes(self):
  self.assertTrue((MODEL/'x2.param').is_file(), 'Genuine compiled NCNN model missing')
  g=tf.compat.v1.GraphDef();g.ParseFromString((HERE/'model-source/fsrcnn/models_FSRCNN-small_x2.pb').read_bytes())
  graph=tf.Graph()
  with graph.as_default(): tf.import_graph_def(g,name='')
  with tf.compat.v1.Session(graph=graph) as sess,ncnn.Net() as net:
   net.opt.use_vulkan_compute=False;net.opt.num_threads=2
   self.assertEqual(net.load_param(str(MODEL/'x2.param')),0)
   self.assertEqual(net.load_model(str(MODEL/'x2.bin')),0)
   for h,w in [(9,13),(24,32),(7,5)]:
    x=np.random.default_rng(42).random((h,w),dtype=np.float32)
    expected=sess.run(graph.get_tensor_by_name('NCHW_output:0'),{graph.get_tensor_by_name('IteratorGetNext:0'):x[None,:,:,None]})[0]
    for _ in range(3):
     with net.create_extractor() as ex:
      pixels=x[None].copy();mat=ncnn.Mat(pixels).clone()
      self.assertEqual(ex.input('input',mat),0)
      status,out=ex.extract('output');self.assertEqual(status,0)
      actual=np.array(out)
      self.assertEqual(actual.shape,(1,h*2,w*2))
      np.testing.assert_allclose(actual,expected,rtol=2e-4,atol=2e-5)
if __name__=='__main__':unittest.main()
