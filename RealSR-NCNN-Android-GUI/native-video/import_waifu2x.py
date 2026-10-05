"""Import official pinned Waifu2x kernels/weights into private video copies only."""
from pathlib import Path
import zipfile,hashlib,json,re,shutil,subprocess
H=Path(__file__).resolve().parent;TC=Path('F:/videoimageupscaler/toolchain')
S=TC/'waifu2x-source-20250915';D=H/'engines/Waifu2x'
assert subprocess.check_output(['git','-C',str(S),'rev-parse','HEAD'],text=True).strip()=='a86cfb043e6482b5f08778a114df9b5faf6e73b7', 'Waifu source pin mismatch'
D.mkdir(parents=True,exist_ok=True)
for p in (S/'src').glob('waifu2x*'):
 if p.suffix in ('.cpp','.h','.comp'):shutil.copy2(p,D/p.name)
for p in D.glob('*.comp'):
 data=p.read_bytes().replace(b'\r\n',b'\n').replace(b'clamp(uint(floor(v)), 0, 255)',b'uint(clamp(floor(v), 0.0, 255.0))');p.write_bytes(data);name=p.name.replace('.','_')+'_data'
 (D/(p.name+'.hex.h')).write_text('static const char '+name+'[] = {\n'+','.join(str(x if x<128 else x-256) for x in data)+'\n};\n')
