#include <jni.h>
#include <mbedtls/chachapoly.h>
#include <mbedtls/platform_util.h>
#include <memory>
#include <stdexcept>
#include "video_buffer.hpp"

namespace {
struct Decoder {
    mbedtls_chachapoly_context cipher;
    std::shared_ptr<wheelplay::VideoPool> pool = std::make_shared<wheelplay::VideoPool>();
    std::vector<uint8_t> encrypted;
    Decoder() { mbedtls_chachapoly_init(&cipher); }
    ~Decoder() { mbedtls_chachapoly_free(&cipher); }
};
void error(JNIEnv* env, const char* type, const char* message) { env->ThrowNew(env->FindClass(type), message); }
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_web_NativeVideoDecoder_create(JNIEnv* env, jobject, jbyteArray key) {
    if (!key || env->GetArrayLength(key) != 32) { error(env,"java/lang/IllegalArgumentException","Invalid video key size"); return 0; }
    try {
        auto decoder = std::make_unique<Decoder>();
        uint8_t bytes[32]; env->GetByteArrayRegion(key,0,32,reinterpret_cast<jbyte*>(bytes));
        int result = mbedtls_chachapoly_setkey(&decoder->cipher, bytes); mbedtls_platform_zeroize(bytes,sizeof(bytes));
        if (result) throw std::runtime_error("Video cipher initialization failed");
        return reinterpret_cast<jlong>(decoder.release());
    } catch (const std::exception& e) { error(env,"java/lang/IllegalStateException",e.what()); return 0; }
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_web_NativeVideoDecoder_decrypt(JNIEnv* env, jobject, jlong handle, jlong counter, jbyteArray header, jbyteArray body, jint size) {
    if (!handle || !header || env->GetArrayLength(header) != 128 || !body || size < 16 || size > 8*1024*1024 || size > env->GetArrayLength(body)) {
        error(env,"java/lang/IllegalArgumentException","Invalid encrypted video frame"); return 0;
    }
    try {
        auto decoder = reinterpret_cast<Decoder*>(handle);
        decoder->encrypted.resize(size);
        env->GetByteArrayRegion(body,0,size,reinterpret_cast<jbyte*>(decoder->encrypted.data()));
        uint8_t aad[128], nonce[12]{};
        env->GetByteArrayRegion(header,0,128,reinterpret_cast<jbyte*>(aad));
        for (int i=0; i<8; ++i) nonce[4+i] = (uint64_t(counter) >> (8*i)) & 255;
        auto frame = std::make_unique<wheelplay::VideoBuffer>();
        frame->pool = decoder->pool; frame->bytes = decoder->pool->acquire(size-16);
        int result = mbedtls_chachapoly_auth_decrypt(&decoder->cipher,size-16,nonce,aad,128,
            decoder->encrypted.data()+size-16,decoder->encrypted.data(),frame->bytes.data());
        if (result) {
            mbedtls_platform_zeroize(frame->bytes.data(),frame->bytes.size());
            error(env,"javax/crypto/AEADBadTagException","Video authentication failed"); return 0;
        }
        wheelplay::annexB(*frame);
        return reinterpret_cast<jlong>(frame.release());
    } catch (const std::exception& e) { error(env,"java/lang/IllegalStateException",e.what()); return 0; }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shilapi_xcertplay_web_NativeVideoDecoder_copy(JNIEnv* env,jobject,jlong handle) {
    auto frame = reinterpret_cast<wheelplay::VideoBuffer*>(handle);
    auto result = env->NewByteArray(frame->bytes.size());
    if (result) env->SetByteArrayRegion(result,0,frame->bytes.size(),reinterpret_cast<jbyte*>(frame->bytes.data()));
    return result;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_shilapi_xcertplay_web_NativeVideoDecoder_keyframe(JNIEnv*,jobject,jlong handle,jboolean hevc) {
    auto frame = reinterpret_cast<wheelplay::VideoBuffer*>(handle); return hevc ? frame->hevcKeyframe : frame->avcKeyframe;
}
extern "C" JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_web_NativeVideoDecoder_release(JNIEnv*,jobject,jlong handle) { delete reinterpret_cast<wheelplay::VideoBuffer*>(handle); }
extern "C" JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_web_NativeVideoDecoder_destroy(JNIEnv*,jobject,jlong handle) { delete reinterpret_cast<Decoder*>(handle); }

extern "C" JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_web_NativeVideoDecoder_importFrame(JNIEnv* env,jobject,jlong handle,jbyteArray bytes,jint size) {
    if (!handle || !bytes || size < 0 || size > 8*1024*1024 || size > env->GetArrayLength(bytes)) {
        error(env,"java/lang/IllegalArgumentException","Invalid decoded frame"); return 0;
    }
    try {
        auto decoder = reinterpret_cast<Decoder*>(handle);
        auto frame = std::make_unique<wheelplay::VideoBuffer>();
        frame->pool = decoder->pool; frame->bytes = decoder->pool->acquire(size);
        env->GetByteArrayRegion(bytes,0,size,reinterpret_cast<jbyte*>(frame->bytes.data()));
        wheelplay::annexB(*frame);
        return reinterpret_cast<jlong>(frame.release());
    } catch (const std::exception& e) { error(env,"java/lang/IllegalStateException",e.what()); return 0; }
}
