#pragma once
#include <algorithm>
namespace video {
inline bool cuganSyncNeeded(int width, int height, int tile, int syncgap) {
  // Rough syncgap=3 samples only interior cells of a 32px grid.
  // A dimension <=64 has no interior sample, so use ordinary tiled
  // processing instead of dereferencing feature_vectors[0] in either backend.
  if (syncgap == 3 && std::min(width, height) <= 64) return false;
  return tile < std::max(width, height);
}
}
