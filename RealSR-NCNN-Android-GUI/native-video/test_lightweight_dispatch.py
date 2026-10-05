"""Source contract RED before wiring the new genuine persistent engine."""
from pathlib import Path
import unittest
H=Path(__file__).resolve().parent
class DispatchTest(unittest.TestCase):
 def test_native_waifu_dispatch(self):
  s=(H/'video_jni.cpp').read_text()
  self.assertIn('std::unique_ptr<Waifu2x>',s)
  self.assertIn('kind=="waifu2x-ncnn"',s)
  self.assertIn('e->waifu->process(in,out)',s)
 def test_native_fsrcnn_dispatch(self):
  s=(H/'video_jni.cpp').read_text()
  self.assertIn('std::unique_ptr<Fsrcnn>',s)
  self.assertIn('kind=="fsrcnn-ncnn"',s)
  self.assertIn('e->fsrcnn->process(in,out)',s)
if __name__=='__main__':unittest.main()
