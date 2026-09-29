#include "video_packetizer.hpp"
#include <cassert>
#include <cstring>
#include <iostream>
using Bytes = std::vector<uint8_t>;
struct Packet { Bytes data; bool marker; };
static Bytes annex(const std::vector<Bytes>& nals) {
    Bytes result;
    for (size_t i = 0; i < nals.size(); ++i) {
        if (i % 2 == 0) result.push_back(0);
        result.insert(result.end(), {0,0,1});
        result.insert(result.end(), nals[i].begin(), nals[i].end());
    }
    return result;
}
static std::vector<Packet> encode(const Bytes& frame, const Bytes& config, bool hevc, size_t limit) {
    std::vector<Packet> result;
    wheelplay::packetizeVideo({frame.data(),frame.size()}, {config.data(),config.size()}, hevc, limit,
        [&](wheelplay::VideoView header, wheelplay::VideoView payload, bool marker) {
            // Each payload references one of the original buffers, never an intermediate vector.
            auto in = [&](const Bytes& b) { auto p = uintptr_t(payload.data), start = uintptr_t(b.data());
                return p >= start && p + payload.size <= start + b.size(); };
            assert(in(frame) || in(config));
            Bytes bytes(header.size + payload.size);
            if (header.size) std::memcpy(bytes.data(),header.data,header.size);
            std::memcpy(bytes.data()+header.size,payload.data,payload.size);
            assert(bytes.size() <= limit);
            result.push_back({std::move(bytes),marker});
        });
    return result;
}
static std::vector<Bytes> reconstruct(const std::vector<Packet>& packets, bool hevc) {
    std::vector<Bytes> result;
    bool open = false;
    for (size_t i = 0; i < packets.size(); ++i) {
        const auto& p = packets[i]; const auto& b = p.data;
        assert(p.marker == (i + 1 == packets.size()));
        auto type = hevc ? (b[0] >> 1) & 63 : b[0] & 31;
        if (type != (hevc ? 49 : 28)) { assert(!open); result.push_back(b); continue; }
        size_t header = hevc ? 3 : 2;
        uint8_t flags = b[header-1];
        if (flags & 0x80) {
            assert(!open); open = true;
            if (hevc) result.push_back({uint8_t((b[0]&0x81)|((flags&63)<<1)),b[1]});
            else result.push_back({uint8_t((b[0]&0xe0)|(flags&31))});
        }
        assert(open);
        result.back().insert(result.back().end(),b.begin()+header,b.end());
        if (flags & 0x40) open = false;
    }
    assert(!open); return result;
}
int main() {
    for (bool hevc : {false,true}) {
        std::vector<Bytes> params = hevc ? std::vector<Bytes>{{0x40,1,9},{0x42,1,8},{0x44,1,7}}
                                       : std::vector<Bytes>{{0x67,9},{0x68,8}};
        for (size_t size : {size_t(2),size_t(1099),size_t(1100),size_t(1101),size_t(2196),size_t(65536)}) {
            Bytes nal(size,0x55);
            nal[0] = hevc ? 0x27 : 0x65; // HEVC IRAP, non-zero layer ID bits.
            if (hevc) nal[1] = 0xdb;
            std::vector<Bytes> nals{nal, hevc ? Bytes{0x02,1,7} : Bytes{0x41,7}};
            auto expected = params; expected.insert(expected.end(),nals.begin(),nals.end());
            auto frame = annex(nals), config = annex(params);
            auto packets = encode(frame,config,hevc,1100);
            // Final RTP payloads remain valid after the source frame is released/reused.
            std::fill(frame.begin(),frame.end(),0); std::fill(config.begin(),config.end(),0);
            assert(reconstruct(packets,hevc) == expected);
            assert(reconstruct(encode(annex(nals),{},hevc,1100),hevc) == nals);
        }
        for (Bytes bad : {Bytes{},Bytes{1,2,3},Bytes{0,0,1},Bytes{0,0,1,0x65,2,0,0,1}}) {
            size_t emitted = 0;
            try {
                auto config = annex(params);
                wheelplay::packetizeVideo({bad.data(),bad.size()},{config.data(),config.size()},hevc,1100,
                    [&](auto,auto,bool){++emitted;});
                assert(false);
            } catch (const std::invalid_argument&) { assert(emitted == 0); }
        }
    }
    std::cout << "H264/HEVC: roundtrip, MTU boundaries, markers, parameter sets, ownership, malformed input passed\n";
}
