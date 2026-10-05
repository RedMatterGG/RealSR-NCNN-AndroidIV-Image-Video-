#pragma once
#include "gpu.h"
#include <stdexcept>
// Declared before command/extractor objects so those release their buffers first.
struct VideoGpuAllocators {
 ncnn::VulkanDevice* device;
 ncnn::VkAllocator* blob=nullptr;
 ncnn::VkAllocator* staging=nullptr;
 explicit VideoGpuAllocators(ncnn::VulkanDevice* d):device(d) {
  blob=d->acquire_blob_allocator();
  staging=d->acquire_staging_allocator();
  if(!blob || !staging) {
   if(blob) d->reclaim_blob_allocator(blob);
   if(staging) d->reclaim_staging_allocator(staging);
   throw std::runtime_error("Cannot acquire Vulkan allocators");
  }
 }
 ~VideoGpuAllocators() {device->reclaim_blob_allocator(blob);device->reclaim_staging_allocator(staging);}
 VideoGpuAllocators(const VideoGpuAllocators&)=delete;
 VideoGpuAllocators& operator=(const VideoGpuAllocators&)=delete;
};
