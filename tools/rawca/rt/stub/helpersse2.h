// The SSE2 helpers of RawTherapee's rtengine/helpersse2.h that CA_correct_RT.cc uses (same definitions), for the SSE build of
// the reference (the x86 RawTherapee path; the port follows the scalar path).
#pragma once
#include <emmintrin.h>
typedef __m128 vfloat;
typedef __m128i vint;
typedef __m128i vmask;
#define LVFU(x) _mm_loadu_ps(&(x))
#define STVFU(x, y) _mm_storeu_ps(&(x), y)
#define F2V(a) _mm_set1_ps((a))
#define ZEROV _mm_setzero_ps()
#define PERMUTEPS(a, mask) _mm_shuffle_ps(a, a, mask)
#define LC2VFU(a) _mm_shuffle_ps(LVFU(a), _mm_loadu_ps((&a) + 4), _MM_SHUFFLE(2, 0, 2, 0))
#define SQRV(x) ((x) * (x))
static inline vfloat vself(vmask mask, vfloat x, vfloat y) {
    return _mm_or_ps(_mm_and_ps(_mm_castsi128_ps(mask), x), _mm_andnot_ps(_mm_castsi128_ps(mask), y));
}
#define STC2VFU(a, v) {                                                \
        __m128 TST1V = _mm_loadu_ps(&a);                               \
        __m128 TST2V = _mm_unpacklo_ps(v, v);                          \
        vmask cmask = _mm_set_epi32(0xffffffff, 0, 0xffffffff, 0);     \
        _mm_storeu_ps(&a, vself(cmask, TST1V, TST2V));                 \
        TST1V = _mm_loadu_ps((&a) + 4);                                \
        TST2V = _mm_unpackhi_ps(v, v);                                 \
        _mm_storeu_ps((&a) + 4, vself(cmask, TST1V, TST2V));           \
    }
static inline vfloat vabsf(vfloat f) { return _mm_andnot_ps(_mm_set1_ps(-0.f), f); }
static inline vfloat vmul2f(vfloat a) { return a + a; }
static inline vfloat vmaxf(vfloat x, vfloat y) { return _mm_max_ps(x, y); }
static inline vfloat vminf(vfloat x, vfloat y) { return _mm_min_ps(x, y); }
static inline vfloat vclampf(vfloat value, vfloat low, vfloat high) { return vmaxf(vminf(high, value), low); }
static inline vmask vmaskf_ge(vfloat x, vfloat y) { return _mm_castps_si128(_mm_cmpge_ps(x, y)); }
static inline vmask vmaskf_gt(vfloat x, vfloat y) { return _mm_castps_si128(_mm_cmpgt_ps(x, y)); }
static inline vmask vmaskf_lt(vfloat x, vfloat y) { return _mm_castps_si128(_mm_cmplt_ps(x, y)); }
static inline vmask vmaskf_le(vfloat x, vfloat y) { return _mm_castps_si128(_mm_cmple_ps(x, y)); }
static inline vfloat vintpf(vfloat a, vfloat b, vfloat c) { return a * (b - c) + c; }
static inline float vhadd(vfloat a) {
    a += _mm_movehl_ps(a, a);
    return _mm_cvtss_f32(_mm_add_ss(a, _mm_shuffle_ps(a, a, 1)));
}
