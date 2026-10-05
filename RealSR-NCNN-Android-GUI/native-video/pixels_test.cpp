#include "pixels.h"
#include "engines/process_policy.h"
#include <cassert>
#include <iostream>
int main() {
 for (int tile : {32, 128}) {
  assert(!video::cuganSyncNeeded(256,64,tile,3));
  assert(!video::cuganSyncNeeded(64,256,tile,3));
 }
 assert(video::cuganSyncNeeded(256,65,128,3));
 assert(video::cuganSyncNeeded(256,64,128,1));
 assert(!video::cuganSyncNeeded(64,64,128,3));
 uint8_t src[]={255,0,0,255,0,255,0,255,99,99,99,99,0,0,255,255,11,22,33,255,88,88,88,88};
 uint8_t rgb[12]={}, dst[24]={};
 video::rgbaToRgb(src,12,rgb,2,2);
 uint8_t expected[]={255,0,0,0,255,0,0,0,255,11,22,33};
 for(int i=0;i<12;i++) assert(rgb[i]==expected[i]);
 video::rgbToRgba(rgb,dst,12,2,2);
 for(int y=0;y<2;y++) for(int x=0;x<8;x++) assert(dst[y*12+x]==src[y*12+x]);
 assert(dst[8]==0 && dst[20]==0);
 assert(video::rgbSize(20,30,2)==1800);
 for(int n=0;n<4;n++) {
  bool thrown=false;
  try { if(n==0) video::rgbSize(0,20,2); if(n==1) video::rgbSize(8192,8192,4); if(n==2) video::rgbaToRgb(src,7,rgb,2,2); if(n==3){src[3]=0;video::rgbaToRgb(src,12,rgb,2,2);} }
  catch(const std::runtime_error&) {thrown=true;}
  assert(thrown);
 }
 std::cout << "Native pixel tests PASS: RGB order, stride, alpha, bounds\n";
}
