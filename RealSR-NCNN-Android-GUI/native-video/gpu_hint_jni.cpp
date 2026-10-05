#include "gpu_hint_session.h"
#include <jni.h>
#include <dlfcn.h>
#include <android/api-level.h>
#include <unistd.h>
#include <mutex>
#include <memory>
#include <unordered_map>
#include <new>
namespace {
std::string loadError;
thread_local std::string lastStatus;
const GpuHintOps& ops(){
 static const GpuHintOps table=[] {
  GpuHintOps o{};
  if(android_get_device_api_level()<36){loadError="SDK<36";return o;}
  void* lib=dlopen("libandroid.so",RTLD_NOW|RTLD_LOCAL);
  if(!lib){const char* e=dlerror();loadError=e?e:"dlopen failed";return o;}
  // Keep the library open for the lifetime of the resolved function pointers.
#define LOAD(field,symbol) o.field=reinterpret_cast<decltype(o.field)>(dlsym(lib,symbol))
  LOAD(manager,"APerformanceHint_getManager");
  LOAD(feature,"APerformanceHint_isFeatureSupported");
  LOAD(config,"ASessionCreationConfig_create");
  LOAD(release,"ASessionCreationConfig_release");
  LOAD(tids,"ASessionCreationConfig_setTids");
  LOAD(target,"ASessionCreationConfig_setTargetWorkDurationNanos");
  LOAD(graphics,"ASessionCreationConfig_setGraphicsPipeline");
  LOAD(create,"APerformanceHint_createSessionUsingConfig");
  LOAD(close,"APerformanceHint_closeSession");
  LOAD(reset,"APerformanceHint_notifyWorkloadReset");
  LOAD(report,"APerformanceHint_reportActualWorkDuration");
  LOAD(update,"APerformanceHint_updateTargetWorkDuration");
  LOAD(ordinary,"APerformanceHint_createSession");
#undef LOAD
  return o;
 }();
 return table;
}
struct Owned { int tid; GpuHintSession session; explicit Owned(const GpuHintOps& o):tid(gettid()),session(o){} };
std::mutex lock;
std::unordered_map<jlong,std::unique_ptr<Owned>> sessions;
jlong nextHandle=1;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_tumuyan_ncnn_realsr_VideoGpuHints_nativeSupported(JNIEnv*,jclass){
 GpuHintSession s(ops()); return s.supported();
}
extern "C" JNIEXPORT jlong JNICALL Java_com_tumuyan_ncnn_realsr_VideoGpuHints_nativeOpen(JNIEnv*,jclass,jlong target){
 try {
  auto s=std::make_unique<Owned>(ops());
  bool opened=s->session.open(s->tid,target);
  lastStatus=s->session.diagnostics();
  if(!opened)return 0;
  std::lock_guard<std::mutex> guard(lock);
  jlong id=nextHandle++; sessions.emplace(id,std::move(s)); return id;
 } catch(...) {lastStatus="native allocation exception";return 0;}
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_tumuyan_ncnn_realsr_VideoGpuHints_nativeBegin(JNIEnv*,jclass,jlong id){
 std::lock_guard<std::mutex> guard(lock);
 auto it=sessions.find(id);
 if(it==sessions.end()||it->second->tid!=gettid()){lastStatus="worker ownership mismatch";return false;}
 bool ok=it->second->session.begin();lastStatus=it->second->session.diagnostics();return ok;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_tumuyan_ncnn_realsr_VideoGpuHints_nativeReport(JNIEnv*,jclass,jlong id,jlong total,jlong target){
 std::lock_guard<std::mutex> guard(lock);
 auto it=sessions.find(id);
 if(it==sessions.end()||it->second->tid!=gettid()) return false;
 bool ok=it->second->session.report(total)&&(target<=0||it->second->session.target(target));
 lastStatus=it->second->session.diagnostics();return ok;
}
extern "C" JNIEXPORT void JNICALL Java_com_tumuyan_ncnn_realsr_VideoGpuHints_nativeClose(JNIEnv*,jclass,jlong id){
 std::lock_guard<std::mutex> guard(lock); sessions.erase(id);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_tumuyan_ncnn_realsr_VideoGpuHints_nativeMeasuringGraphics(JNIEnv*,jclass,jlong id){
 std::lock_guard<std::mutex> guard(lock);auto it=sessions.find(id);
 return it!=sessions.end()&&it->second->session.measuringGraphics();
}
extern "C" JNIEXPORT jstring JNICALL Java_com_tumuyan_ncnn_realsr_VideoGpuHints_nativeStatus(JNIEnv* env,jclass,jlong id){
 std::lock_guard<std::mutex> guard(lock);auto it=sessions.find(id);
 std::string text=it==sessions.end()?lastStatus:it->second->session.diagnostics();
 return env->NewStringUTF(text.c_str());
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_tumuyan_ncnn_realsr_VideoGpuHints_nativeProbe(JNIEnv* env,jclass){
 GpuHintSession s(ops());const auto p=s.probe();
 auto cls=env->FindClass("java/lang/String");if(!cls)return nullptr;
 auto arr=env->NewObjectArray(5,cls,nullptr);if(!arr)return nullptr;
 const std::string values[]={p.missing,p.sessions?"true":"false",p.graphics?"true":"false",p.manager?"true":"false",loadError};
 for(int i=0;i<5;i++){auto v=env->NewStringUTF(values[i].c_str());if(!v)return nullptr;env->SetObjectArrayElement(arr,i,v);env->DeleteLocalRef(v);}
 return arr;
}
