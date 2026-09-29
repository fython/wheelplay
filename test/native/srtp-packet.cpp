#include "srtp_packet.hpp"
#include <srtp.h>
#include <array>
#include <cassert>
#include <cstring>
#include <iostream>
#include <thread>
#include <vector>
#ifdef __ANDROID__
#include <sys/auxv.h>
#if defined(__aarch64__)
#include <asm/hwcap.h>
#endif
#endif
struct Packet : std::vector<uint8_t> { std::shared_ptr<wheelplay::SrtpPacketState> wheelplaySrtp; };
struct Session {
    srtp_t handle = nullptr;
    std::array<uint8_t,30> key{};
    Session() {
        for(size_t i=0;i<key.size();++i) key[i]=uint8_t(i+1);
        srtp_policy_t policy{};
        srtp_crypto_policy_set_aes_cm_128_hmac_sha1_80(&policy.rtp);
        srtp_crypto_policy_set_aes_cm_128_hmac_sha1_80(&policy.rtcp);
        policy.ssrc.type=ssrc_specific; policy.ssrc.value=42; policy.key=key.data();
        policy.window_size=1024;
        assert(srtp_create(&handle,&policy)==srtp_err_status_ok);
    }
    ~Session(){srtp_dealloc(handle);}
};
static auto packet(uint16_t sequence, std::shared_ptr<wheelplay::SrtpMetrics> stats) {
    auto p=std::make_shared<Packet>();p->reserve(1112+SRTP_MAX_TRAILER_LEN);p->resize(1112,0x55);
    (*p)[0]=0x80;(*p)[1]=96;(*p)[2]=sequence>>8;(*p)[3]=sequence;
    (*p)[8]=(*p)[9]=(*p)[10]=0;(*p)[11]=42;
    p->wheelplaySrtp=std::make_shared<wheelplay::SrtpPacketState>();p->wheelplaySrtp->metrics=stats;p->wheelplaySrtp->owner=p.get();
    return p;
}
static void checks() {
    Session sender,receiver;
    auto context=std::make_shared<const int>(0);
    auto stats=std::make_shared<wheelplay::SrtpMetrics>();
    std::vector<std::shared_ptr<Packet>> cached;
    int protectedCount=0;
    auto protect=[&](void* b,int* n){++protectedCount;return int(srtp_protect(sender.handle,b,n));};
    for(uint16_t seq:{uint16_t(65534),uint16_t(65535),uint16_t(0),uint16_t(1)}) {
        auto p=packet(seq,stats);auto address=p->data();
        assert(wheelplay::sendOwnedSrtp(p,context,SRTP_MAX_TRAILER_LEN,protect,[](auto){return true;}));
        assert(p->data()==address);assert(p->size()==1122);cached.push_back(p);
    }
    assert(protectedCount==4);
    // First packet was lost. Accept the following packets across sequence/ROC wrap first.
    for(int i:{1,2,3}) {auto received=std::vector<uint8_t>(*cached[i]);int size=received.size();
        assert(srtp_unprotect(receiver.handle,received.data(),&size)==srtp_err_status_ok);assert(size==1112);}
    auto copied=std::make_shared<Packet>(*cached[0]);
    assert(!wheelplay::sendOwnedSrtp(copied,context,SRTP_MAX_TRAILER_LEN,protect,[](auto){assert(false);return true;}));
    copied.reset();
    auto ciphertext=std::vector<uint8_t>(*cached[0]);
    assert(!wheelplay::sendOwnedSrtp(cached[0],context,SRTP_MAX_TRAILER_LEN,protect,[](auto){return false;}));
    assert(wheelplay::sendOwnedSrtp(cached[0],context,SRTP_MAX_TRAILER_LEN,protect,[](auto){return true;}));
    assert(protectedCount==4 && std::vector<uint8_t>(*cached[0])==ciphertext);
    auto corrupt=ciphertext;corrupt[20]^=1;int size=corrupt.size();
    assert(srtp_unprotect(receiver.handle,corrupt.data(),&size)==srtp_err_status_auth_fail);
    size=ciphertext.size();assert(srtp_unprotect(receiver.handle,ciphertext.data(),&size)==srtp_err_status_ok);
    assert(size==1112 && ciphertext[20]==0x55);
    auto duplicate=std::vector<uint8_t>(*cached[0]);size=duplicate.size();
    assert(srtp_unprotect(receiver.handle,duplicate.data(),&size)==srtp_err_status_replay_fail);
    assert(!wheelplay::sendOwnedSrtp(cached[0],std::make_shared<const int>(1),SRTP_MAX_TRAILER_LEN,protect,[](auto){assert(false);return true;}));
    std::vector<std::thread> workers;
    for(int i=0;i<8;++i) workers.emplace_back([&]{assert(wheelplay::sendOwnedSrtp(cached[0],context,SRTP_MAX_TRAILER_LEN,protect,[](auto){return true;}));});
    for(auto& t:workers)t.join();assert(protectedCount==4);
    auto failed=packet(2,stats);
    assert(!wheelplay::sendOwnedSrtp(failed,context,SRTP_MAX_TRAILER_LEN,[](void* p,int*){static_cast<uint8_t*>(p)[20]=0;return -1;},[](auto){assert(false);return true;}));
    assert(!wheelplay::sendOwnedSrtp(failed,context,SRTP_MAX_TRAILER_LEN,protect,[](auto){assert(false);return true;}));
    std::weak_ptr<Packet> released=cached[0];cached.clear();assert(released.expired());
    std::cout<<"SRTP roundtrip, loss/retry, ROC wrap, tampering, replay rejection, context isolation, concurrent retry and release passed\n";
}
static void benchmark() {
    for(int round=0;round<3;++round) {
        Session sender;auto stats=std::make_shared<wheelplay::SrtpMetrics>();auto context=std::make_shared<const int>(0);
        for(int i=0;i<10000;++i) {
            auto p=packet(i,stats);
            assert(wheelplay::sendOwnedSrtp(p,context,SRTP_MAX_TRAILER_LEN,
                [&](void* b,int* n){return int(srtp_protect(sender.handle,b,n));},[](auto){return true;}));
        }
        stats->protectNs=0;
        auto start=wheelplay::SrtpClock::now();
        for(int i=10000;i<15000;++i) {
            auto p=packet(i,stats);
            assert(wheelplay::sendOwnedSrtp(p,context,SRTP_MAX_TRAILER_LEN,
                [&](void* b,int* n){return int(srtp_protect(sender.handle,b,n));},[](auto){return true;}));
        }
        std::cout<<"round="<<round<<" protectUs="<<stats->protectNs.load()/5000000.0
                 <<" totalUs="<<wheelplay::elapsedNs(start)/5000000.0<<std::endl;
    }
}
int main() {
    assert(srtp_init()==srtp_err_status_ok);
#if defined(__ANDROID__) && defined(__aarch64__)
    std::cout<<"armAES="<<bool(getauxval(AT_HWCAP)&HWCAP_AES)<<std::endl;
#endif
    checks();benchmark();srtp_shutdown();
}
