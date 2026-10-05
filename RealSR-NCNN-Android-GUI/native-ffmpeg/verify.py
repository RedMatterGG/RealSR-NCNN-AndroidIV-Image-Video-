import hashlib,json,pathlib,re,subprocess
root=pathlib.Path(__file__).resolve().parent
ndk=pathlib.Path('F:/videoimageupscaler/toolchain/android-ndk-r28c')
elf=ndk/'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
lib=root.parent/'app/src/main/jniLibs/arm64-v8a/libffmpeg_video.so'
text=subprocess.check_output([str(elf),'-h','-l','-d','-n','--dyn-syms',str(lib)],text=True)
(root/'elf-verification.log').write_text(text)
assert 'AArch64' in text and 'ELF64' in text
loads=[line for line in text.splitlines() if line.strip().startswith('LOAD')]
assert loads and all(int(line.split()[-1],16)>=16384 for line in loads)
needed=re.findall(r'Shared library: \[(.*?)\]',text)
assert set(needed)=={'libjnigraphics.so','liblog.so','libm.so','libdl.so','libc.so'},needed
note=re.search(r'NT_ANDROID_TYPE_IDENT\s+description data: ([0-9a-f ]+)',text)
assert note and int.from_bytes(bytes.fromhex(note.group(1))[:4],'little')==24
for symbol in ['nativeOpen','nativeNext','nativeDiagnostics','nativeClose']:
 assert 'Java_com_tumuyan_ncnn_realsr_FfmpegVideoDecoder_'+symbol in text
kit=pathlib.Path('F:/videoimageupscaler/ffmpeg-video-lgpl-relink')
for filename,sha in json.loads((kit/'SHA256SUMS.json').read_text()).items():
 assert hashlib.sha256((kit/filename).read_bytes()).hexdigest()==sha,filename
print('PASS ELF64 ARM64 API24; all '+str(len(loads))+' PT_LOAD >=16KB; system-only dependencies; four JNI exports; complete kit hash manifest')
print('LIB SHA256 '+hashlib.sha256(lib.read_bytes()).hexdigest())
