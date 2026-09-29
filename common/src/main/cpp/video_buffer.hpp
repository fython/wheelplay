#pragma once
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

namespace wheelplay {
// Only returned buffers are cached; live frames remain owned by their Kotlin reference count.
struct VideoPool {
    std::mutex mutex;
    std::vector<std::vector<uint8_t>> buffers;
    size_t cached = 0;
    VideoPool() { buffers.reserve(8); }
    std::vector<uint8_t> acquire(size_t size) {
        std::lock_guard<std::mutex> lock(mutex);
        for (auto it = buffers.begin(); it != buffers.end(); ++it) {
            if (it->capacity() >= size) {
                auto result = std::move(*it); cached -= result.capacity(); buffers.erase(it);
                result.resize(size); return result;
            }
        }
        return std::vector<uint8_t>(size);
    }
    void recycle(std::vector<uint8_t> bytes) {
        std::lock_guard<std::mutex> lock(mutex);
        if (buffers.size() < 8 && bytes.capacity() <= 4 * 1024 * 1024 - cached) {
            cached += bytes.capacity(); buffers.push_back(std::move(bytes));
        }
    }
};
struct VideoBuffer {
    std::shared_ptr<VideoPool> pool;
    std::vector<uint8_t> bytes;
    bool avcKeyframe = false, hevcKeyframe = false;
    ~VideoBuffer() { pool->recycle(std::move(bytes)); }
};
// Validates the entire record before rewriting it, matching the JVM fallback's behavior.
inline void annexB(VideoBuffer& frame) {
    auto& b = frame.bytes;
    auto prefix = [&](size_t i) -> size_t {
        if (i + 3 > b.size() || b[i] || b[i+1]) return 0;
        if (b[i+2] == 1) return 3;
        return i + 4 <= b.size() && !b[i+2] && b[i+3] == 1 ? 4 : 0;
    };
    auto length = [&](size_t i) { return (uint32_t(b[i]) << 24) | (uint32_t(b[i+1]) << 16) | (uint32_t(b[i+2]) << 8) | b[i+3]; };
    if (!prefix(0)) {
        size_t i = 0;
        while (i + 4 <= b.size()) {
            auto n = length(i); i += 4;
            if (!n || n > b.size() - i) return;
            i += n;
        }
        if (i != b.size()) return;
        for (i = 0; i + 4 <= b.size();) {
            auto n = length(i); b[i] = b[i+1] = b[i+2] = 0; b[i+3] = 1; i += 4 + n;
        }
    }
    for (size_t i = 0; i + 3 < b.size();) {
        auto p = prefix(i);
        if (!p) { ++i; continue; }
        if (i + p < b.size()) {
            frame.avcKeyframe |= (b[i+p] & 31) == 5;
            auto type = (b[i+p] >> 1) & 63;
            frame.hevcKeyframe |= i + p + 1 < b.size() && type >= 16 && type <= 21;
        }
        i += p;
    }
}
}