cpp=(D/'waifu2x.cpp').read_text();header=(D/'waifu2x.h').read_text()
header=re.sub(r'#if _WIN32\n    int load.*?#endif','    int load(const std::string& parampath, const std::string& modelpath);',header,flags=re.S)
cpp=re.sub(r'#if _WIN32\nint Waifu2x::load.*?#endif','int Waifu2x::load(const std::string& parampath, const std::string& modelpath)',cpp,flags=re.S)
cpp=re.sub(r'#if _WIN32\n    \{\n        FILE\* fp.*?#endif','    if (net.load_param(parampath.c_str()) != 0) return -1;\n    if (net.load_model(modelpath.c_str()) != 0) return -2;\n    const auto ins=net.input_names(), outs=net.output_names();\n    if(ins.size()!=1 || outs.size()!=1 || std::string(ins[0])!="Input1" || std::string(outs[0])!="Eltwise4")return -3;',cpp,flags=re.S)
cpp=cpp.replace('#include "waifu2x.h"','#include "waifu2x.h"\n#include "../gpu_allocators.h"')
cpp=cpp.replace('    bicubic_2x->destroy_pipeline(net.opt);','    if(bicubic_2x) bicubic_2x->destroy_pipeline(net.opt);')
cpp=cpp.replace('    net.set_vulkan_device(vkdev);','    if(vkdev) net.set_vulkan_device(vkdev);')
cpp=re.sub(r'(\s+)(compile_spirv_module\([^;]+\));',r'\1if (\2 != 0) return -4;',cpp)
cpp=re.sub(r'(\s+)(waifu2x_(?:preproc|postproc)->create\([^;]+\));',r'\1if (\2 != 0) return -4;',cpp)
cpp=cpp.replace('        bicubic_2x->vkdev = vkdev;','        if(!bicubic_2x)return -4;\n        bicubic_2x->vkdev = vkdev;')
cpp=cpp.replace('        bicubic_2x->load_param(pd);','        if(bicubic_2x->load_param(pd))return -4;')
cpp=cpp.replace('        bicubic_2x->create_pipeline(net.opt);','        if(bicubic_2x->create_pipeline(net.opt))return -4;')
cpp=cpp.replace('    ncnn::VkAllocator* blob_vkallocator = vkdev->acquire_blob_allocator();\n    ncnn::VkAllocator* staging_vkallocator = vkdev->acquire_staging_allocator();','    VideoGpuAllocators lease(vkdev);\n    ncnn::VkAllocator* blob_vkallocator = lease.blob;\n    ncnn::VkAllocator* staging_vkallocator = lease.staging;')
cpp=cpp.replace('    vkdev->reclaim_blob_allocator(blob_vkallocator);\n    vkdev->reclaim_staging_allocator(staging_vkallocator);','    // RAII lease reclaims allocators after command buffers/extractors release.')
cpp=re.sub(r'(\s+)(ex\.(?:input|extract)\([^;]+\));',r'\1if (\2 != 0) return -5;',cpp)
cpp=cpp.replace('cmd.submit_and_wait();','if(cmd.submit_and_wait()!=0)return -6;')
cpp=re.sub(r'if \(tta_mode\)\n(\s+)(if \(compile_spirv_module[^\n]+)\n\s+else\n(\s+)(if \(compile_spirv_module[^\n]+)',r'if (tta_mode) {\n\1\2\n                    } else {\n\3\4\n                    }',cpp)
# Video path is RGB packed, never the Windows image-CLI BGR convention, even in host tests.
cpp=re.sub(r'#if _WIN32\n(.*?)#else\n(.*?)#endif',lambda m:m.group(2),cpp,flags=re.S)
# Preserve original replicate-padding/normalized RGB/native deconvolution, restrict supported graph.
cpp=cpp.replace('int Waifu2x::process(const ncnn::Mat& inimage, ncnn::Mat& outimage) const\n{','int Waifu2x::process(const ncnn::Mat& inimage, ncnn::Mat& outimage) const\n{\n    if(scale!=2 || noise < -1 || noise>3 || tilesize<32 || prepadding!=7 || inimage.empty() || inimage.elempack!=3 || outimage.elempack!=3 || outimage.w!=inimage.w*2 || outimage.h!=inimage.h*2)return -7;')
# Check each real output before its first shape access (both CPU and Vulkan paths).
cpp=cpp.replace('if (ex.extract("Eltwise4", out_tile_gpu[ti], cmd) != 0) return -5;','if (ex.extract("Eltwise4", out_tile_gpu[ti], cmd) != 0 || out_tile_gpu[ti].empty()) return -5;')
cpp=cpp.replace('if (ex.extract("Eltwise4", out_tile_gpu, cmd) != 0) return -5;','if (ex.extract("Eltwise4", out_tile_gpu, cmd) != 0 || out_tile_gpu.empty()) return -5;')
cpp=cpp.replace('if (ex.extract("Eltwise4", out_tile[ti]) != 0) return -5;','if (ex.extract("Eltwise4", out_tile[ti]) != 0 || out_tile[ti].empty()) return -5;')
cpp=cpp.replace('if (ex.extract("Eltwise4", out_tile) != 0) return -5;','if (ex.extract("Eltwise4", out_tile) != 0 || out_tile.empty() || out_tile.w<tile_w_nopad*scale || out_tile.h<tile_h_nopad*scale || out_tile.c!=3) return -5;')
# A configurable CPU workspace allocator also permits real allocation-failure tests.
header=header.replace('    int prepadding;', '    int prepadding;\n    ncnn::Allocator* frame_allocator = nullptr;')
cpp=cpp.replace('in_tile.create(in.w, in.h, 3);','in_tile.create(in.w, in.h, 3, size_t(4u), frame_allocator);\n                    if(in_tile.empty()) return -8;')
cpp=cpp.replace('out.create(tile_w_nopad * scale, tile_h_nopad * scale, channels);','out.create(tile_w_nopad * scale, tile_h_nopad * scale, channels, size_t(4u), frame_allocator);\n                    if(out.empty()) return -8;')
cpp=cpp.replace('            ncnn::Mat out;\n\n            if (tta_mode)', '            if(in.empty() || (channels==4 && in_nopad.empty())) return -8;\n            ncnn::Mat out;\n\n            if (tta_mode)')
cpp=cpp.replace('                    in_tile = in_tile_padded;','                    if(in_tile_padded.empty()) return -8;\n                    in_tile = in_tile_padded;')
(D/'waifu2x.cpp').write_text(cpp);(D/'waifu2x.h').write_text(header)
(D/'LICENSE').write_bytes((S/'LICENSE').read_bytes())
archive=TC/'waifu2x-ncnn-vulkan-20250915-windows.zip'
assert hashlib.sha256(archive.read_bytes()).hexdigest()=='7425be94b94e4c8f37a1e433ac0e0100c43790e2c37418f4b65d8235adfbdc87', 'Waifu official weights archive pin mismatch'
z=zipfile.ZipFile(archive)
for model in ['models-upconv_7_anime_style_art_rgb','models-upconv_7_photo']:
 M=H.parent/'app/src/main/assets/realsr'/model;M.mkdir(parents=True,exist_ok=True)
 prefix='waifu2x-ncnn-vulkan-20250915-windows/'+model+'/'
 hashes={}
 for name in z.namelist():
  if name.startswith(prefix) and name.endswith(('.bin','.param')):
   data=z.read(name);file=name.rsplit('/',1)[1];(M/file).write_bytes(data);hashes[file]=hashlib.sha256(data).hexdigest()
 (M/'LICENSE').write_bytes(z.read('waifu2x-ncnn-vulkan-20250915-windows/LICENSE'))
 (M/'LICENSE-models').write_bytes((TC/'nagadomi-LICENSE').read_bytes())
 (M/'PROVENANCE.json').write_text(json.dumps({'source':'https://github.com/nihui/waifu2x-ncnn-vulkan/releases/tag/20250915','source_commit':'a86cfb043e6482b5f08778a114df9b5faf6e73b7','archive_sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'weights_origin':'nagadomi/waifu2x upconv_7, official NCNN-converted FP16 release weights','license':'MIT; release LICENSE and original nagadomi model LICENSE retained','preprocessing':'packed RGB -> planar RGB /255, BORDER_REPLICATE padding7, original official CPU and Vulkan kernels','postprocessing':'native2x deconvolution, original crop/clamp/round normalized RGB; opaque video output','scale':2,'noise':[-1,0,1,2,3],'sha256':hashes},indent=2)+'\n')
(D/'SOURCE.json').write_text(json.dumps({'url':'https://github.com/nihui/waifu2x-ncnn-vulkan','commit':'a86cfb043e6482b5f08778a114df9b5faf6e73b7','license_source':'https://github.com/nagadomi/waifu2x/blob/cc385f97a9debfe611316aabfd5d8bb30ba2dbeb/LICENSE','upstream_sha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (S/'src').glob('waifu2x*') if p.is_file()},'private_changes':'See LIGHTWEIGHT-MODELS.md and reproducible import_waifu2x.py; original kernels retained'},indent=2)+'\n')
print('Imported pinned private Waifu2x kernels and official two upconv7 families.')
