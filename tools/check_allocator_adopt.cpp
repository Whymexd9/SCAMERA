// P371 (speed, zero_copy_read): the merge client reads the worker's result in place - the result memfd adopted as an arena
// (allocator.cpp Allocator.arenaAdopt), the RGB and the clip flags as its views - instead of a pread into 85 MB of fresh native
// buffers. This check builds the real allocator.cpp on the host against the NDK's jni.h with a fake JNIEnv (direct buffers only)
// and checks the views' bytes, the hole the RGB view's free punches, the unmap once every view is freed, and the failures.
// Review B1: the trailers between the views (the merged DNG and the effective map, >= 64 MB from ~21 MP with RAW saving or on a
// 2x grid) read after arenaRelease keep their bytes (its release punched every gap no view held: zeros before the reads), and
// arenaPunch gives back exactly the range the client read, never a page a view holds.
//
// Build (CI copies jni.h from the NDK into /tmp/native-engine-host first):
//   g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined -I/tmp/native-engine-host -Itools/host-include tools/check_allocator_adopt.cpp
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <sys/mman.h>
#include <unistd.h>

#include "../app/src/main/cpp/allocator.cpp"

namespace {
struct Direct { void* address; jlong capacity; };
std::vector<Direct*> made;
jobject newDirect(JNIEnv*, void* address, jlong capacity) {
    made.push_back(new Direct{address, capacity});
    return reinterpret_cast<jobject>(made.back());
}
void* address(JNIEnv*, jobject buffer) { return buffer ? reinterpret_cast<Direct*>(buffer)->address : nullptr; }
jlong capacity(JNIEnv*, jobject buffer) { return buffer ? reinterpret_cast<Direct*>(buffer)->capacity : -1; }

int failures = 0;
void expect(bool ok, const std::string& what) {
    if (!ok) { std::printf("FAIL: %s\n", what.c_str()); failures++; }
}
size_t arenaCount() { std::lock_guard<std::mutex> lock(arenaLock); return arenas.size(); }
}

