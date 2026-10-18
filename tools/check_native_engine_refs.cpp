// P214a: NativeEngine's lookups (app/src/main/cpp/native-engine.cpp) must leave no JNI global reference behind. The runtime
// aborts at 51200 global references ("global reference table overflow", OPPO Find X7 Ultra, 2026-10-17 11:09 and 11:17:
// 5,636 Method and 5,633 Field entries); before P214a every lookup left about nine. This check builds the real source on
// the host against the NDK's jni.h with a fake JNIEnv / JavaVM that counts NewGlobalRef / DeleteGlobalRef, enforces the
// same 51200 limit, and follows the JNI rules a real runtime checks: a local reference belongs to its thread and dies when
// the native call returns (or its thread detaches), a deleted global reference must not be used again.
//
// Build (CI copies jni.h from the NDK into /tmp/native-engine-host first):
//   g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined -I/tmp/native-engine-host tools/check_native_engine_refs.cpp -ldl
#define SCAM_HOST_TEST 1

#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

// android/log.h stand-in (native-engine.cpp skips its include under SCAM_HOST_TEST).
enum { ANDROID_LOG_DEBUG = 3, ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6 };
static int __android_log_print(int, const char *, const char *, ...) { return 0; }

#ifndef NATIVE_ENGINE_CPP
#define NATIVE_ENGINE_CPP "../app/src/main/cpp/native-engine.cpp"
#endif
#include NATIVE_ENGINE_CPP

namespace {

constexpr size_t kGlobalLimit = 51200;  // ART's global reference table size

struct Obj {
    enum Kind { CLASS, STRING, ARRAY, METHOD, FIELD } kind;
    std::string text;                 // class name, string value, member name
    std::vector<Obj *> elements;      // ARRAY
    const Obj *owner = nullptr;       // METHOD / FIELD: declaring class
    size_t params = 0;                // METHOD: parameter count

