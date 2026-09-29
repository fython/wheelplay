#pragma once
#include <algorithm>
#include <array>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace wheelplay {
struct VideoView { const uint8_t* data; size_t size; };

// Views borrow the access unit only until packetization returns. No payload arrays are built.
inline void appendNals(VideoView input, bool hevc, std::vector<VideoView>& result) {
    if (!input.size) return;
    if (!input.data) throw std::invalid_argument("Missing video data");
    auto prefix = [&](size_t i) -> size_t {
        if (i + 3 > input.size || input.data[i] || input.data[i+1]) return 0;
        if (input.data[i+2] == 1) return 3;
        return i + 4 <= input.size && !input.data[i+2] && input.data[i+3] == 1 ? 4 : 0;
    };
    size_t i = 0;
    while (i < input.size && !prefix(i)) {
        if (input.data[i++]) throw std::invalid_argument("Expected Annex B video");
    }
    if (i == input.size) throw std::invalid_argument("Missing NAL start code");
    while (i < input.size) {
        const size_t start = i + prefix(i);
        i = start;
        while (i < input.size && !prefix(i)) ++i;
        const size_t size = i - start;
        if (size < (hevc ? 2u : 1u)) throw std::invalid_argument("Truncated NAL header");
        result.push_back({input.data + start, size});
    }
}

// Emits a small FU header plus a borrowed payload directly into the final RTP packet.
// Parameter sets and the frame share one timestamp; only the final packet has the marker bit.
template<class Emit>
void packetizeVideo(VideoView frame, VideoView parameters, bool hevc, size_t maxPayload, Emit emit) {
    if (!frame.size || maxPayload < 4) throw std::invalid_argument("Invalid video packetization input");
    std::vector<VideoView> nals;
    appendNals(parameters, hevc, nals);
    appendNals(frame, hevc, nals); // Validate the entire access unit before emitting any packet.
    for (size_t index = 0; index < nals.size(); ++index) {
        const auto nal = nals[index];
        const bool lastNal = index + 1 == nals.size();
        if (nal.size <= maxPayload) { emit(VideoView{nullptr, 0}, nal, lastNal); continue; }
        const size_t nalHeader = hevc ? 2 : 1;
        const size_t fuHeader = hevc ? 3 : 2;
        const size_t capacity = maxPayload - fuHeader;
        const uint8_t type = hevc ? (nal.data[0] >> 1) & 63 : nal.data[0] & 31;
        for (size_t offset = nalHeader; offset < nal.size;) {
            const size_t size = std::min(capacity, nal.size - offset);
            const bool start = offset == nalHeader, end = offset + size == nal.size;
            std::array<uint8_t, 3> header{};
            header[0] = hevc ? (nal.data[0] & 0x81) | (49 << 1) : (nal.data[0] & 0xe0) | 28;
            if (hevc) header[1] = nal.data[1]; // Preserve layer ID and temporal ID.
            header[fuHeader - 1] = type | (start ? 0x80 : 0) | (end ? 0x40 : 0);
            emit(VideoView{header.data(), fuHeader}, VideoView{nal.data + offset, size}, lastNal && end);
            offset += size;
        }
    }
}
}
