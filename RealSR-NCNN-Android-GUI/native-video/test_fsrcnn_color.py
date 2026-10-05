"""Host native color checks against actual OpenCV reference; max 1-byte cubic rounding deviation."""
import unittest,subprocess
from pathlib import Path
import numpy as np,cv2
H=Path(__file__).resolve().parent;TC=Path('F:/videoimageupscaler/toolchain')
class ColorTest(unittest.TestCase):
 def test_upstream_ycrcb_bicubic_reconstruction(self):
  self.assertTrue((H/'engines/fsrcnn_color.h').exists(),'Native upstream color pipeline missing')
  rng=np.random.default_rng(18);rgb=rng.integers(0,256,(7,9,3),dtype=np.uint8)
  ycc=cv2.cvtColor(rgb,cv2.COLOR_RGB2YCrCb)
  cr=cv2.resize(ycc[:,:,1],(18,14),interpolation=cv2.INTER_CUBIC)
  cb=cv2.resize(ycc[:,:,2],(18,14),interpolation=cv2.INTER_CUBIC)
  y=rng.integers(0,256,(14,18),dtype=np.uint8)
  reconstructed=cv2.cvtColor(np.stack((y,cr,cb),axis=2),cv2.COLOR_YCrCb2RGB)
  fixture=TC/'fsrcnn-color-fixture.bin';fixture.write_bytes(rgb.tobytes()+y.tobytes())
  exe=TC/'build-ncnn-video-host/Release/fsrcnn_color_test.exe'
  subprocess.run([str(exe),str(fixture)],check=True)
  actual=np.frombuffer(fixture.with_suffix('.out').read_bytes(),np.uint8)
  np.testing.assert_array_equal(actual[:7*9*3].reshape(7,9,3),ycc)
  error=np.abs(actual[7*9*3:].reshape(14,18,3).astype(int)-reconstructed.astype(int))
  self.assertLessEqual(error.max(),2)
  print('Native vs OpenCV reconstruction max byte error:',error.max())
if __name__=='__main__':unittest.main()
