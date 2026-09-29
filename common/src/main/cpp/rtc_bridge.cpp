#include <jni.h>
#include <rtc/rtc.h>
#include <android/log.h>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include "video_buffer.hpp"

extern "C" int wheelplaySetVideoPacketizer(int, const rtcPacketizerInit*, bool);
extern "C" int wheelplaySendVideoParts(int, const uint8_t*, size_t, const uint8_t*, size_t);

namespace {
struct Peer {
    JavaVM* vm = nullptr;
    jobject owner = nullptr;
    jmethodID event = nullptr;
    int pc = -1, track = -1;

    void emit(const char* kind, const char* value = "") {
        JNIEnv* env = nullptr;
        bool attached = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED;
        if (attached && vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        auto k = env->NewStringUTF(kind), v = env->NewStringUTF(value);
        env->CallVoidMethod(owner, event, k, v);
        // Java only queues these events; no native deletion is allowed on this callback.
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(k); env->DeleteLocalRef(v);
        if (attached) vm->DetachCurrentThread();
    }
    void dispose(JNIEnv* env) {
        // rtcDelete* waits for callbacks before we release their user pointer/global reference.
        if (track >= 0) { rtcDeleteTrack(track); track = -1; }
        if (pc >= 0) { rtcClosePeerConnection(pc); rtcDeletePeerConnection(pc); pc = -1; }
        if (owner) { env->DeleteGlobalRef(owner); owner = nullptr; }
    }
};
void check(int result) { if (result < 0) throw std::runtime_error("WebRTC operation failed: " + std::to_string(result)); }
void fail(JNIEnv* env, const std::exception& e) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
}
Peer* peer(jlong handle) { return reinterpret_cast<Peer*>(handle); }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_web_RtcNative_create(JNIEnv* env, jobject owner, jstring profile, jboolean hevc) {
    auto p = std::make_unique<Peer>();
    try {
        static std::once_flag logging;
        std::call_once(logging, [] { rtcInitLogger(RTC_LOG_WARNING, [](rtcLogLevel, const char* message) {
            __android_log_write(ANDROID_LOG_WARN, "WheelPlayRtc", message);
        }); });
        env->GetJavaVM(&p->vm);
        p->owner = env->NewGlobalRef(owner);
        auto cls = env->GetObjectClass(owner);
        p->event = env->GetMethodID(cls, "onEvent", "(Ljava/lang/String;Ljava/lang/String;)V");
        env->DeleteLocalRef(cls);
        rtcConfiguration config{};
        config.disableAutoNegotiation = true;
        config.forceMediaTransport = true;
        config.mtu = 1200;
        p->pc = rtcCreatePeerConnection(&config); check(p->pc);
        rtcSetUserPointer(p->pc, p.get());
        check(rtcSetGatheringStateChangeCallback(p->pc, [](int id, rtcGatheringState state, void* ptr) {
            if (state != RTC_GATHERING_COMPLETE) return;
            int size = rtcGetLocalDescription(id, nullptr, 0);
            if (size <= 0 || size > 32768) { static_cast<Peer*>(ptr)->emit("failed"); return; }
            std::vector<char> sdp(size);
            if (rtcGetLocalDescription(id, sdp.data(), size) > 0)
                static_cast<Peer*>(ptr)->emit("offer", sdp.data());
        }));
        check(rtcSetStateChangeCallback(p->pc, [](int, rtcState state, void* ptr) {
            if (state == RTC_CONNECTED) static_cast<Peer*>(ptr)->emit("connected");
            if (state == RTC_FAILED || state == RTC_DISCONNECTED) static_cast<Peer*>(ptr)->emit("failed");
        }));
        const char* format = env->GetStringUTFChars(profile, nullptr);
        rtcTrackInit track{};
        track.direction = RTC_DIRECTION_SENDONLY; track.codec = hevc ? RTC_CODEC_H265 : RTC_CODEC_H264;
        track.payloadType = 96; track.ssrc = 42;
        track.mid = "video"; track.name = "wheelplay"; track.msid = "wheelplay";
        track.trackId = "screen"; track.profile = format;
        p->track = rtcAddTrackEx(p->pc, &track);
        env->ReleaseStringUTFChars(profile, format); check(p->track);
        rtcSetUserPointer(p->track, p.get());
        rtcPacketizerInit packetizer{};
        packetizer.ssrc = 42; packetizer.cname = "wheelplay"; packetizer.payloadType = 96;
        packetizer.clockRate = 90000; packetizer.nalSeparator = RTC_NAL_SEPARATOR_START_SEQUENCE;
        packetizer.maxFragmentSize = 1100;
        check(wheelplaySetVideoPacketizer(p->track, &packetizer, hevc));
        check(rtcChainRtcpSrReporter(p->track));
        check(rtcChainRtcpNackResponder(p->track, 512));
        check(rtcChainPliHandler(p->track, [](int, void* ptr) { static_cast<Peer*>(ptr)->emit("keyframe"); }));
        return reinterpret_cast<jlong>(p.release());
    } catch (const std::exception& e) { p->dispose(env); fail(env, e); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_web_RtcNative_begin(JNIEnv* env, jobject, jlong handle) {
    try { check(rtcSetLocalDescription(peer(handle)->pc, "offer")); } catch (const std::exception& e) { fail(env, e); }
}
extern "C" JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_web_RtcNative_answer(JNIEnv* env, jobject, jlong handle, jstring sdp) {
    const char* text = env->GetStringUTFChars(sdp, nullptr);
    int result = rtcSetRemoteDescription(peer(handle)->pc, text, "answer");
    env->ReleaseStringUTFChars(sdp, text);
    try { check(result); } catch (const std::exception& e) { fail(env, e); }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_shilapi_xcertplay_web_RtcNative_send(JNIEnv* env, jobject, jlong handle, jbyteArray frame, jbyteArray parameters, jlong timestamp) {
    auto p = peer(handle);
    if (!rtcIsOpen(p->track)) return false;
    auto data = env->GetByteArrayElements(frame, nullptr);
    if (!data) return false;
    jbyte* prefix = parameters ? env->GetByteArrayElements(parameters, nullptr) : nullptr;
    if (parameters && !prefix) { env->ReleaseByteArrayElements(frame, data, JNI_ABORT); return false; }
    int result = rtcSetTrackRtpTimestamp(p->track, static_cast<uint32_t>(timestamp));
    if (result >= 0) result = wheelplaySendVideoParts(p->track, reinterpret_cast<uint8_t*>(data),
        env->GetArrayLength(frame), reinterpret_cast<uint8_t*>(prefix), parameters ? env->GetArrayLength(parameters) : 0);
    if (prefix) env->ReleaseByteArrayElements(parameters, prefix, JNI_ABORT);
    env->ReleaseByteArrayElements(frame, data, JNI_ABORT);
    return result >= 0;
}
extern "C" JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_web_RtcNative_destroy(JNIEnv* env, jobject, jlong handle) {
    std::unique_ptr<Peer> p(peer(handle));
    if (p) p->dispose(env);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shilapi_xcertplay_web_RtcNative_sendNative(JNIEnv* env, jobject, jlong handle, jlong frameHandle, jbyteArray parameters, jlong timestamp) {
    auto p = peer(handle);
    if (!rtcIsOpen(p->track)) return false;
    auto frame = reinterpret_cast<wheelplay::VideoBuffer*>(frameHandle);
    try {
        std::vector<uint8_t> parameterBytes;
        if (parameters && env->GetArrayLength(parameters) > 0) {
            size_t prefix = env->GetArrayLength(parameters);
            parameterBytes.resize(prefix);
            env->GetByteArrayRegion(parameters,0,prefix,reinterpret_cast<jbyte*>(parameterBytes.data()));
            if (env->ExceptionCheck()) return false;
        }
        int result = rtcSetTrackRtpTimestamp(p->track, static_cast<uint32_t>(timestamp));
        if (result >= 0) result = wheelplaySendVideoParts(p->track, frame->bytes.data(), frame->bytes.size(),
            parameterBytes.data(), parameterBytes.size());
        return result >= 0;
    } catch (const std::exception& e) { fail(env,e); return false; }
}

extern "C" int wheelplaySrtpStats(int, int64_t*);
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_shilapi_xcertplay_web_RtcNative_srtpStats(JNIEnv* env, jobject, jlong handle) {
    int64_t counters[6]{};
    if (wheelplaySrtpStats(peer(handle)->track, counters) < 0) return nullptr;
    auto result = env->NewLongArray(6);
    jlong values[6];
    for (int i=0;i<6;++i) values[i] = counters[i];
    if (result) env->SetLongArrayRegion(result,0,6,values);
    return result;
}
