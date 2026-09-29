#pragma once
#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>

namespace wheelplay {
struct SrtpMetrics {
    std::atomic<int64_t> protectedPackets{0}, retransmissions{0}, protectNs{0}, sendNs{0}, bytes{0}, failures{0};
};
struct SrtpPacketState {
    const void* owner = nullptr; // Copies of Message must never share this encryption state.
    std::mutex mutex;
    enum class Phase { Plain, Protected, Failed } phase = Phase::Plain;
    std::shared_ptr<const int> context;
    std::shared_ptr<SrtpMetrics> metrics;
};
using SrtpClock = std::chrono::steady_clock;
inline int64_t elapsedNs(SrtpClock::time_point start) {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(SrtpClock::now()-start).count();
}
// Called only for dedicated video packets, after all plaintext media handlers have finished.
// NACK stores a reference but accesses no bytes after publication. Subsequent sends are immutable.
// The packet lock also rejects concurrent reuse under a different transport/key context.
template<class Message, class Protect, class Send>
bool sendOwnedSrtp(const std::shared_ptr<Message>& packet, const std::shared_ptr<const int>& context,
                   size_t trailer, Protect protect, Send send) {
    auto& state = *packet->wheelplaySrtp;
    std::lock_guard<std::mutex> lock(state.mutex);
    auto& stats = *state.metrics;
    if (state.owner != packet.get()) { ++stats.failures; return false; }
    if (state.phase == SrtpPacketState::Phase::Failed) return false;
    if (state.phase == SrtpPacketState::Phase::Protected) {
        if (state.context != context) { ++stats.failures; return false; }
        ++stats.retransmissions;
    } else {
        const size_t plainSize = packet->size();
        if (packet->capacity() - plainSize < trailer) throw std::logic_error("SRTP tailroom missing");
        // Poison before any potentially failing mutation; never transmit partially encrypted data.
        state.phase = SrtpPacketState::Phase::Failed;
        packet->resize(plainSize + trailer);
        int length = static_cast<int>(plainSize);
        auto start = SrtpClock::now();
        int result = protect(packet->data(), &length);
        stats.protectNs += elapsedNs(start);
        if (result || length < int(plainSize) || size_t(length) > packet->size()) {
            ++stats.failures; return false;
        }
        packet->resize(length);
        state.context = context;
        state.phase = SrtpPacketState::Phase::Protected;
        ++stats.protectedPackets;
        stats.bytes += plainSize;
    }
    auto start = SrtpClock::now();
    bool result = send(packet);
    stats.sendNs += elapsedNs(start);
    if (!result) ++stats.failures;
    return result;
}
}
