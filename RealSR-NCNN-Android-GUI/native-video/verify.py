"""Verify release artifacts without reading signing credentials."""
from pathlib import Path
import zipfile,struct,hashlib,json,subprocess,os,xml.etree.ElementTree as ET
OUT=Path('F:/videoimageupscaler')
SDK=Path('C:/Users/RedMatter/AppData/Local/Android/Sdk')
ROOT=Path(__file__).resolve().parent.parent
result={}
for variant in ['debug','release']:
 apk=OUT/(os.environ.get('REALSR_APK_PREFIX','RealSR-Video4-')+variant+'.apk')
 subprocess.run([str(SDK/'build-tools/35.0.0/zipalign.exe'),'-c','-P','16','4',str(apk)],check=True)
 with zipfile.ZipFile(apk) as z:
  libs=[n for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')]
  expected={'lib/arm64-v8a/libncnn_video.so','lib/arm64-v8a/libffmpeg_video.so'}
  assert set(libs)==expected,libs
  for name in expected:
   packaged=z.read(name)
   assert packaged==(ROOT/'app/src/main/jniLibs/arm64-v8a'/Path(name).name).read_bytes(),name
   assert packaged[:5]==b'\x7fELF\x02' and struct.unpack_from('<H',packaged,18)[0]==183,name
   off=struct.unpack_from('<Q',packaged,32)[0];size,n=struct.unpack_from('<HH',packaged,54)
   for k in range(n):
    segment=struct.unpack_from('<IIQQQQQQ',packaged,off+k*size)
    if segment[0]==1:assert segment[7]>=16384 and (segment[2]-segment[3])%16384==0,(name,segment)
  for notice in ['COPYING.LGPLv2.1','LICENSE.md','NOTICE.md','NDK-LLVM-NOTICE.txt']:
   assert z.read('assets/ffmpeg/'+notice),notice
  data=z.read('lib/arm64-v8a/libncnn_video.so');assert data[:5]==b'\x7fELF\x02'
  assert struct.unpack_from('<H',data,18)[0]==183
  offset=struct.unpack_from('<Q',data,32)[0]
  entry,count=struct.unpack_from('<HH',data,54)
  align=[]
  for i in range(count):
   ph=struct.unpack_from('<IIQQQQQQ',data,offset+i*entry)
   if ph[0]==1:
    assert ph[7]>=16384 and (ph[2]-ph[3])%16384==0,ph
    align.append(ph[7])
  result[variant]={'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'jni_sha256':hashlib.sha256(data).hexdigest(),'pt_load_alignment':align,'zipalign_16kb':True}
  model_count=sum(n.endswith('.param') for n in z.namelist() if n.startswith('assets/realsr/'))
  assert model_count==39,model_count
  for model in ['models-FSRCNN-small','models-upconv_7_anime_style_art_rgb','models-upconv_7_photo','models-RealeSR-general-v3']:
   source=ROOT/'app/src/main/assets/realsr'/model
   for file in source.iterdir():
    if file.is_file(): assert z.read('assets/realsr/'+model+'/'+file.name)==file.read_bytes(),file
   assert z.read('assets/realsr/'+model+'/PROVENANCE.json')
  result[variant]['packaged_model_params']=model_count
  for name in ['assets/realsr/realcugan-ncnn','assets/realsr/realsr-ncnn']:
   assert z.read(name)[:4]==b'\x7fELF'
for variant in ['debug','release']:
 report=ROOT/('app/build/reports/lint-results-'+variant+'.xml')
 if report.exists():
  issues=ET.parse(report).getroot().findall('issue')
  result[variant]['lint']={'errors':sum(i.get('severity') in ['Error','Fatal'] for i in issues),'warnings':sum(i.get('severity')=='Warning' for i in issues)}
result['device_instrumentation']='UNEXECUTED: no adb device'
print(json.dumps(result,indent=2))
(OUT/os.environ.get('REALSR_NATIVE_REPORT','native-video4-verification.json')).write_text(json.dumps(result,indent=2))
