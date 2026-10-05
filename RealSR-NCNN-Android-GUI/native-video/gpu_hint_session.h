#pragma once
#include <cstddef>
#include <cstdint>
#include <cerrno>
#include <string>
struct APerformanceHintManager;
struct APerformanceHintSession;
struct ASessionCreationConfig;
#ifndef ANDROID_NATIVE_PERFORMANCE_HINT_H
enum APerformanceHintFeature : int32_t {
 APERF_HINT_SESSIONS=0, APERF_HINT_POWER_EFFICIENCY=1, APERF_HINT_SURFACE_BINDING=2,
 APERF_HINT_GRAPHICS_PIPELINE=3, APERF_HINT_AUTO_CPU=4, APERF_HINT_AUTO_GPU=5
};
#endif
struct GpuHintOps {
 APerformanceHintManager* (*manager)();
 bool (*feature)(APerformanceHintFeature);
 ASessionCreationConfig* (*config)();
 void (*release)(ASessionCreationConfig*);
 void (*tids)(ASessionCreationConfig*,const int32_t*,size_t);
 void (*target)(ASessionCreationConfig*,int64_t);
 void (*graphics)(ASessionCreationConfig*,bool);
 int (*create)(APerformanceHintManager*,ASessionCreationConfig*,APerformanceHintSession**);
 void (*close)(APerformanceHintSession*);
 int (*reset)(APerformanceHintSession*,bool,bool,const char*);
 int (*report)(APerformanceHintSession*,int64_t);
 int (*update)(APerformanceHintSession*,int64_t);
 APerformanceHintSession* (*ordinary)(APerformanceHintManager*,const int32_t*,size_t,int64_t);
};
// Snapshot every predicate independently. Graphics exports are optional for reset-only.
struct GpuHintProbe {
 const std::string missing;
 const bool sessions, graphics, manager;
 const bool ordinaryExports, graphicsExports;
};
class GpuHintSession {
 const GpuHintOps& o;
 APerformanceHintSession* session=nullptr;
 bool started=false, measuring=false, graphicsMode=false;
 int graphicsCreateError=0, createError=0, resetError=0, reportError=0, updateError=0;
 public:
 explicit GpuHintSession(const GpuHintOps& ops):o(ops){}
 ~GpuHintSession(){close();}
 GpuHintProbe probe() const {
  std::string missing;
#define CHECK(field,name) if(!o.field){if(!missing.empty())missing+=",";missing+=name;}
  CHECK(manager,"APerformanceHint_getManager")
  CHECK(feature,"APerformanceHint_isFeatureSupported")
  CHECK(ordinary,"APerformanceHint_createSession")
  CHECK(close,"APerformanceHint_closeSession")
  CHECK(reset,"APerformanceHint_notifyWorkloadReset")
  CHECK(config,"ASessionCreationConfig_create")
  CHECK(release,"ASessionCreationConfig_release")
  CHECK(tids,"ASessionCreationConfig_setTids")
  CHECK(target,"ASessionCreationConfig_setTargetWorkDurationNanos")
  CHECK(graphics,"ASessionCreationConfig_setGraphicsPipeline")
  CHECK(create,"APerformanceHint_createSessionUsingConfig")
  CHECK(report,"APerformanceHint_reportActualWorkDuration")
  CHECK(update,"APerformanceHint_updateTargetWorkDuration")
#undef CHECK
  bool s=o.feature&&o.feature(APERF_HINT_SESSIONS);
  bool g=o.feature&&o.feature(APERF_HINT_GRAPHICS_PIPELINE);
  bool m=o.manager&&o.manager();
  return {missing,s,g,m, bool(o.manager&&o.feature&&o.ordinary&&o.close&&o.reset),
   bool(o.config&&o.release&&o.tids&&o.target&&o.graphics&&o.create&&o.report&&o.update)};
 }
 bool supported() const {auto p=probe();return p.sessions&&p.manager&&p.ordinaryExports;}
 bool active() const {return session!=nullptr;}
 bool measuringGraphics() const {return active()&&measuring;}
 bool open(int32_t tid,int64_t nanos) {
  close(); graphicsMode=false; graphicsCreateError=createError=resetError=reportError=updateError=0;
  const auto p=probe();
  if(tid<=0||nanos<=0){createError=EINVAL;return false;}
  if(!p.sessions||!p.manager||!p.ordinaryExports){createError=ENOTSUP;return false;}
  auto m=o.manager();
  if(p.graphics&&p.graphicsExports){
   auto c=o.config();
   if(c){
    o.tids(c,&tid,1);o.target(c,nanos);o.graphics(c,true);
    graphicsCreateError=o.create(m,c,&session);o.release(c);
    if(!graphicsCreateError&&session){measuring=graphicsMode=true;return true;}
    if(!graphicsCreateError)graphicsCreateError=EIO;
    close(); // EBUSY can return a session; never leak it before the one fallback.
   }else graphicsCreateError=ENOMEM;
  }
  // One ordinary creation attempt only, no graphics flag, no config requirement.
  errno=0;session=o.ordinary(m,&tid,1,nanos);
  if(!session){createError=errno?errno:EIO;return false;}
  return true;
 }
 bool begin(){
  if(!session)return false;
  if(started)return true;
  resetError=o.reset(session,false,true,"RealSR video start");
  if(resetError){close();return false;}
  started=true;return true;
 }
 bool report(int64_t total){
  if(!measuringGraphics()||!started||total<=0)return false;
  // Legacy API maps total to CPU time internally, GPU=0. Not measured GPU duration.
  reportError=o.report(session,total);
  if(reportError){close();return false;}return true;
 }
 bool target(int64_t nanos){
  if(!measuringGraphics()||nanos<=0)return false;
  updateError=o.update(session,nanos);
  if(updateError){close();return false;}return true;
 }
 std::string diagnostics() const {
  return "mode="+std::string(graphicsMode?"graphics":"ordinary reset-only")+
   "; graphicsCreateErrno="+std::to_string(graphicsCreateError)+"; createErrno="+std::to_string(createError)+
   "; resetErrno="+std::to_string(resetError)+"; reportErrno="+std::to_string(reportError)+
   "; updateErrno="+std::to_string(updateError);
 }
 void close(){if(session){o.close(session);session=nullptr;}started=false;measuring=false;}
};
