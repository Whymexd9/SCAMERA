// rt_math.h stand-in (same definitions as RawTherapee's rtengine/rt_math.h for the functions CA_correct_RT uses).
#pragma once
#include "rtengine.h"
namespace rtengine {
template<typename T> constexpr T SQR(T x) { return x * x; }
template<typename T> constexpr const T& min(const T& a, const T& b) { return b < a ? b : a; }
template<typename T> constexpr const T& max(const T& a, const T& b) { return a < b ? b : a; }
template<typename T> constexpr const T& LIM(const T& val, const T& low, const T& high) { return max(low, min(val, high)); }
template<typename T> constexpr T intp(T a, T b, T c) { return a * (b - c) + c; }
}
#if defined(__SSE2__)
#include "helpersse2.h"
#endif
