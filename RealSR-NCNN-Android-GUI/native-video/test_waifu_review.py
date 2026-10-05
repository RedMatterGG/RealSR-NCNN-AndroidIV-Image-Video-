from pathlib import Path
import re,unittest
D=Path(__file__).resolve().parent/'engines/Waifu2x'
class WaifuReviewRegression(unittest.TestCase):
 def test_float_predictions_are_clamped_before_unsigned_conversion(self):
  for name in ['waifu2x_postproc.comp','waifu2x_postproc_tta.comp']:
   text=(D/name).read_text()
   self.assertNotRegex(text,r'clamp\(uint\(floor\(')
   self.assertIn('uint(clamp(floor(v), 0.0, 255.0))',text)
if __name__=='__main__':unittest.main()
