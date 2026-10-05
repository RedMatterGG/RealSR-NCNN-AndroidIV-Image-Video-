#include "engines/fsrcnn.h"
#include "engines/Waifu2x/waifu2x.h"
#include "engines/RealSR/realsr.h"
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
class FailWorkspace : public ncnn::Allocator {
 int target, calls=0;
public:
 explicit FailWorkspace(int n):target(n){}
 void* fastMalloc(size_t size) override {return ++calls==target ? nullptr : ncnn::fastMalloc(size);}
 void fastFree(void* ptr) override {ncnn::fastFree(ptr);}
};
void check(bool b,const char* message){if(!b)throw std::runtime_error(message);}
int main(int argc,char** argv) {
 try {
  check(argc==7,"Usage: kind model stem cpu|vulkan noise fixture");
  std::string kind=argv[1],path=std::string(argv[2])+"/"+argv[3],backend=argv[4];int gpu=-1,noise=std::stoi(argv[5]);
  if(backend=="vulkan") {
   check(ncnn::create_gpu_instance()==0 && ncnn::get_gpu_count()>0,"No usable host Vulkan GPU");gpu=ncnn::get_default_gpu_index();
   std::cout<<"Actual host Vulkan: "<<ncnn::get_gpu_info(gpu).device_name()<<std::endl;
  }
  std::vector<uint8_t> rgb(37*29*3),result(rgb.size()*(kind=="general"?16:4)),first;
  std::ifstream f(argv[6],std::ios::binary);f.read((char*)rgb.data(),rgb.size());check(bool(f),"Fixture failed");
  int scale=kind=="general"?4:2;
  ncnn::Mat in(37,29,rgb.data(),size_t(3),3),out(37*scale,29*scale,result.data(),size_t(3),3);
  std::unique_ptr<Fsrcnn> fs;std::unique_ptr<Waifu2x> waifu;std::unique_ptr<RealSR> sr;
  if(kind=="fsrcnn") {
   {Fsrcnn bad(gpu,2,32);check(bad.load("missing.param","missing.bin")!=0,"Missing model accepted");}
   fs=std::make_unique<Fsrcnn>(gpu,2,32);check(fs->load(path+".param",path+".bin")==0,"FSRCNN model load failed");
  } else if(kind=="waifu") {
   {Waifu2x bad(gpu,false,2);check(bad.load("missing.param","missing.bin")!=0,"Missing model accepted");}
   waifu=std::make_unique<Waifu2x>(gpu,false,2);waifu->scale=2;waifu->noise=noise;waifu->tilesize=32;waifu->prepadding=7;
   check(waifu->load(path+".param",path+".bin")==0,"Waifu official model load failed");
  } else {
   sr=std::make_unique<RealSR>(gpu,false,2);sr->scale=4;sr->tilesize=32;sr->prepadding=10;
   std::wstring p(path.begin(),path.end());check(sr->load(p+L".param",p+L".bin")==0,"General model load failed");
   // Existing RealSR's host Windows path is BGR (Android production is RGB).
   for(size_t i=0;i<rgb.size();i+=3)std::swap(rgb[i],rgb[i+2]);
  }
  if(waifu && gpu<0) {
   for(int allocation:{1,2}) {
    FailWorkspace failure(allocation);waifu->frame_allocator=&failure;
    check(waifu->process(in,out)!=0,"Workspace allocation failure must return an error");
    waifu->frame_allocator=nullptr;
   }
   std::cout<<"Waifu normalized/output allocation-failure guards passed"<<std::endl;
  }
  for(int i=0;i<3;i++) {
   int status=fs?fs->process(in,out):waifu?waifu->process(in,out):sr->process(in,out);check(status==0,"Production native inference failed");
   if(i==0)first=result;else check(first==result,"Persistent repeat-frame changed");
  }
  if(fs || waifu) {
   // Same model/context, full frame vs bounded tiles. Genuine context halo/padding must agree.
   if(fs) {
    Fsrcnn full(gpu,2,128);check(full.load(path+".param",path+".bin")==0,"Full model failed");check(full.process(in,out)==0,"Full inference failed");
   } else {
    waifu->tilesize=128;check(waifu->process(in,out)==0,"Full inference failed");
   }
   int maxdiff=0;for(size_t i=0;i<result.size();i++)maxdiff=std::max(maxdiff,std::abs(int(result[i])-first[i]));
   check(maxdiff<=2,"Tiled vs full result seam mismatch");std::cout<<"Tile/full max byte error="<<maxdiff<<std::endl;
  }
  if(sr)for(size_t i=0;i<result.size();i+=3)std::swap(result[i],result[i+2]);
  std::string dest=argv[6];dest=dest.substr(0,dest.find_last_of('.'))+".out";std::ofstream o(dest,std::ios::binary);o.write((char*)result.data(),result.size());check(bool(o),"Output write failed");
  std::cout<<kind<<" "<<argv[3]<<" "<<backend<<": real model, 3 repeat frames passed"<<std::endl;return 0;
 } catch(const std::exception& e){std::cerr<<e.what()<<std::endl;return 1;}
}
