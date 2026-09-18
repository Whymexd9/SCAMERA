#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <cstdio>
#include <vector>
#include "engine.h"
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_particlesdevs_photoncamera_processing_opengl_postpipeline_RawTherapeeDenoise_nativeCurve(
    JNIEnv* env,jclass,jdoubleArray points) {
    if(!points)return nullptr;
    int n=env->GetArrayLength(points);
    if(n<9||n>257) { env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),"Invalid curve length"); return nullptr; }
    std::vector<double> values(n);env->GetDoubleArrayRegion(points,0,n,values.data());
    float lut[501];char error[256]={};
    int status=rt_curve(values.data(),n,lut,error,sizeof(error));
    if(!status) { env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),error);return nullptr; }
    if(status==2)return nullptr;
    jfloatArray out=env->NewFloatArray(501);
    if(out)env->SetFloatArrayRegion(out,0,501,lut);return out;
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_particlesdevs_photoncamera_processing_opengl_postpipeline_RawTherapeeDenoise_nativeRun(
    JNIEnv* env,jclass,jobject buffer,jint w,jint h,jfloatArray parameters,jfloatArray matrix,
    jfloatArray lumaCurve,jfloatArray chromaCurve) {
    auto fail=[&](const char* s){return env->NewStringUTF(s);};
    auto* pixels=static_cast<float*>(env->GetDirectBufferAddress(buffer));
    if(w<32||h<32||w>16384||h>16384||!pixels||env->GetDirectBufferCapacity(buffer)<jlong(w)*h*16
       ||!parameters||env->GetArrayLength(parameters)!=14||!matrix||env->GetArrayLength(matrix)!=9)
        return fail("Invalid RAW denoise input");
    float v[14],m[9],inv[9],lc[501],cc[501];
    env->GetFloatArrayRegion(parameters,0,14,v);env->GetFloatArrayRegion(matrix,0,9,m);
    for(float x:m) if(!std::isfinite(x)) return fail("Non-finite color transform");
    const float det=m[0]*(m[4]*m[8]-m[5]*m[7])-m[1]*(m[3]*m[8]-m[5]*m[6])+m[2]*(m[3]*m[7]-m[4]*m[6]);
    if(!std::isfinite(det)||std::abs(det)<1e-8f) return fail("Singular color transform");
    inv[0]=(m[4]*m[8]-m[5]*m[7])/det;inv[1]=(m[2]*m[7]-m[1]*m[8])/det;inv[2]=(m[1]*m[5]-m[2]*m[4])/det;
    inv[3]=(m[5]*m[6]-m[3]*m[8])/det;inv[4]=(m[0]*m[8]-m[2]*m[6])/det;inv[5]=(m[2]*m[3]-m[0]*m[5])/det;
    inv[6]=(m[3]*m[7]-m[4]*m[6])/det;inv[7]=(m[1]*m[6]-m[0]*m[7])/det;inv[8]=(m[0]*m[4]-m[1]*m[3])/det;
    if(lumaCurve) {
        if(env->GetArrayLength(lumaCurve)!=501)return fail("Invalid luma curve");
        env->GetFloatArrayRegion(lumaCurve,0,501,lc);
    }
    if(chromaCurve) {
        if(env->GetArrayLength(chromaCurve)!=501)return fail("Invalid chroma curve");
        env->GetFloatArrayRegion(chromaCurve,0,501,cc);
    }
    if(env->ExceptionCheck())return nullptr;
    for(size_t i=0;i<size_t(w)*h;i++) {
        const float r=pixels[4*i],g=pixels[4*i+1],b=pixels[4*i+2];
        for(int c=0;c<3;c++)pixels[4*i+c]=m[c*3]*r+m[c*3+1]*g+m[c*3+2]*b;
    }
    char error[256]={};
    if(!rt_denoise(pixels,w,h,v,14,lumaCurve?lc:nullptr,chromaCurve?cc:nullptr,error,sizeof(error))) return fail(error);
    for(size_t i=0;i<size_t(w)*h;i++) {
        const float r=pixels[4*i],g=pixels[4*i+1],b=pixels[4*i+2];
        for(int c=0;c<3;c++) {
            pixels[4*i+c]=inv[c*3]*r+inv[c*3+1]*g+inv[c*3+2]*b;
            if(!std::isfinite(pixels[4*i+c]))return fail("Invalid inverse color output");
        }
    }
    return nullptr;
}
