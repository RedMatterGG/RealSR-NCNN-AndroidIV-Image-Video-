#pragma once
#include <algorithm>
#include <cstdint>
#include <limits>
#include <stdexcept>
#include <cstring>
namespace ffpolicy {
inline bool sharesAndroidTimeline(const char* container) {
 // Android TS rebases PES time, while FFmpeg preserves the transport origin.
 // Do not mix raw FFmpeg video PTS with MediaExtractor preview/audio until mapped.
 return container && std::strcmp(container,"mpegts") != 0;
}
inline int readSize(int64_t pos,int64_t len,int requested) {
 if(pos<0 || len<0 || pos>len || requested<0) throw std::runtime_error("Invalid AVIO bounds");
 return static_cast<int>(std::min<int64_t>(std::min(requested,32768),len-pos));
}
inline int64_t seek(int64_t pos,int64_t len,int64_t offset,int whence) {
 int64_t base=whence==0?0:whence==1?pos:whence==2?len:-1;
 if(base<0 || (offset>0 && base>INT64_MAX-offset) || (offset<0 && offset < -base)) throw std::runtime_error("Invalid AVIO seek");
 int64_t next=base+offset; if(next<0 || next>len) throw std::runtime_error("AVIO seek outside asset"); return next;
}
}
