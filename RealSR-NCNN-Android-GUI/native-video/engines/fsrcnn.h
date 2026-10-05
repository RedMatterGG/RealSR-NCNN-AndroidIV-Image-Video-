#pragma once
#include <net.h>
#include <gpu.h>
#include <algorithm>
#include <string>
#include <vector>
#include "fsrcnn_color.h"
// Persistent genuine NCNN network. Mat extract uploads/downloads internally on Vulkan.
class Fsrcnn {
 ncnn::Net net;
 std::string inputName,outputName;
 int tile;
public:
 Fsrcnn(int gpu,int threads,int tilesize):tile(tilesize) {
  net.opt.num_threads=threads;
  net.opt.use_vulkan_compute=gpu>=0;
  // Preserve reference FP32; the small graph is inexpensive and also CPU portable.
  net.opt.use_fp16_packed=false;net.opt.use_fp16_storage=false;net.opt.use_fp16_arithmetic=false;
  if(gpu>=0)net.set_vulkan_device(gpu);
 }
 int load(const std::string& param,const std::string& weights) {
  if(net.load_param(param.c_str()))return -1;
  if(net.load_model(weights.c_str()))return -2;
  auto ins=net.input_names(),outs=net.output_names();
  if(ins.size()!=1 || outs.size()!=1)return -3;
  inputName=ins[0];outputName=outs[0];return 0;
 }
 int process(const ncnn::Mat& in,ncnn::Mat& out) const {
  if(in.empty() || in.elempack!=3 || out.elempack!=3 || out.w!=in.w*2 || out.h!=in.h*2)return -4;
  int w=in.w,h=in.h;const auto* rgb=(const uint8_t*)in.data;
  auto* result=(uint8_t*)out.data;std::vector<uint8_t> ycc(size_t(w)*h*3);
  for(size_t i=0;i<ycc.size();i+=3)video::rgbYcc(rgb+i,ycc.data()+i);
  // Radius 3: 5x5 extraction + single 3x3 mapping. Context crop avoids tile seams.
  for(int y=0;y<h;y+=tile)for(int x=0;x<w;x+=tile) {
   int x0=std::max(0,x-3),y0=std::max(0,y-3),x1=std::min(w,x+tile+3),y1=std::min(h,y+tile+3);
   ncnn::Mat lum(x1-x0,y1-y0,1);if(lum.empty())return -5;
   for(int j=y0;j<y1;j++)for(int i=x0;i<x1;i++)lum.row(j-y0)[i-x0]=ycc[(j*w+i)*3]/255.f;
   auto ex=net.create_extractor();if(ex.input(inputName.c_str(),lum))return -6;
   ncnn::Mat prediction;if(ex.extract(outputName.c_str(),prediction))return -7;
   if(prediction.empty() || prediction.w!=(x1-x0)*2 || prediction.h!=(y1-y0)*2 || prediction.c!=1 || prediction.elempack!=1 || prediction.elemsize!=4)return -8;
   for(int j=y*2;j<std::min(h,y+tile)*2;j++)for(int i=x*2;i<std::min(w,x+tile)*2;i++) {
    float v=prediction.row(j-y0*2)[i-x0*2];if(!std::isfinite(v))return -9;
    uint8_t luminance=video::clampByte(int(std::max(0.f,std::min(255.f,v*255.f))));
    video::yccRgb(luminance,video::chroma2x(ycc.data(),w,h,i,j,1),video::chroma2x(ycc.data(),w,h,i,j,2),result+(size_t(j)*w*2+i)*3);
   }
  }
  return 0;
 }
};
