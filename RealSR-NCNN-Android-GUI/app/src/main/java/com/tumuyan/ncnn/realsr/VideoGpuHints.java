package com.tumuyan.ncnn.realsr;
/** Worker-owned best-effort GPU hint session, separate from CPU ADPF. */
public final class VideoGpuHints implements AutoCloseable {
  /** Immutable, independent predicate snapshot; graphics=false is not GPU-hint unsupported. */
  public static final class Probe {
    public final String missingExports, loadError;
    public final boolean sessions, graphics, manager;
    Probe(String missing,boolean sessions,boolean graphics,boolean manager,String error){
      this.missingExports=missing;this.sessions=sessions;this.graphics=graphics;this.manager=manager;this.loadError=error;
    }
    public String toString(){return "missingExports=["+missingExports+"]; sessions="+sessions+"; graphics="+graphics+"; manager="+manager+"; loadError="+loadError;}
  }
  interface Ops {
    boolean supported(); long open(long target); boolean begin(long handle);
    boolean report(long handle,long total,long target); void close(long handle);
    default boolean measuringGraphics(long handle){return true;}
    default Probe probe(){boolean s=supported();return new Probe("",s,s,s,"");}
    default String diagnostics(long handle){return "";}
  }
  private static final class NativeOps implements Ops {
    NativeOps(){System.loadLibrary("ncnn_video");}
    public boolean supported(){return nativeSupported();}
    public long open(long target){return nativeOpen(target);}
    public boolean begin(long h){return nativeBegin(h);}
    public boolean report(long h,long total,long target){return nativeReport(h,total,target);}
    public void close(long h){nativeClose(h);}
    public boolean measuringGraphics(long h){return nativeMeasuringGraphics(h);}
    public String diagnostics(long h){return nativeStatus(h);}
    public Probe probe(){String[] p=nativeProbe();return new Probe(p[0],Boolean.parseBoolean(p[1]),Boolean.parseBoolean(p[2]),Boolean.parseBoolean(p[3]),p[4]);}
  }
  private static native boolean nativeSupported();
  private static native long nativeOpen(long target);
  private static native boolean nativeBegin(long handle);
  private static native boolean nativeReport(long handle,long total,long target);
  private static native void nativeClose(long handle);
  private static native boolean nativeMeasuringGraphics(long handle);
  private static native String nativeStatus(long handle);
  private static native String[] nativeProbe();
  private final Ops ops;
  private final boolean autoTarget;
  private final Probe probe;
  private long handle;
  private boolean begun, graphics;
  private String operation="", status="GPU performance request off";
  public static boolean supported(int sdk){
    if(sdk<36)return false;
    try{return new NativeOps().supported();}catch(RuntimeException|LinkageError e){return false;}
  }
  public static VideoGpuHints create(boolean requested,boolean cpu,int sdk,long target,boolean auto){
    Ops o=null;String error="";
    if(requested&&!cpu&&sdk>=36)try{o=new NativeOps();}catch(RuntimeException|LinkageError e){error=e.getClass().getSimpleName()+": "+e.getMessage();}
    return new VideoGpuHints(requested,cpu,sdk,o,target,auto,error);
  }
  VideoGpuHints(boolean requested,boolean cpu,int sdk,Ops ops,long target,boolean auto){this(requested,cpu,sdk,ops,target,auto,"");}
  private VideoGpuHints(boolean requested,boolean cpu,int sdk,Ops ops,long target,boolean auto,String loadError){
    this.ops=ops;autoTarget=auto;
    Probe p=new Probe("",false,false,false,loadError);
    if(!requested){probe=p;return;}
    if(cpu){probe=p;status="GPU request disabled: CPU backend";return;}
    if(sdk<36){probe=p;status="GPU request disabled: SDK<36";return;}
    status="GPU performance request unavailable";
    try {
      if(ops!=null){
        p=ops.probe();
        handle=ops.open(target);operation=ops.diagnostics(handle);
        if(handle!=0){graphics=ops.measuringGraphics(handle);status=graphics?"GPU graphics session ready (best effort; no frequency lock)":"GPU ordinary reset-only session ready (best effort; no frequency lock)";}
      }
    }catch(RuntimeException|LinkageError e){operation=e.getClass().getSimpleName()+": "+e.getMessage();close();}
    probe=p;
  }
  public Probe probe(){return probe;}
  public boolean active(){return handle!=0;}
  public boolean measuringGraphics(){return active()&&graphics;}
  /** One reset before real work, never a frame pulse. */
  public boolean beginFrame(){
    if(!active())return false;
    if(begun)return true;
    try {
      boolean ok=ops.begin(handle);operation=ops.diagnostics(handle);
      if(ok){begun=true;status="GPU hint requested ("+(graphics?"graphics":"ordinary reset-only")+"); device may ignore it; no frequency lock";return true;}
    }catch(RuntimeException|LinkageError e){operation=e.getClass().getSimpleName()+": "+e.getMessage();}
    disable();return false;
  }
  /** Graphics end-to-end duration only. Legacy API internally classifies total as CPU, GPU=0. */
  public void reportFrame(long start,long end){
    if(!measuringGraphics()||!begun||start<=0||end<=start)return;
    long total=end-start;
    try{boolean ok=ops.report(handle,total,autoTarget?total:0);operation=ops.diagnostics(handle);if(ok)return;}
    catch(RuntimeException|LinkageError e){operation=e.getClass().getSimpleName()+": "+e.getMessage();}
    disable();
  }
  private void disable(){close();status="GPU performance request unavailable (session closed)";}
  public String status(){return status+"; "+probe+"; "+operation;}
  @Override public void close(){
    long h=handle;handle=0;graphics=false;
    if(h!=0)try{ops.close(h);}catch(RuntimeException|LinkageError e){operation+="; close="+e.getClass().getSimpleName();}
  }
}
