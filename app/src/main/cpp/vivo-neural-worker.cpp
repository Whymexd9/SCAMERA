#include "vivo-neural-runtime.h"
#include <jni.h>
#include <unistd.h>
#include <signal.h>

extern "C" JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_processing_opengl_postpipeline_VivoNeuralWorker_nativeRun(
        JNIEnv* env,jclass,jstring input,jstring output,jint width,jint height,jint redQuad) {
    try {
        if(geteuid()!=0)throw std::runtime_error("Root worker required");
        // Hard kernel timeout covers blocked vendor code, not just Java waits.
        signal(SIGALRM,SIG_DFL);alarm(180);
        {
        vivo_nn::Session session;session.init();auto mapping=vivo_nn::calibrate(session);
        if(input && output){
            const char* a=env->GetStringUTFChars(input,nullptr);if(!a)return;std::string ip(a);env->ReleaseStringUTFChars(input,a);
            const char* b=env->GetStringUTFChars(output,nullptr);if(!b)return;std::string op(b);env->ReleaseStringUTFChars(output,b);
            auto bytes=vivo_nn::read(ip);
            if(width<8||height<8||static_cast<int64_t>(width)*height>16000000||bytes.size()!=static_cast<uint64_t>(width)*height*4)
                throw std::runtime_error("Invalid prepared RAW length");
            for(size_t i=0;i<bytes.size();i+=4){float v;std::memcpy(&v,bytes.data()+i,4);if(!std::isfinite(v)||v<0||v>16)throw std::runtime_error("Invalid prepared RAW sample");}
            auto result=vivo_nn::reconstruct(session,mapping,reinterpret_cast<const float*>(bytes.data()),width,height,redQuad);
            std::ofstream f(op,std::ios::binary|std::ios::trunc);if(!f)throw std::runtime_error("Cannot open result");
            f.write(reinterpret_cast<const char*>(result.data()),result.size()*sizeof(float));f.close();
            if(!f)throw std::runtime_error("Result write failed");
            vivo_nn::log("NEURAL FRAME COMPLETE");
        }
        } // Free vendor handles while the hard timeout is still armed.
        alarm(0);vivo_nn::log("NEURAL JOB OK");
    } catch(const std::exception& e){
        vivo_nn::log(std::string("STOP: ")+e.what());
        jclass exception=env->FindClass("java/lang/IllegalStateException");if(exception)env->ThrowNew(exception,e.what());
    }
}
