package com.tumuyan.ncnn.realsr;
public final class VideoGpuHintsTest {
 static class Ops implements VideoGpuHints.Ops {
  int opens,begins,reports,closes; boolean supported=true,fail; long total,target;
  public boolean supported(){return supported;}
  public long open(long target){opens++;return 7;}
  public boolean begin(long h){begins++;return !fail;}
  public boolean report(long h,long n,long t){reports++;total=n;target=t;return !fail;}
  public void close(long h){closes++;}
 }
 public static void main(String[] args){
  Ops o=new Ops();
  try(VideoGpuHints h=new VideoGpuHints(false,false,36,o,1000,true)){if(o.opens!=0)throw new AssertionError("off");}
  try(VideoGpuHints h=new VideoGpuHints(true,true,36,o,1000,true)){if(o.opens!=0)throw new AssertionError("CPU");}
  try(VideoGpuHints h=new VideoGpuHints(true,false,36,o,1000,true)){
   if(!h.beginFrame()||!h.beginFrame()||o.begins!=1)throw new AssertionError("reset once");
   h.reportFrame(100,2100);h.reportFrame(2100,4100);
   if(o.reports!=2||o.total!=2000||o.target!=2000)throw new AssertionError("measured total ongoing");
   h.reportFrame(0,10);h.reportFrame(20,10);if(o.reports!=2)throw new AssertionError("invalid duration");
  }
  if(o.closes!=1)throw new AssertionError("close");
  o.fail=true; try(VideoGpuHints h=new VideoGpuHints(true,false,36,o,1000,false)){
   if(h.beginFrame()||h.active())throw new AssertionError("failure disabled");
  }
  if(o.closes!=2)throw new AssertionError("failure cleanup exactly once");
  Ops vanilla=new Ops(){public boolean measuringGraphics(long h){return false;}};
  try(VideoGpuHints h=new VideoGpuHints(true,false,36,vanilla,1000,true)){
   if(!h.active()||!h.beginFrame()||!h.beginFrame()||vanilla.begins!=1)throw new AssertionError("vanilla one reset");
   h.reportFrame(100,2100);if(vanilla.reports!=0)throw new AssertionError("vanilla must never report GPU wall time as CPU");
   h.close();h.close();
  }
  if(vanilla.closes!=1)throw new AssertionError("vanilla shutdown exactly once");
  System.out.println("GPU Java wrapper ownership/timing/failure tests passed");
 }
}
