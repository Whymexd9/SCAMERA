// Starts the native NICE/neural worker as a child of the app (no su): the
// worker runs in the app sandbox from nativeLibraryDir, with chosen file
// descriptors (memfd burst/result) mapped to 3, 4, ... and stdout+stderr on a pipe.
#include <jni.h>
#include <cerrno>
#include <csignal>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include <vector>

#ifndef __NR_close_range
#define __NR_close_range 436
#endif

namespace {
std::vector<std::string> strings(JNIEnv* env,jobjectArray array) {
    std::vector<std::string> out;
    const jsize n=array?env->GetArrayLength(array):0;
    for(jsize i=0;i<n;++i) {
        auto s=static_cast<jstring>(env->GetObjectArrayElement(array,i));
        const char* c=env->GetStringUTFChars(s,nullptr);
        out.emplace_back(c);
        env->ReleaseStringUTFChars(s,c);env->DeleteLocalRef(s);
    }
    return out;
}
std::vector<char*> pointers(std::vector<std::string>& v) {
    std::vector<char*> p;
    for(auto& s:v)p.push_back(&s[0]);
    p.push_back(nullptr);
    return p;
}
}

// Returns {pid, readFd of the child's stdout+stderr}, or null on failure.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_particlesdevs_photoncamera_util_WorkerSpawn_spawn(JNIEnv* env,jclass,jobjectArray jargv,jobjectArray jenv,jintArray jfds) {
    auto argvStrings=strings(env,jargv),envStrings=strings(env,jenv);
    if(argvStrings.empty())return nullptr;
    std::vector<int> fds;
    if(jfds) {
        const jsize n=env->GetArrayLength(jfds);fds.resize(n);
        env->GetIntArrayRegion(jfds,0,n,fds.data());
    }
    auto argv=pointers(argvStrings),envp=pointers(envStrings);
    // Sources are moved above the target range first, so mapping never clobbers one.
    std::vector<int> high;
    for(int fd:fds) {
        int h=fcntl(fd,F_DUPFD_CLOEXEC,64);
        if(h<0){for(int x:high)close(x);return nullptr;}
        high.push_back(h);
    }
    int pipeFds[2];
    if(pipe2(pipeFds,O_CLOEXEC)){for(int x:high)close(x);return nullptr;}
    const int devNull=open("/dev/null",O_RDONLY|O_CLOEXEC);
    const pid_t pid=fork();
    if(pid==0) {
        // Child: async-signal-safe calls only.
        if(devNull>=0)dup2(devNull,0);
        dup2(pipeFds[1],1);dup2(pipeFds[1],2);
        for(size_t i=0;i<high.size();++i)dup2(high[i],3+int(i));
        const unsigned first=3u+unsigned(high.size());
        syscall(__NR_close_range,first,~0u,0u);
        sigset_t none;sigemptyset(&none);sigprocmask(SIG_SETMASK,&none,nullptr);
        execve(argv[0],argv.data(),envp.data());
        _exit(127);
    }
    for(int x:high)close(x);
    if(devNull>=0)close(devNull);
    close(pipeFds[1]);
    if(pid<0){close(pipeFds[0]);return nullptr;}
    jintArray result=env->NewIntArray(2);
    const jint values[2]={pid,pipeFds[0]};
    env->SetIntArrayRegion(result,0,2,values);
    return result;
}

// Exit status (0..255), 128+signal, or -1 while still running after timeoutMs.
extern "C" JNIEXPORT jint JNICALL
Java_com_particlesdevs_photoncamera_util_WorkerSpawn_waitFor(JNIEnv*,jclass,jint pid,jlong timeoutMs) {
    timespec start{};clock_gettime(CLOCK_MONOTONIC,&start);
    for(;;) {
        int status=0;
        const pid_t r=waitpid(pid,&status,WNOHANG);
        if(r==pid)return WIFEXITED(status)?WEXITSTATUS(status):WIFSIGNALED(status)?128+WTERMSIG(status):255;
        if(r<0 && errno!=EINTR)return 255;
        timespec now{};clock_gettime(CLOCK_MONOTONIC,&now);
        const long long elapsed=(now.tv_sec-start.tv_sec)*1000LL+(now.tv_nsec-start.tv_nsec)/1000000;
        if(elapsed>=timeoutMs)return -1;
        usleep(2000);
    }
}
