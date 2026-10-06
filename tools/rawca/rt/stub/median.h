// median.h stand-in: the median of 9 values (RT uses a sorting network; the value is the same).
#pragma once
#include <algorithm>
#include <array>
namespace rtengine {
template<typename T> inline T median(std::array<T, 9> p) { std::nth_element(p.begin(), p.begin() + 4, p.end()); return p[4]; }
}
