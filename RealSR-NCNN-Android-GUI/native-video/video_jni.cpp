#include <jni.h>
#include <android/bitmap.h>
#include <chrono>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <sstream>
#include <vector>
#include "engines/RealCUGAN/realcugan.h"
#include "engines/RealSR/realsr.h"
#include "pixels.h"
#include "engines/fsrcnn.h"
#include "engines/Waifu2x/waifu2x.h"

namespace {
using Clock=std::chrono::steady_clock;
double ms(Clock::time_point begin) { return std::chrono::duration<double,std::milli>(Clock::now()-begin).count(); }
struct Engine {
 std::mutex mutex;
 std::unique_ptr<RealCUGAN> cugan;
 std::unique_ptr<RealSR> sr;
 std::unique_ptr<Fsrcnn> fsrcnn;
 std::unique_ptr<Waifu2x> waifu;
 int scale=0, threads=0;
 bool closed=false;
 double init=0,last=0;
 uint64_t frames=0;
 std::string backend;
};
std::mutex registryMutex;
std::unordered_map<jlong,std::shared_ptr<Engine>> registry;
jlong nextId=1;
std::once_flag gpuOnce;
int gpuStatus=-1;
std::shared_ptr<Engine> find(jlong id) {
 std::lock_guard<std::mutex> lock(registryMutex);
 auto it=registry.find(id);
 if(it==registry.end()) throw std::runtime_error("Invalid/closed native engine handle");
 return it->second;
}
void fail(JNIEnv* env,const char* message) {
 if(!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"),message);
}
std::string text(JNIEnv* env,jstring value) {
 if(!value) throw std::runtime_error("Missing native engine argument");
 const char* p=env->GetStringUTFChars(value,nullptr);
 if(!p) throw std::runtime_error("Cannot read native argument");
 std::string s(p); env->ReleaseStringUTFChars(value,p);return s;
}
struct Pixels {
 JNIEnv* env; jobject bitmap; AndroidBitmapInfo info{}; void* data=nullptr;
 Pixels(JNIEnv* e,jobject b):env(e),bitmap(b) {
  if(!b || AndroidBitmap_getInfo(e,b,&info)!=ANDROID_BITMAP_RESULT_SUCCESS || info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888)
   throw std::runtime_error("Native engine requires RGBA_8888 software bitmap");
  if(AndroidBitmap_lockPixels(e,b,&data)!=ANDROID_BITMAP_RESULT_SUCCESS || !data)
   throw std::runtime_error("Cannot lock bitmap pixels");
 }
 ~Pixels(){ if(data) AndroidBitmap_unlockPixels(env,bitmap); }
};
jobject newBitmap(JNIEnv* env,int w,int h) {
 jclass config=env->FindClass("android/graphics/Bitmap$Config");
 jobject argb=env->GetStaticObjectField(config,env->GetStaticFieldID(config,"ARGB_8888","Landroid/graphics/Bitmap$Config;"));
 jclass bitmap=env->FindClass("android/graphics/Bitmap");
 return env->CallStaticObjectMethod(bitmap,env->GetStaticMethodID(bitmap,"createBitmap","(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"),w,h,argb);
}
}
#define JNI_METHOD(name) Java_com_tumuyan_ncnn_realsr_NcnnFrameProcessor_##name
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(nativeCreate)(JNIEnv* env,jclass,jstring type,jstring param,jstring weights,jint scale,jint tile,jboolean cpu,jint noise,jint threads,jboolean nose) {
 try {
  auto begin=Clock::now();
  std::string kind=text(env,type),p=text(env,param),w=text(env,weights);
  if((kind!="realcugan-ncnn" && kind!="realsr-ncnn" && kind!="fsrcnn-ncnn" && kind!="waifu2x-ncnn") || scale<1 || scale>4 || threads<1 || threads>256 || (tile!=0 && (tile<32 || tile>4096)) || noise< -1 || noise>3)
   throw std::runtime_error("Invalid native model configuration");
  if(kind=="fsrcnn-ncnn" && (scale!=2 || noise!=0)) throw std::runtime_error("FSRCNN-small supports native 2x / noise 0 only");
  if(kind=="waifu2x-ncnn" && scale!=2) throw std::runtime_error("Waifu2x upconv supports native 2x only");
  auto e=std::make_shared<Engine>();e->scale=scale;e->threads=threads;
  int gpu=-1;
  if(!cpu) {
   std::call_once(gpuOnce,[]{gpuStatus=ncnn::create_gpu_instance();});
   if(gpuStatus!=0 || ncnn::get_gpu_count()==0 || (gpu=ncnn::get_default_gpu_index())<0 || !ncnn::get_gpu_device(gpu))
    throw std::runtime_error("Vulkan requested but no usable NCNN Vulkan device; select CPU explicitly");
   const auto& info=ncnn::get_gpu_info(gpu);
   std::ostringstream device;device<<"Vulkan GPU "<<gpu<<": "<<info.device_name()<<" vendor="<<info.vendor_id()<<" device="<<info.device_id()<<" driver="<<info.driver_version()<<" API="<<info.api_version();
   e->backend=device.str();
  } else e->backend="CPU (NCNN/OpenMP)";
  int result;
  if(kind=="realcugan-ncnn") {
   e->cugan=std::make_unique<RealCUGAN>(gpu,false,threads);
   e->cugan->scale=scale;e->cugan->noise=noise;e->cugan->tilesize=tile?tile:128;
   e->cugan->prepadding=scale==2?18:scale==3?14:scale==4?19:0;
   e->cugan->syncgap=nose?0:3;
   result=e->cugan->load(p,w);
  } else if(kind=="waifu2x-ncnn") {
   e->waifu=std::make_unique<Waifu2x>(gpu,false,threads);
   e->waifu->scale=2;e->waifu->noise=noise;e->waifu->tilesize=tile?tile:128;e->waifu->prepadding=7;
   result=e->waifu->load(p,w);
  } else if(kind=="fsrcnn-ncnn") {
   e->fsrcnn=std::make_unique<Fsrcnn>(gpu,threads,tile?tile:128);
   result=e->fsrcnn->load(p,w);
  } else {
   e->sr=std::make_unique<RealSR>(gpu,false,threads);
   e->sr->scale=scale;e->sr->tilesize=tile?tile:128;e->sr->prepadding=10;
   result=e->sr->load(p,w);
  }
  if(result!=0) throw std::runtime_error("NCNN model/pipeline load failed: "+std::to_string(result));
  e->init=ms(begin);
  std::lock_guard<std::mutex> lock(registryMutex);
  if(nextId==INT64_MAX) throw std::runtime_error("Native handle space exhausted");
  jlong id=nextId++;registry.emplace(id,e);return id;
 } catch(const std::exception& e){fail(env,e.what());return 0;} catch(...){fail(env,"Unknown native init failure");return 0;}
}
extern "C" JNIEXPORT jobject JNICALL JNI_METHOD(nativeProcess)(JNIEnv* env,jclass,jlong handle,jobject bitmap) {
 try {
  auto e=find(handle);std::lock_guard<std::mutex> lock(e->mutex);
  if(e->closed) throw std::runtime_error("NCNN engine is closed");
  auto begin=Clock::now();unsigned width,height;std::vector<uint8_t> rgb;
  {
   Pixels input(env,bitmap);width=input.info.width;height=input.info.height;
   rgb.resize(video::rgbSize(width,height,e->scale));
   video::rgbaToRgb(static_cast<const uint8_t*>(input.data),input.info.stride,rgb.data(),width,height);
  }
  unsigned ow=width*e->scale,oh=height*e->scale;
  std::vector<uint8_t> result(size_t(ow)*oh*3);
  // These engine process methods require packed bytes and elempack=3, not planar from_pixels.
  ncnn::Mat in(width,height,rgb.data(),size_t(3),3);
  ncnn::Mat out(ow,oh,result.data(),size_t(3),3);
  int status=e->cugan?e->cugan->process(in,out):e->fsrcnn?e->fsrcnn->process(in,out):e->waifu?e->waifu->process(in,out):e->sr->process(in,out);
  if(status!=0 || out.empty() || out.w!=int(ow) || out.h!=int(oh) || out.elempack!=3)
   throw std::runtime_error("NCNN frame inference failed or returned wrong pixel shape");
  jobject output=newBitmap(env,ow,oh);
  if(!output || env->ExceptionCheck()) return nullptr;
  { Pixels pixels(env,output);video::rgbToRgba(static_cast<const uint8_t*>(out.data),static_cast<uint8_t*>(pixels.data),pixels.info.stride,ow,oh); }
  e->last=ms(begin);e->frames++;return output;
 } catch(const std::exception& e){fail(env,e.what());return nullptr;} catch(...){fail(env,"Unknown native inference failure");return nullptr;}
}
extern "C" JNIEXPORT jstring JNICALL JNI_METHOD(nativeDiagnostics)(JNIEnv* env,jclass,jlong handle) {
 try {
  auto e=find(handle);std::lock_guard<std::mutex> lock(e->mutex);
  std::ostringstream s;s<<e->backend<<"; CPU intra-op threads="<<e->threads<<"; model loads=1; frames="<<e->frames<<"; init="<<e->init<<" ms; last-frame="<<e->last<<" ms";
  return env->NewStringUTF(s.str().c_str());
 } catch(const std::exception& e){fail(env,e.what());return nullptr;}
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeClose)(JNIEnv*,jclass,jlong handle) {
 std::shared_ptr<Engine> e;
 { std::lock_guard<std::mutex> lock(registryMutex);auto it=registry.find(handle);if(it==registry.end())return;e=it->second;registry.erase(it); }
 std::lock_guard<std::mutex> lock(e->mutex);e->closed=true;e->cugan.reset();e->sr.reset();e->fsrcnn.reset();e->waifu.reset();
}
// The single Vulkan instance is intentionally process-lifetime; never destroy it while another job can use it.