int main() {
    JNINativeInterface functions{};
    functions.NewDirectByteBuffer = newDirect;
    functions.GetDirectBufferAddress = address;
    functions.GetDirectBufferCapacity = capacity;
    JNIEnv env{};
    env.functions = &functions;

    // a result as the worker leaves it: halves (here 72 MB + a page), then a 12 MB trailer of clip flags
    const size_t rgb = (size_t(72) << 20) + 4096, clip = size_t(12) << 20, total = rgb + clip;
    const int fd = int(syscall(__NR_memfd_create, "check-out", 1));
    expect(fd >= 0 && ftruncate(fd, off_t(total)) == 0, "memfd");
    {
        void* w = mmap(nullptr, total, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        expect(w != MAP_FAILED, "fill map");
        uint8_t* p = static_cast<uint8_t*>(w);
        for (size_t i = 0; i < total; ++i) p[i] = uint8_t((i * 2654435761u) >> 24);
        munmap(w, total);
    }
    const size_t before = arenaCount();
    const jint id = Java_com_particlesdevs_photoncamera_util_Allocator_arenaAdopt(&env, nullptr, fd, jlong(total));
    expect(id > 0 && arenaCount() == before + 1, "adopted");
    jobject rgbView = Java_com_particlesdevs_photoncamera_util_Allocator_arenaView(&env, nullptr, id, 0, jint(rgb));
    jobject clipView = Java_com_particlesdevs_photoncamera_util_Allocator_arenaView(&env, nullptr, id, jlong(rgb), jint(clip));
    expect(rgbView && clipView, "views");
    Java_com_particlesdevs_photoncamera_util_Allocator_arenaRelease(&env, nullptr, id);
    expect(arenaCount() == before + 1, "a released arena stays while its views live");
    // the views read the bytes the worker wrote, in place
    bool same = true;
    const uint8_t* r = static_cast<const uint8_t*>(address(&env, rgbView));
    const uint8_t* c = static_cast<const uint8_t*>(address(&env, clipView));
    for (size_t i = 0; i < rgb && same; i += 509) same = r[i] == uint8_t((i * 2654435761u) >> 24);
    for (size_t i = 0; i < clip && same; i += 509) same = c[i] == uint8_t(((rgb + i) * 2654435761u) >> 24);
    expect(same, "the views hold the result's bytes");
    expect(capacity(&env, rgbView) == jlong(rgb) && capacity(&env, clipView) == jlong(clip), "view sizes");
    // the caller's fd stays its own (a duplicate is held)
    expect(fcntl(fd, F_GETFD) >= 0, "the caller keeps its fd");
    // the RGB view freed (ScamRgb after the upload): its pages go back at once (a hole), the clip flags stay
    Java_com_particlesdevs_photoncamera_util_Allocator_free(&env, nullptr, rgbView);
    {
        std::vector<uint8_t> head(4096), tail(4096);
        expect(pread(fd, head.data(), head.size(), 0) == ssize_t(head.size()), "pread head");
        bool zero = true;
        for (uint8_t v : head) zero &= v == 0;
        expect(zero, "the RGB view's pages are punched once it is freed");
        expect(pread(fd, tail.data(), tail.size(), off_t(rgb)) == ssize_t(tail.size()), "pread tail");
        bool kept = true;
        for (size_t i = 0; i < tail.size(); ++i) kept &= tail[i] == uint8_t(((rgb + i) * 2654435761u) >> 24);
        expect(kept, "the clip flags stay while their view lives");
        expect(c[4097] == uint8_t(((rgb + 4097) * 2654435761u) >> 24), "the clip view still reads");
    }
    expect(arenaCount() == before + 1, "still mapped for the clip view");
    Java_com_particlesdevs_photoncamera_util_Allocator_free(&env, nullptr, clipView);
    expect(arenaCount() == before, "unmapped and closed once every view is freed");
    expect(Java_com_particlesdevs_photoncamera_util_Allocator_arenaFd(&env, nullptr, id) == -1, "the arena is gone");
    // failures: no fd, no size, a size the fd cannot map
    expect(Java_com_particlesdevs_photoncamera_util_Allocator_arenaAdopt(&env, nullptr, -1, 4096) == 0, "bad fd");
    expect(Java_com_particlesdevs_photoncamera_util_Allocator_arenaAdopt(&env, nullptr, fd, 0) == 0, "no size");
    int p[2];
    expect(pipe(p) == 0, "pipe");
    expect(Java_com_particlesdevs_photoncamera_util_Allocator_arenaAdopt(&env, nullptr, p[0], 4096) == 0, "a pipe cannot be mapped");
    expect(arenaCount() == before, "no arena left by the failures");
    close(p[0]); close(p[1]);
    close(fd);

    // review B1: RGB | merged DNG | effective map | clip flags, the gap between the two views 80 MB (>= the 64 MB punch size)
    {
        const size_t MB = size_t(1) << 20, vRgb = 80 * MB, vDng = 64 * MB, vEff = 16 * MB, vClip = 8 * MB;
        const size_t all = vRgb + vDng + vEff + vClip;
        auto pat = [](size_t i) { return uint8_t(((i * 2654435761u) >> 24) | 1); }; // never 0
        const int out = int(syscall(__NR_memfd_create, "check-trailers", 1));
        expect(out >= 0 && ftruncate(out, off_t(all)) == 0, "trailers memfd");
        {
            uint8_t* w = static_cast<uint8_t*>(mmap(nullptr, all, PROT_READ | PROT_WRITE, MAP_SHARED, out, 0));
            expect(w != MAP_FAILED, "trailers fill map");
            for (size_t i = 0; i < all; ++i) w[i] = pat(i);
            munmap(w, all);
        }
        const jint t = Java_com_particlesdevs_photoncamera_util_Allocator_arenaAdopt(&env, nullptr, out, jlong(all));
        jobject tRgb = Java_com_particlesdevs_photoncamera_util_Allocator_arenaView(&env, nullptr, t, 0, jint(vRgb));
        jobject tClip = Java_com_particlesdevs_photoncamera_util_Allocator_arenaView(&env, nullptr, t, jlong(vRgb + vDng + vEff), jint(vClip));
        expect(t > 0 && tRgb && tClip, "trailers: adopted, two views");
        // the client's order before the fix: released with its views, then the trailers read from the memfd
        Java_com_particlesdevs_photoncamera_util_Allocator_arenaRelease(&env, nullptr, t);
        std::vector<uint8_t> trailers(vDng + vEff);
        expect(pread(out, trailers.data(), trailers.size(), off_t(vRgb)) == ssize_t(trailers.size()), "trailers pread");
        size_t changed = 0;
        for (size_t i = 0; i < trailers.size(); ++i) changed += trailers[i] != pat(vRgb + i);
        expect(changed == 0, "the merged DNG and the effective map keep their bytes after the release (" + std::to_string(changed) + " changed)");
        // arenaPunch: refused over a view, for a range out of bounds, for an arena not adopted; the read trailers given back
        expect(!Java_com_particlesdevs_photoncamera_util_Allocator_arenaPunch(&env, nullptr, t, jlong(vRgb - 4096), jlong(vDng)), "no punch over the RGB view");
        expect(!Java_com_particlesdevs_photoncamera_util_Allocator_arenaPunch(&env, nullptr, t, jlong(vRgb), jlong(vDng + vEff + 4096)), "no punch over the clip view");
        expect(!Java_com_particlesdevs_photoncamera_util_Allocator_arenaPunch(&env, nullptr, t, jlong(all - 4096), 8192), "no punch beyond the arena");
        const jint ring = Java_com_particlesdevs_photoncamera_util_Allocator_arenaCreate(&env, nullptr, jlong(8 * MB));
        expect(ring > 0 && !Java_com_particlesdevs_photoncamera_util_Allocator_arenaPunch(&env, nullptr, ring, 0, jlong(4 * MB)), "no punch in a shot's arena");
        Java_com_particlesdevs_photoncamera_util_Allocator_arenaRelease(&env, nullptr, ring);
        expect(Java_com_particlesdevs_photoncamera_util_Allocator_arenaPunch(&env, nullptr, t, jlong(vRgb), jlong(vDng + vEff)), "the read trailers punched");
        expect(pread(out, trailers.data(), trailers.size(), off_t(vRgb)) == ssize_t(trailers.size()), "trailers pread after the punch");
        bool zero = true;
        for (uint8_t v : trailers) zero &= v == 0;
        expect(zero, "the punched trailers read as zeros (their pages went back)");
        const uint8_t* rv = static_cast<const uint8_t*>(address(&env, tRgb));
        const uint8_t* cv = static_cast<const uint8_t*>(address(&env, tClip));
        bool views = true;
        for (size_t i = 0; i < vRgb; i += 4093) views &= rv[i] == pat(i);
        views &= rv[vRgb - 1] == pat(vRgb - 1);
        for (size_t i = 0; i < vClip; i += 4093) views &= cv[i] == pat(vRgb + vDng + vEff + i);
        expect(views, "the views keep their bytes around the punched range");
        Java_com_particlesdevs_photoncamera_util_Allocator_free(&env, nullptr, tRgb);
        Java_com_particlesdevs_photoncamera_util_Allocator_free(&env, nullptr, tClip);
        expect(Java_com_particlesdevs_photoncamera_util_Allocator_arenaFd(&env, nullptr, t) == -1, "trailers: the arena gone after its views");
        close(out);
    }
    expect(arenaCount() == before, "no arena left");
    for (Direct* d : made) delete d;
    if (failures) { std::printf("allocator adopt: %d failure(s)\n", failures); return 1; }
    std::printf("allocator adopt: views of the adopted result read in place, the RGB view's free punches its pages, unmapped after the last view; "
                "the trailers between the views kept through the release, arenaPunch only where no view is: ok\n");
    return 0;
}
