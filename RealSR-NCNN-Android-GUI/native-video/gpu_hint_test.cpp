#ifdef NDEBUG
#undef NDEBUG
#endif
#include "gpu_hint_session.h"
#include <cassert>
#include <iostream>
static int closed,released,reports,resets,ordinaryCreates,graphicsCreates,graphicsFlags;
static bool capable=true,graphicsCapable=true;
static int creation,resetError,reportError;
static bool ordinaryFails;
static APerformanceHintManager* manager(){return reinterpret_cast<APerformanceHintManager*>(1);}
static bool feature(APerformanceHintFeature v){assert(v==0||v==3);return v==0?capable:graphicsCapable;}
static ASessionCreationConfig* config(){return reinterpret_cast<ASessionCreationConfig*>(2);}
static void release(ASessionCreationConfig*){released++;}
static void tids(ASessionCreationConfig*,const int32_t* p,size_t n){assert(n==1&&*p==42);}
static void target(ASessionCreationConfig*,int64_t n){assert(n==1000);}
static void graphics(ASessionCreationConfig*,bool b){assert(b);graphicsFlags++;}
static int create(APerformanceHintManager*,ASessionCreationConfig*,APerformanceHintSession** s){graphicsCreates++;*s=reinterpret_cast<APerformanceHintSession*>(3);return creation;}
static APerformanceHintSession* ordinary(APerformanceHintManager*,const int32_t* p,size_t n,int64_t t){assert(*p==42&&n==1&&t==1000);ordinaryCreates++;if(ordinaryFails){errno=EBUSY;return nullptr;}return reinterpret_cast<APerformanceHintSession*>(4);}
static void closeSession(APerformanceHintSession*){closed++;}
static int reset(APerformanceHintSession*,bool cpu,bool gpu,const char*){assert(!cpu&&gpu);resets++;return resetError;}
static int report(APerformanceHintSession*,int64_t n){assert(n==2000);reports++;return reportError;}
static int update(APerformanceHintSession*,int64_t n){assert(n>0);return 0;}
int main(){
 GpuHintOps o{manager,feature,config,release,tids,target,graphics,create,closeSession,reset,report,update,ordinary};
 {GpuHintSession s(o);assert(s.supported());assert(s.open(42,1000));assert(s.measuringGraphics());assert(s.begin());assert(s.begin());assert(resets==1);assert(s.report(2000));s.close();s.close();assert(closed==1);}
 graphicsCapable=false;
 {GpuHintSession s(o);assert(s.open(42,1000));assert(s.active());assert(!s.measuringGraphics());assert(s.begin());assert(s.begin());assert(resets==2);assert(!s.report(2000));assert(!s.target(2000));assert(reports==1);assert(graphicsFlags==1);s.close();s.close();assert(closed==2);}
 graphicsCapable=true;
 creation=EBUSY;
 {GpuHintSession s(o);assert(s.open(42,1000));assert(!s.measuringGraphics());assert(closed==3);assert(s.begin());s.close();s.close();assert(closed==4);assert(s.diagnostics().find("graphicsCreateErrno=16")!=std::string::npos);}
 ordinaryFails=true;
 {GpuHintSession s(o);assert(!s.open(42,1000));assert(closed==5);assert(s.diagnostics().find("createErrno=16")!=std::string::npos);s.close();assert(closed==5);}
 ordinaryFails=false;creation=0;
 // Optional graphics exports must not block the ordinary API33 session + API36 reset.
 auto fallback=o;fallback.config=nullptr;fallback.graphics=nullptr;fallback.report=nullptr;
 {GpuHintSession s(fallback);const auto p=s.probe();assert(p.sessions&&p.graphics&&p.manager&&p.ordinaryExports&&!p.graphicsExports);assert(!p.missing.empty());assert(s.open(42,1000));assert(!s.measuringGraphics());assert(s.begin());assert(!s.report(2000));}assert(closed==6);
 resetError=EPIPE;
 {GpuHintSession s(o);assert(s.open(42,1000));assert(!s.begin());assert(!s.begin());s.close();assert(closed==7);assert(s.diagnostics().find("resetErrno=32")!=std::string::npos);}resetError=0;
 reportError=EPIPE;
 {GpuHintSession s(o);assert(s.open(42,1000));assert(s.begin());assert(!s.report(2000));assert(!s.report(2000));s.close();assert(closed==8);assert(s.diagnostics().find("reportErrno=32")!=std::string::npos);assert(s.diagnostics().find("mode=graphics")!=std::string::npos);}reportError=0;
 capable=false;{GpuHintSession s(o);assert(!s.open(42,1000));const auto p=s.probe();assert(!p.sessions&&p.graphics&&p.manager);}capable=true;
 o.manager=[]()->APerformanceHintManager*{return nullptr;};{GpuHintSession s(o);const auto p=s.probe();assert(p.sessions&&p.graphics&&!p.manager);assert(!s.open(42,1000));}
 assert(ordinaryCreates==4);assert(released==graphicsCreates);
 std::cout<<"GPU native graphics/ordinary reset-only, optional exports, EBUSY and exactly-once cleanup passed\n";
}