    Obj(Kind k, std::string t) : kind(k), text(std::move(t)) {}
};

struct Ref {
    Obj *obj;
    bool global;
    bool dead = false;
    std::thread::id thread;           // local references only
};

std::mutex lock;
std::vector<Ref *> refs;              // every handle ever made (never freed, so a stale one is still detectable)
thread_local std::vector<Ref *> locals;  // this thread's live local references
size_t liveGlobals = 0, newGlobals = 0, deletedGlobals = 0, attaches = 0, detaches = 0;
std::vector<std::string> errors;
thread_local bool pendingException = false;
thread_local bool attached = false;

void fail(const std::string &what) {
    errors.push_back(what);
}

Obj classClass{Obj::CLASS, "java.lang.Class"};
Obj stringClass{Obj::CLASS, "java.lang.String"};
int getDeclaredMethodId, getDeclaredFieldId;

jobject makeRef(Obj *o, bool global) {
    if (o == nullptr) return nullptr;
    std::lock_guard<std::mutex> g(lock);
    Ref *r = new Ref{o, global, false, std::this_thread::get_id()};
    refs.push_back(r);
    if (!global) locals.push_back(r);
    if (global) {
        newGlobals++;
        if (++liveGlobals > kGlobalLimit) {
            std::fprintf(stderr, "FAIL: global reference table overflow (max=%zu)\n", kGlobalLimit);
            std::exit(1);
        }
    }
    return reinterpret_cast<jobject>(r);
}

// The object behind a reference, after the checks a real runtime makes (CheckJNI / the reference tables).
Obj *deref(jobject ref, const char *where) {
    if (ref == nullptr) return nullptr;
    Ref *r = reinterpret_cast<Ref *>(ref);
    std::lock_guard<std::mutex> g(lock);
    if (r->dead) {
        fail(std::string(where) + ": use of a deleted or expired reference");
        return r->obj;
    }
    if (!r->global && r->thread != std::this_thread::get_id()) fail(std::string(where) + ": local reference used on another thread");
    return r->obj;
}

// Locals of this thread die: on return of a native call and on DetachCurrentThread.
void dropLocals() {
    std::lock_guard<std::mutex> g(lock);
    for (Ref *r : locals) r->dead = true;
    locals.clear();
}

size_t liveLocalsHere() {
    std::lock_guard<std::mutex> g(lock);
    size_t n = 0;
    for (Ref *r : locals)
        if (!r->dead) n++;
    return n;
}

jobject NewGlobalRef_(JNIEnv *, jobject ref) {
    return makeRef(deref(ref, "NewGlobalRef"), true);
}

void DeleteGlobalRef_(JNIEnv *, jobject ref) {
    if (ref == nullptr) return;
    Ref *r = reinterpret_cast<Ref *>(ref);
    std::lock_guard<std::mutex> g(lock);
    if (!r->global) { fail("DeleteGlobalRef of a local reference"); return; }
    if (r->dead) { fail("DeleteGlobalRef twice"); return; }
    r->dead = true;
    liveGlobals--;
    deletedGlobals++;
}

jobject NewLocalRef_(JNIEnv *, jobject ref) {
    return makeRef(deref(ref, "NewLocalRef"), false);
}

void DeleteLocalRef_(JNIEnv *, jobject ref) {
    if (ref == nullptr) return;
    Ref *r = reinterpret_cast<Ref *>(ref);
    std::lock_guard<std::mutex> g(lock);
    if (r->global) { fail("DeleteLocalRef of a global reference"); return; }
    r->dead = true;
}

jclass GetObjectClass_(JNIEnv *, jobject ref) {
    Obj *o = deref(ref, "GetObjectClass");
    if (o == nullptr) { fail("GetObjectClass(null)"); return nullptr; }
    Obj *cls = o->kind == Obj::CLASS ? &classClass : o->kind == Obj::STRING ? &stringClass : &classClass;
    return reinterpret_cast<jclass>(makeRef(cls, false));
}

jmethodID GetMethodID_(JNIEnv *, jclass cls, const char *name, const char *sig) {
    Obj *c = deref(cls, "GetMethodID");
    if (c != &classClass) fail("GetMethodID on a class other than java.lang.Class");
    if (!std::strcmp(name, "getDeclaredMethod") && !std::strcmp(sig, "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;"))
        return reinterpret_cast<jmethodID>(&getDeclaredMethodId);
    if (!std::strcmp(name, "getDeclaredField") && !std::strcmp(sig, "(Ljava/lang/String;)Ljava/lang/reflect/Field;"))
        return reinterpret_cast<jmethodID>(&getDeclaredFieldId);
    fail(std::string("unexpected GetMethodID ") + name + sig);
    return nullptr;
}

// Class.getDeclaredMethod / getDeclaredField: members whose name starts with "missing" do not exist (exception pending).
jobject CallObjectMethodV_(JNIEnv *, jobject target, jmethodID id, va_list args) {
    Obj *owner = deref(target, "CallObjectMethod target");
    if (owner == nullptr || owner->kind != Obj::CLASS) { fail("lookup on a non-class"); return nullptr; }
    jstring name = va_arg(args, jstring);
    Obj *n = deref(name, "member name");
    if (n == nullptr || n->kind != Obj::STRING) { fail("member name is not a string"); return nullptr; }
    size_t params = 0;
    if (id == reinterpret_cast<jmethodID>(&getDeclaredMethodId)) {
        jobjectArray types = va_arg(args, jobjectArray);
        Obj *a = deref(types, "parameter types");
        if (a != nullptr) {
            if (a->kind != Obj::ARRAY) { fail("parameter types is not an array"); return nullptr; }
            for (Obj *e : a->elements)
                if (e == nullptr || e->kind != Obj::CLASS) fail("parameter type is not a class");
            params = a->elements.size();
        }
    } else if (id != reinterpret_cast<jmethodID>(&getDeclaredFieldId)) {
        fail("unexpected CallObjectMethod");
        return nullptr;
    }
    if (n->text.rfind("missing", 0) == 0) {
        pendingException = true;  // NoSuchMethodException / NoSuchFieldException
        return nullptr;
    }
    Obj *member = new Obj{id == reinterpret_cast<jmethodID>(&getDeclaredMethodId) ? Obj::METHOD : Obj::FIELD, n->text};
    member->owner = owner;
    member->params = params;
    return makeRef(member, false);
}

jboolean ExceptionCheck_(JNIEnv *) { return pendingException ? JNI_TRUE : JNI_FALSE; }
void ExceptionDescribe_(JNIEnv *) {}
void ExceptionClear_(JNIEnv *) { pendingException = false; }

jsize GetArrayLength_(JNIEnv *, jarray array) {
    Obj *a = deref(array, "GetArrayLength");
    return a == nullptr ? 0 : (jsize) a->elements.size();
}

jobject GetObjectArrayElement_(JNIEnv *, jobjectArray array, jsize i) {
    Obj *a = deref(array, "GetObjectArrayElement");
    return makeRef(a->elements.at(i), false);
}

void SetObjectArrayElement_(JNIEnv *, jobjectArray array, jsize i, jobject value) {
    Obj *a = deref(array, "SetObjectArrayElement");
    a->elements.at(i) = deref(value, "SetObjectArrayElement value");
}

JNINativeInterface makeEnvTable() {
    JNINativeInterface t{};
    t.NewGlobalRef = NewGlobalRef_;
    t.DeleteGlobalRef = DeleteGlobalRef_;
    t.NewLocalRef = NewLocalRef_;
    t.DeleteLocalRef = DeleteLocalRef_;
    t.GetObjectClass = GetObjectClass_;
    t.GetMethodID = GetMethodID_;
    t.CallObjectMethodV = CallObjectMethodV_;
    t.ExceptionCheck = ExceptionCheck_;
    t.ExceptionDescribe = ExceptionDescribe_;
    t.ExceptionClear = ExceptionClear_;
    t.GetArrayLength = GetArrayLength_;
    t.GetObjectArrayElement = GetObjectArrayElement_;
    t.SetObjectArrayElement = SetObjectArrayElement_;
    return t;
}

const JNINativeInterface envTable = makeEnvTable();
JNIEnv fakeEnv{&envTable};

jint AttachCurrentThread_(JavaVM *, JNIEnv **env, void *) {
    *env = &fakeEnv;
    attached = true;
    std::lock_guard<std::mutex> g(lock);
    attaches++;
    return JNI_OK;
}

jint DetachCurrentThread_(JavaVM *) {
    if (!attached) fail("DetachCurrentThread on a thread that is not attached");
    attached = false;
    dropLocals();
    std::lock_guard<std::mutex> g(lock);
    detaches++;
    return JNI_OK;
}

jint GetEnv_(JavaVM *, void **env, jint) {
    *env = &fakeEnv;
    return JNI_OK;
}

JNIInvokeInterface makeVmTable() {
    JNIInvokeInterface t{};
    t.AttachCurrentThread = AttachCurrentThread_;
    t.DetachCurrentThread = DetachCurrentThread_;
    t.GetEnv = GetEnv_;
    return t;
}

const JNIInvokeInterface vmTable = makeVmTable();
JavaVM fakeVm{&vmTable};

// One native call from "Java": the arguments are locals of this frame, the result is checked, then the frame's locals die.
struct Lookup {
    bool method;
    std::string owner, name;
    size_t params;          // SIZE_MAX: a null parameter array
    bool found;
};

bool runOne(const Lookup &l, Obj &ownerClass, std::vector<Obj *> &paramClasses) {
    size_t before = liveGlobals;
    jclass cls = reinterpret_cast<jclass>(makeRef(&ownerClass, false));
    Obj *nameObj = new Obj{Obj::STRING, l.name};
    jstring name = reinterpret_cast<jstring>(makeRef(nameObj, false));
    jobject result;
    if (l.method) {
        jobjectArray types = nullptr;
        if (l.params != SIZE_MAX) {
            Obj *array = new Obj{Obj::ARRAY, "Class[]"};
            for (size_t i = 0; i < l.params; i++) array->elements.push_back(paramClasses[i % paramClasses.size()]);
            types = reinterpret_cast<jobjectArray>(makeRef(array, false));
        }
        result = Java_com_particlesdevs_photoncamera_api_NativeEngine_nativeGetCameraMethod(&fakeEnv, nullptr, cls, name, types);
    } else {
        result = Java_com_particlesdevs_photoncamera_api_NativeEngine_nativeGetCameraField(&fakeEnv, nullptr, cls, name);
    }
    bool ok = true;
    if (pendingException) { fail("exception left pending for the Java caller: " + l.name); ok = false; }
    if (l.found) {
        if (result == nullptr) { fail("lookup returned null: " + l.name); return false; }
        Ref *r = reinterpret_cast<Ref *>(result);
        if (r->global) { fail("result is a global reference (nobody deletes it): " + l.name); ok = false; }
        Obj *m = deref(result, "result");
        if (m->kind != (l.method ? Obj::METHOD : Obj::FIELD) || m->text != l.name || m->owner != &ownerClass
            || (l.method && m->params != (l.params == SIZE_MAX ? 0 : l.params))) {
            fail("wrong member returned for " + l.name);
            ok = false;
        }
    } else if (result != nullptr) {
        fail("missing member returned an object: " + l.name);
        ok = false;
    }
    dropLocals();  // the native call returns
    if (liveGlobals != before) {
        char buf[160];
        std::snprintf(buf, sizeof buf, "global references after %s %s: %zu -> %zu", l.method ? "method" : "field", l.name.c_str(), before, liveGlobals);
        fail(buf);
        ok = false;
    }
    return ok;
}

}  // namespace

