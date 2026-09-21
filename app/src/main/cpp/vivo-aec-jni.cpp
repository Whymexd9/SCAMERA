#include <jni.h>
#include "vivo-aec-wire.h"
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_particlesdevs_photoncamera_capture_VivoStockAe_nativeSolve(JNIEnv* env,jclass,jbyteArray input) {
    try {
        if(!input)throw std::invalid_argument("Missing AE snapshot");
        const jsize size=env->GetArrayLength(input);
        if(size<=0 || size>1024*1024)throw std::invalid_argument("AE snapshot size");
        std::vector<uint8_t> bytes(static_cast<size_t>(size));
        env->GetByteArrayRegion(input,0,size,reinterpret_cast<jbyte*>(bytes.data()));
        if(env->ExceptionCheck())return nullptr;
        auto output=vivo_aec::encodeSolverPlan(vivo_aec::solveExposures(vivo_aec::decodeSolverSnapshot(bytes.data(),bytes.size())));
        auto result=env->NewByteArray(output.size());
        if(result)env->SetByteArrayRegion(result,0,output.size(),reinterpret_cast<const jbyte*>(output.data()));
        return result;
    }catch(const std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),error.what());return nullptr;
    }
}
