#include "policy.h"
#include <cassert>
#include <stdexcept>
int main() {
  assert(ffpolicy::sharesAndroidTimeline("mov,mp4,m4a,3gp,3g2,mj2"));
  assert(ffpolicy::sharesAndroidTimeline("matroska,webm"));
  assert(ffpolicy::sharesAndroidTimeline("avi"));
  assert(!ffpolicy::sharesAndroidTimeline("mpegts"));
  assert(!ffpolicy::sharesAndroidTimeline(nullptr));
  assert(ffpolicy::readSize(10,10,100)==0);
  assert(ffpolicy::readSize(8,10,100)==2);
  assert(ffpolicy::seek(4,10,-2,1)==2);
  assert(ffpolicy::seek(4,10,-1,2)==9);
  bool rejected=false; try { ffpolicy::seek(4,10,11,0); } catch(const std::exception&) {rejected=true;} assert(rejected);
}
