// Minimal stand-ins for the RawTherapee headers CA_correct_RT.cc includes (tools/rawca parity harness only).
#pragma once
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <memory>
#include <utility>
namespace rtengine {
struct Settings { bool verbose = false; };
extern const Settings* settings;
class ProgressListener { public: virtual ~ProgressListener() = default; virtual void setProgress(double) = 0; };
// array2D<T>(width, height): rows of `width`, contiguous (the harness hands the data to the blur as one plane).
template<typename T> class array2D {
    int w = 0, h = 0; T* data = nullptr; T** rows = nullptr;
public:
    array2D(int width, int height) : w(width), h(height) {
        data = static_cast<T*>(std::calloc(size_t(w) * h + 16, sizeof(T)));
        rows = new T*[size_t(h)];
        for (int i = 0; i < h; ++i) rows[i] = data + size_t(i) * w;
    }
    array2D(const array2D&) = delete;
    ~array2D() { delete[] rows; std::free(data); }
    T* operator[](int row) { return rows[row]; }
    const T* operator[](int row) const { return rows[row]; }
    operator T**() { return rows; }
    T* plane() { return data; }
    int width() const { return w; }
    int height() const { return h; }
};
}
