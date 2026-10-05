#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
namespace video {
inline uint8_t clampByte(int x) { return uint8_t(std::max(0,std::min(255,x))); }
inline int descale(int x) { return (x+8192)>>14; }
inline void rgbYcc(const uint8_t* rgb,uint8_t* ycc) {
 int y=descale(rgb[0]*4899+rgb[1]*9617+rgb[2]*1868);
 ycc[0]=uint8_t(y);ycc[1]=clampByte(descale((rgb[0]-y)*11682+(128<<14)));
 ycc[2]=clampByte(descale((rgb[2]-y)*9241+(128<<14)));
}
inline void yccRgb(uint8_t y,uint8_t cr,uint8_t cb,uint8_t* rgb) {
 int r=int(cr)-128,b=int(cb)-128;
 rgb[0]=clampByte(y+descale(r*22987));
 rgb[1]=clampByte(y+descale(r*-11698+b*-5636));
 rgb[2]=clampByte(y+descale(b*29049));
}
inline float cubic(float x) {
 x=std::abs(x);const float a=-0.75f;
 return x<=1?((a+2)*x-(a+3))*x*x+1:x<2?((a*x-5*a)*x+8*a)*x-4*a:0;
}
inline uint8_t chroma2x(const uint8_t* ycc,int w,int h,int ox,int oy,int c) {
 float fx=(ox+0.5f)*0.5f-0.5f,fy=(oy+0.5f)*0.5f-0.5f;
 int ix=int(std::floor(fx)),iy=int(std::floor(fy));float total=0;
 for(int j=-1;j<=2;j++)for(int i=-1;i<=2;i++) {
  int x=std::max(0,std::min(w-1,ix+i)),y=std::max(0,std::min(h-1,iy+j));
  total+=ycc[(y*w+x)*3+c]*cubic(fx-(ix+i))*cubic(fy-(iy+j));
 }
 return clampByte(int(std::nearbyint(total)));
}
}
