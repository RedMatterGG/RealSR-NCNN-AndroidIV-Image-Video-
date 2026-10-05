#include <jni.h>
#include <android/bitmap.h>
#include <unistd.h>
#include <sys/stat.h>
#include <cerrno>
#include <chrono>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <string>
#include "policy.h"
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/pixdesc.h>
#include <libavutil/error.h>
#include <libswscale/swscale.h>
}
namespace {
using Clock=std::chrono::steady_clock;
void checked(int r,const char* what) {if(r<0) {char b[AV_ERROR_MAX_STRING_SIZE];av_strerror(r,b,sizeof b);throw std::runtime_error(std::string(what)+": "+b);}}
struct Decoder {
 int fd=-1,track=-1,width=0,height=0,codedWidth=0,codedHeight=0; int64_t offset=0,length=0,pos=0;
 AVFormatContext* format=nullptr; AVIOContext* io=nullptr; AVCodecContext* codec=nullptr;
 AVPacket* packet=nullptr; AVFrame* frame=nullptr; SwsContext* sws=nullptr;
 bool eof=false,flushed=false,ended=false; Clock::time_point deadline;
 ~Decoder() {sws_freeContext(sws);av_frame_free(&frame);av_packet_free(&packet);avcodec_free_context(&codec);avformat_close_input(&format);if(io) {av_freep(&io->buffer);avio_context_free(&io);} if(fd>=0)::close(fd);}
 void arm() {deadline=Clock::now()+std::chrono::seconds(30);}
 static int interrupt(void* p) {return Clock::now()>static_cast<Decoder*>(p)->deadline;}
 static int read(void* p,uint8_t* buf,int size) {
  auto& d=*static_cast<Decoder*>(p); if(interrupt(p))return AVERROR(ETIMEDOUT);
  try {int n=ffpolicy::readSize(d.pos,d.length,size);if(!n)return AVERROR_EOF;
   ssize_t r;do {r=pread(d.fd,buf,n,d.offset+d.pos);}while(r<0&&errno==EINTR&&!interrupt(p));
   if(r<0)return AVERROR(errno);if(!r)return AVERROR(EIO);d.pos+=r;return static_cast<int>(r);
  }catch(...) {return AVERROR(EINVAL);}
 }
 static int64_t seek(void* p,int64_t off,int whence) {
  auto& d=*static_cast<Decoder*>(p);if(whence&AVSEEK_SIZE)return d.length;
  try {d.pos=ffpolicy::seek(d.pos,d.length,off,whence&~AVSEEK_FORCE);return d.pos;}catch(...) {return AVERROR(EINVAL);}
 }
 void open(int source,int64_t begin,int64_t len,int64_t start,int threads) {
  if(begin<0||start<0||threads<1||threads>16)throw std::runtime_error("Invalid FFmpeg arguments");
  fd=dup(source);if(fd<0)throw std::runtime_error("dup source failed");
  if(lseek(fd,0,SEEK_CUR)<0)throw std::runtime_error("FFmpeg requires a seekable SAF descriptor; no copy fallback");
  struct stat st{};if(fstat(fd,&st)<0)throw std::runtime_error("fstat failed");
  offset=begin;length=len<0?st.st_size-begin:len;
  if(length<=0||begin>INT64_MAX-length||(S_ISREG(st.st_mode)&&begin+length>st.st_size))throw std::runtime_error("Invalid or unknown source extent");
  arm();av_max_alloc(128*1024*1024);
  auto* buffer=static_cast<unsigned char*>(av_malloc(32768));if(!buffer)throw std::bad_alloc();
  io=avio_alloc_context(buffer,32768,0,this,read,nullptr,seek);if(!io){av_free(buffer);throw std::bad_alloc();}
  io->seekable=AVIO_SEEKABLE_NORMAL;
  format=avformat_alloc_context();if(!format)throw std::bad_alloc();
  format->pb=io;format->flags|=AVFMT_FLAG_CUSTOM_IO;format->interrupt_callback={interrupt,this};
  format->probesize=4*1024*1024;format->max_analyze_duration=5*AV_TIME_BASE;
  checked(avformat_open_input(&format,nullptr,nullptr,nullptr),"Open container");
  if(!ffpolicy::sharesAndroidTimeline(format->iformat ? format->iformat->name : nullptr))
   throw std::runtime_error("FFmpeg MPEG-TS decoding unavailable: Android/FFmpeg timestamp origins differ. Select Android MediaCodec or convert to MP4.");
  checked(avformat_find_stream_info(format,nullptr),"Inspect streams");
  for(unsigned i=0;i<format->nb_streams;i++)if(format->streams[i]->codecpar->codec_type==AVMEDIA_TYPE_VIDEO){track=i;break;}
  if(track<0)throw std::runtime_error("No video stream");
  auto* par=format->streams[track]->codecpar;
  if(av_packet_side_data_get(par->coded_side_data,par->nb_coded_side_data,AV_PKT_DATA_DOVI_CONF))throw std::runtime_error("Dolby Vision configuration unsupported");
  if(par->codec_id!=AV_CODEC_ID_H264&&par->codec_id!=AV_CODEC_ID_HEVC&&par->codec_id!=AV_CODEC_ID_VP8&&par->codec_id!=AV_CODEC_ID_VP9&&par->codec_id!=AV_CODEC_ID_MPEG4)throw std::runtime_error("Unsupported FFmpeg codec (AV1/Dolby Vision unavailable)");
  if(par->color_trc==AVCOL_TRC_SMPTE2084||par->color_trc==AVCOL_TRC_ARIB_STD_B67||par->bits_per_raw_sample>8)throw std::runtime_error("HDR/10-bit video unsupported");
  if(par->width<=0||par->height<=0||par->width>8192||par->height>8192||int64_t(par->width)*par->height>16777216)throw std::runtime_error("Source dimensions exceed decoder bound");
  const AVCodec* implementation=avcodec_find_decoder(par->codec_id);if(!implementation)throw std::runtime_error("FFmpeg software decoder unavailable");
  codec=avcodec_alloc_context3(implementation);if(!codec)throw std::bad_alloc();
  checked(avcodec_parameters_to_context(codec,par),"Codec parameters");codec->thread_count=threads;codec->max_pixels=16777216;codec->err_recognition=AV_EF_EXPLODE;
  codec->apply_cropping=0; // Apply exact, potentially unaligned visible crop ourselves.
  checked(avcodec_open2(codec,implementation,nullptr),"Open decoder");
  packet=av_packet_alloc();frame=av_frame_alloc();if(!packet||!frame)throw std::bad_alloc();
  if(start>0){int64_t target=av_rescale_q(start,AVRational{1,1000000},format->streams[track]->time_base);checked(av_seek_frame(format,track,target,AVSEEK_FLAG_BACKWARD),"Seek previous keyframe");avcodec_flush_buffers(codec);}
 }
 bool next() {
  if(ended)return false;arm();av_frame_unref(frame);
  while(true) {
   if(interrupt(this))throw std::runtime_error("FFmpeg decode exceeded 30 second cooperative timeout");
   int r=avcodec_receive_frame(codec,frame);if(r==0)return true;if(r==AVERROR_EOF){ended=true;return false;}if(r!=AVERROR(EAGAIN))checked(r,"Receive frame");
   if(eof){if(flushed)throw std::runtime_error("Decoder EAGAIN after accepted EOS flush");checked(avcodec_send_packet(codec,nullptr),"Flush decoder");flushed=true;continue;}
   do {av_packet_unref(packet);r=av_read_frame(format,packet);if(r==AVERROR_EOF){if(io->error<0&&io->error!=AVERROR_EOF)checked(io->error,"Source read failed");eof=true;break;}checked(r,"Read packet");if(packet->size>32*1024*1024)throw std::runtime_error("Encoded packet exceeds 32 MiB");}while(packet->stream_index!=track);
   if(eof)continue;
   // receive already returned EAGAIN: FFmpeg guarantees send cannot also EAGAIN.
   checked(avcodec_send_packet(codec,packet),"Send packet");av_packet_unref(packet);
  }
 }
 jobject output(JNIEnv* env) {
  const AVPixFmtDescriptor* desc=av_pix_fmt_desc_get(static_cast<AVPixelFormat>(frame->format));if(!desc)throw std::runtime_error("Unknown pixel format");
  for(int i=0;i<desc->nb_components;i++)if(desc->comp[i].depth!=8)throw std::runtime_error("Only 8-bit SDR FFmpeg frames supported");
  if(frame->color_trc==AVCOL_TRC_SMPTE2084||frame->color_trc==AVCOL_TRC_ARIB_STD_B67||av_frame_get_side_data(frame,AV_FRAME_DATA_MASTERING_DISPLAY_METADATA)||av_frame_get_side_data(frame,AV_FRAME_DATA_CONTENT_LIGHT_LEVEL)||av_frame_get_side_data(frame,AV_FRAME_DATA_DOVI_METADATA))throw std::runtime_error("HDR/Dolby Vision frames unsupported");
  if(!codedWidth){codedWidth=frame->width;codedHeight=frame->height;}else if(codedWidth!=frame->width||codedHeight!=frame->height)throw std::runtime_error("Dynamic coded video dimensions unsupported");
  checked(av_frame_apply_cropping(frame,AV_FRAME_CROP_UNALIGNED),"Apply frame crop");
  if(frame->width<=0||frame->height<=0||frame->width>8192||frame->height>8192||int64_t(frame->width)*frame->height>16777216)throw std::runtime_error("Invalid cropped dimensions");
  if(!width){width=frame->width;height=frame->height;}else if(width!=frame->width||height!=frame->height)throw std::runtime_error("Dynamic video dimensions unsupported");
  int64_t pts=frame->best_effort_timestamp;if(pts==AV_NOPTS_VALUE)throw std::runtime_error("Decoded frame lacks source PTS");
  pts=av_rescale_q(pts,format->streams[track]->time_base,AVRational{1,1000000});if(pts<0)throw std::runtime_error("Negative source PTS/preroll unsupported");
  int matrix;
  switch(frame->colorspace){case AVCOL_SPC_BT709:matrix=SWS_CS_ITU709;break;case AVCOL_SPC_SMPTE170M:case AVCOL_SPC_BT470BG:matrix=SWS_CS_ITU601;break;case AVCOL_SPC_UNSPECIFIED:matrix=height>=720?SWS_CS_ITU709:SWS_CS_ITU601;break;default:throw std::runtime_error("Unsupported SDR color matrix (only BT601/709)");}
  sws=sws_getCachedContext(sws,width,height,static_cast<AVPixelFormat>(frame->format),width,height,AV_PIX_FMT_RGBA,SWS_BILINEAR,nullptr,nullptr,nullptr);if(!sws)throw std::runtime_error("RGBA converter initialization failed");
  checked(sws_setColorspaceDetails(sws,sws_getCoefficients(matrix),frame->color_range==AVCOL_RANGE_JPEG,sws_getCoefficients(matrix),1,0,1<<16,1<<16),"Color range");
  jclass bitmap=env->FindClass("android/graphics/Bitmap"),config=env->FindClass("android/graphics/Bitmap$Config");
  jobject argb=env->GetStaticObjectField(config,env->GetStaticFieldID(config,"ARGB_8888","Landroid/graphics/Bitmap$Config;"));
  jobject result=env->CallStaticObjectMethod(bitmap,env->GetStaticMethodID(bitmap,"createBitmap","(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"),width,height,argb);
  if(env->ExceptionCheck()||!result)return nullptr;
  AndroidBitmapInfo info{};void* pixels=nullptr;
  if(AndroidBitmap_getInfo(env,result,&info)!=0||AndroidBitmap_lockPixels(env,result,&pixels)!=0)throw std::runtime_error("Bitmap lock failed");
  uint8_t* dst[]={static_cast<uint8_t*>(pixels),nullptr,nullptr,nullptr};int strides[]={static_cast<int>(info.stride),0,0,0};
  int converted=sws_scale(sws,frame->data,frame->linesize,0,height,dst,strides);AndroidBitmap_unlockPixels(env,result);
  if(converted!=height)throw std::runtime_error("RGBA conversion incomplete");
  jclass cls=env->FindClass("com/tumuyan/ncnn/realsr/FfmpegVideoDecoder$Frame");
  return env->NewObject(cls,env->GetMethodID(cls,"<init>","(Landroid/graphics/Bitmap;J)V"),result,static_cast<jlong>(pts));
 }
};
std::mutex registryMutex;std::unordered_map<jlong,std::unique_ptr<Decoder>> registry;jlong nextHandle=1;
Decoder& get(jlong h){auto i=registry.find(h);if(i==registry.end())throw std::runtime_error("Invalid/closed FFmpeg handle");return *i->second;}
void error(JNIEnv* env,const std::exception& e){if(!env->ExceptionCheck())env->ThrowNew(env->FindClass("java/io/IOException"),e.what());}
}
#define JNI_FN(name) Java_com_tumuyan_ncnn_realsr_FfmpegVideoDecoder_##name
extern "C" JNIEXPORT jlong JNICALL JNI_FN(nativeOpen)(JNIEnv* env,jclass,jint fd,jlong offset,jlong length,jlong start,jint threads){std::lock_guard<std::mutex> lock(registryMutex);try{auto d=std::make_unique<Decoder>();d->open(fd,offset,length,start,threads);jlong h=nextHandle++;registry.emplace(h,std::move(d));return h;}catch(const std::exception& e){error(env,e);return 0;}}
extern "C" JNIEXPORT jobject JNICALL JNI_FN(nativeNext)(JNIEnv* env,jclass,jlong h){std::lock_guard<std::mutex> lock(registryMutex);try{auto& d=get(h);if(!d.next())return nullptr;return d.output(env);}catch(const std::exception& e){error(env,e);return nullptr;}}
extern "C" JNIEXPORT jstring JNICALL JNI_FN(nativeDiagnostics)(JNIEnv* env,jclass,jlong h){std::lock_guard<std::mutex> lock(registryMutex);try{auto& d=get(h);std::string text=std::string("FFmpeg ")+av_version_info()+" LGPL software "+d.codec->codec->name+"; threads="+std::to_string(d.codec->thread_count)+"; source PTS; unrotated RGBA; BT601/709 range (untagged matrix inferred by height); cancellation waits for in-flight pread/decode; cooperative timeout 30s";return env->NewStringUTF(text.c_str());}catch(const std::exception& e){error(env,e);return nullptr;}}
extern "C" JNIEXPORT void JNICALL JNI_FN(nativeClose)(JNIEnv*,jclass,jlong h){std::lock_guard<std::mutex> lock(registryMutex);registry.erase(h);}
