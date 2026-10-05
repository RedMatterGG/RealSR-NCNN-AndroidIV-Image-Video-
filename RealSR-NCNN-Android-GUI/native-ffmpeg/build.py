"""Private, checksum-pinned FFmpeg 7.1.2 / NDK r28c ARM64 API24 build and LGPL relink kit."""
import hashlib,json,os,pathlib,shutil,subprocess,tarfile,urllib.request
ROOT=pathlib.Path(__file__).resolve().parent
TOOLS=pathlib.Path(os.environ.get('REALSR_TOOLCHAIN','F:/videoimageupscaler/toolchain'))
FF=TOOLS/'ffmpeg-7.1.2'
BIN=TOOLS/'android-ndk-r28c/toolchains/llvm/prebuilt/windows-x86_64/bin'
PINS={
 'ffmpeg-7.1.2.tar.xz':('https://ffmpeg.org/releases/ffmpeg-7.1.2.tar.xz','089bc60fb59d6aecc5d994ff530fd0dcb3ee39aa55867849a2bbc4e555f9c304'),
 'make-4.4.1-2-x86_64.pkg.tar.zst':('https://repo.msys2.org/msys/x86_64/make-4.4.1-2-x86_64.pkg.tar.zst','2408af61717dae87b00c855b132769a125c708907fc94a46bb16dae076113e5c')}
def run(args,**kw):
 print(' '.join(map(str,args)),flush=True);subprocess.run(list(map(str,args)),check=True,**kw)
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 TOOLS.mkdir(parents=True,exist_ok=True)
 properties=(TOOLS/'android-ndk-r28c/source.properties').read_text()
 if 'Pkg.Revision = 28.2.13676358' not in properties:raise RuntimeError('NDK r28c 28.2.13676358 required')
 for name,(url,sha) in PINS.items():
  p=TOOLS/name
  if not p.exists():urllib.request.urlretrieve(url,p)
  if digest(p)!=sha:raise RuntimeError('Checksum mismatch '+name)
 if not FF.exists():
  with tarfile.open(TOOLS/'ffmpeg-7.1.2.tar.xz') as t:t.extractall(TOOLS,filter='data')
 if not (TOOLS/'msys-make/usr/bin/make.exe').exists():
  # Python 3.14 supports zstd tar files. Never install a global package.
  run(['python3','-c',"import tarfile;tarfile.open(r'"+str(TOOLS/'make-4.4.1-2-x86_64.pkg.tar.zst')+"').extractall(r'"+str(TOOLS/'msys-make')+"',filter='data')"])
 recipe=hashlib.sha256((ROOT/'configure-build.sh').read_bytes()+(ROOT/'host-clang.sh').read_bytes()).hexdigest()
 stamp=FF/'realsr-recipe.sha256'
 if not (FF/'libavcodec/libavcodec.a').exists() or not stamp.exists() or stamp.read_text()!=recipe:
  run([os.sys.executable,ROOT/'compile_ffmpeg.py']);stamp.write_text(recipe)
 kit=pathlib.Path('F:/videoimageupscaler/ffmpeg-video-lgpl-relink');kit.mkdir(parents=True,exist_ok=True)
 obj=kit/'ffmpeg_video.o';lib=kit/'libffmpeg_video.so'
 common=[BIN/'clang++.exe','--target=aarch64-linux-android24','-std=c++17','-O2','-fPIC','-fvisibility=hidden','-idirafter',str(FF)]
 run(common+['-c',ROOT/'ffmpeg_video.cpp','-o',obj])
 archives=[]
 for component in ['avformat','avcodec','swscale','avutil']:
  a=FF/('lib'+component)/('lib'+component+'.a');dest=kit/a.name;shutil.copy2(a,dest);archives.append(dest)
 link=[BIN/'clang++.exe','--target=aarch64-linux-android24','-shared','-static-libstdc++','-Wl,-z,max-page-size=16384','-Wl,-z,common-page-size=16384','-Wl,--no-undefined','-Wl,--exclude-libs,ALL','-Wl,-soname,libffmpeg_video.so',obj,*archives,'-ljnigraphics','-llog','-lm','-ldl','-o',lib]
 run(link)
 dest=ROOT.parent/'app/src/main/jniLibs/arm64-v8a/libffmpeg_video.so';dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(lib,dest)
 for name in ['COPYING.LGPLv2.1','LICENSE.md']:shutil.copy2(FF/name,kit/name)
 assets=ROOT/'assets/ffmpeg';assets.mkdir(parents=True,exist_ok=True)
 for name in ['COPYING.LGPLv2.1','LICENSE.md']:shutil.copy2(FF/name,assets/name)
 shutil.copy2(ROOT/'README.md',assets/'NOTICE.md')
 runtimeNotice=TOOLS/'android-ndk-r28c/toolchains/llvm/prebuilt/windows-x86_64/NOTICE'
 shutil.copy2(runtimeNotice,kit/'NDK-LLVM-NOTICE.txt');shutil.copy2(runtimeNotice,assets/'NDK-LLVM-NOTICE.txt')
 for relative in ['app/src/main/java/com/tumuyan/ncnn/realsr/FfmpegVideoDecoder.java','app/src/androidTest/java/com/tumuyan/ncnn/realsr/FfmpegVideoDecoderTest.java']:
  source=ROOT.parent/relative;shutil.copy2(source,kit/source.name)
 for name in ['build.log','host-test.log','elf-verification.log','compile-verification.log','test-first.log']:
  if (ROOT/name).exists():shutil.copy2(ROOT/name,kit/name)
 shutil.copy2(TOOLS/'android-ndk-r28c/source.properties',kit/'NDK-source.properties')
 shutil.copy2(TOOLS/'ffmpeg-7.1.2.tar.xz',kit/'ffmpeg-7.1.2.tar.xz')
 for p in ROOT.iterdir():
  if p.suffix in ['.py','.sh','.cpp','.h','.md']:shutil.copy2(p,kit/p.name)
 for name in ['configure-output.log','build-output.log','config.h']:shutil.copy2(FF/name,kit/name)
 shutil.copy2(FF/'ffbuild/config.mak',kit/'config.mak')
 (kit/'relink-command.json').write_text(json.dumps([str(a) for a in link],indent=2))
 manifest={p.name:digest(p) for p in kit.iterdir() if p.is_file() and p.name!='SHA256SUMS.json'}
 (kit/'SHA256SUMS.json').write_text(json.dumps(manifest,indent=2))
 print('ARM64 LIB SHA256 '+digest(dest));print('LGPL relink kit '+str(kit))
if __name__=='__main__':main()