int main() {
    if (JNI_OnLoad(&fakeVm, nullptr) != JNI_VERSION_1_6) {
        std::fprintf(stderr, "FAIL: JNI_OnLoad\n");
        return 1;
    }
    Obj results{Obj::CLASS, "android.hardware.camera2.CaptureResult"};
    Obj metadata{Obj::CLASS, "android.hardware.camera2.impl.CameraMetadataNative"};
    Obj device{Obj::CLASS, "android.hardware.camera2.impl.CameraDeviceImpl"};
    Obj key{Obj::CLASS, "android.hardware.camera2.CaptureResult$Key"};
    Obj object{Obj::CLASS, "java.lang.Object"};
    Obj integer{Obj::CLASS, "int"};
    std::vector<Obj *> params{&key, &object, &integer};

    // The calls CameraReflectionApi.set(...) makes, plus createCustomCaptureSession (5 parameters), a null parameter
    // array and members that do not exist. 6000 rounds = 30001 lookups; the old code kept 25 global references per round
    // (150000, about 3 times the table) and overflowed in round 2049.
    const std::vector<std::pair<Obj *, Lookup>> calls = {
        {&results, {false, "", "mResults", 0, true}},
        {&metadata, {true, "", "set", 2, true}},
        {&device, {true, "", "createCustomCaptureSession", 5, true}},
        {&metadata, {true, "", "missingSetter", 2, false}},
        {&results, {false, "", "missingField", 0, false}},
    };
    const int rounds = 6000;
    for (int i = 0; i < rounds && errors.empty(); i++) {
        for (const auto &c : calls) runOne(c.second, *c.first, params);
        if (i == 0) runOne({true, "", "getKeys", SIZE_MAX, true}, metadata, params);
    }
    const size_t lookups = (size_t) rounds * calls.size() + 1;
    if (liveLocalsHere() != 0) fail("locals of the calling thread outlive the call");
    if (liveGlobals != 0 || newGlobals != deletedGlobals) {
        char buf[160];
        std::snprintf(buf, sizeof buf, "global references: %zu created, %zu deleted, %zu still live", newGlobals, deletedGlobals, liveGlobals);
        fail(buf);
    }
    if (errors.empty() && (attaches != lookups || detaches != lookups)) {
        char buf[160];
        std::snprintf(buf, sizeof buf, "lookup threads: %zu attached, %zu detached for %zu lookups", attaches, detaches, lookups);
        fail(buf);
    }
    if (!errors.empty()) {
        for (size_t i = 0; i < errors.size() && i < 20; i++) std::fprintf(stderr, "FAIL: %s\n", errors[i].c_str());
        std::fprintf(stderr, "FAIL: %zu problem(s)\n", errors.size());
        return 1;
    }
    std::printf("native engine refs ok: %zu lookups, %zu global references created and deleted, 0 live\n", lookups, newGlobals);
    return 0;
}
