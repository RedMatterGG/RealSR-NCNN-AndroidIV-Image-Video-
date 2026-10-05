// Compile-only ABI verification against the frozen Android 16 release header.
#include "android36-performance_hint.reference.h"
#include "gpu_hint_session.h"
#include <type_traits>
#define ABI(field, symbol) static_assert(std::is_same_v<decltype(GpuHintOps::field), decltype(&symbol)>, #symbol)
ABI(manager, APerformanceHint_getManager);
ABI(ordinary, APerformanceHint_createSession);
ABI(feature, APerformanceHint_isFeatureSupported);
ABI(config, ASessionCreationConfig_create);
ABI(release, ASessionCreationConfig_release);
ABI(tids, ASessionCreationConfig_setTids);
ABI(target, ASessionCreationConfig_setTargetWorkDurationNanos);
ABI(graphics, ASessionCreationConfig_setGraphicsPipeline);
ABI(create, APerformanceHint_createSessionUsingConfig);
ABI(close, APerformanceHint_closeSession);
ABI(reset, APerformanceHint_notifyWorkloadReset);
ABI(report, APerformanceHint_reportActualWorkDuration);
ABI(update, APerformanceHint_updateTargetWorkDuration);
static_assert(APERF_HINT_SESSIONS == 0 && APERF_HINT_GRAPHICS_PIPELINE == 3);
