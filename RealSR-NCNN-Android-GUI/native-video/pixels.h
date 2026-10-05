#pragma once
#include <cstddef>
#include <cstdint>
#include <stdexcept>
namespace video {
inline size_t rgbSize(unsigned w, unsigned h, int scale) {
 if (!w || !h || scale < 1 || scale > 4 || uint64_t(w)*scale > 8192 || uint64_t(h)*scale > 8192 || uint64_t(w)*h*scale*scale > 16777216)
  throw std::runtime_error("Frame exceeds supported 8192-axis / 16MP output limits");
 return size_t(w)*h*3;
}
inline void rgbaToRgb(const uint8_t* rgba, size_t stride, uint8_t* rgb, unsigned w, unsigned h) {
 if (stride < size_t(w)*4) throw std::runtime_error("Invalid bitmap row stride");
 for (unsigned y=0;y<h;y++) for(unsigned x=0;x<w;x++) {
  const uint8_t* p=rgba+y*stride+x*4;
  uint8_t* q=rgb+(size_t(y)*w+x)*3;
  // Video frames are opaque; reject transparency rather than feed premultiplied colors.
  if (p[3]!=255) throw std::runtime_error("Video frame must be opaque RGBA");
  q[0]=p[0]; q[1]=p[1]; q[2]=p[2];
 }
}
inline void rgbToRgba(const uint8_t* rgb, uint8_t* rgba, size_t stride, unsigned w, unsigned h) {
 if (stride < size_t(w)*4) throw std::runtime_error("Invalid bitmap row stride");
 for(unsigned y=0;y<h;y++) for(unsigned x=0;x<w;x++) {
  const uint8_t* p=rgb+(size_t(y)*w+x)*3; uint8_t* q=rgba+y*stride+x*4;
  q[0]=p[0];q[1]=p[1];q[2]=p[2];q[3]=255;
 }
}
}
