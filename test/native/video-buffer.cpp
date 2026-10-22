#include "video_buffer.hpp"
#include "video_packetizer.hpp"
#include <cassert>
#include <iostream>

using Bytes = std::vector<uint8_t>;
static Bytes lengths(const std::vector<Bytes>& nals) {
    Bytes result;
    for (const auto& nal : nals) {
        const uint32_t size = nal.size();
        for (int shift : {24,16,8,0}) result.push_back(size >> shift);
        result.insert(result.end(),nal.begin(),nal.end());
    }
    return result;
}
static Bytes annex(const std::vector<Bytes>& nals, bool mixed = false) {
    Bytes result;
    for (size_t i=0;i<nals.size();++i) {
        if (!mixed || i%2 == 0) result.push_back(0);
        result.insert(result.end(),{0,0,1});
        result.insert(result.end(),nals[i].begin(),nals[i].end());
    }
    return result;
}
static void verify(const std::vector<Bytes>& nals, bool hevc, bool keyframe) {
    wheelplay::VideoBuffer frame;
    frame.pool = std::make_shared<wheelplay::VideoPool>();
    frame.bytes = lengths(nals);
    wheelplay::annexB(frame,wheelplay::VideoInputFormat::LengthPrefixed);
    assert(frame.bytes == annex(nals));
    assert((hevc ? frame.hevcKeyframe : frame.avcKeyframe) == keyframe);
    // Check the consumer sees the original NAL bytes after conversion and RTP FU assembly.
    std::vector<Bytes> reconstructed;
    size_t markers = 0;
    wheelplay::packetizeVideo({frame.bytes.data(),frame.bytes.size()},{nullptr,0},hevc,1100,
        [&](wheelplay::VideoView header, wheelplay::VideoView payload, bool marker) {
            if (!header.size) reconstructed.emplace_back(payload.data,payload.data+payload.size);
            else {
                const uint8_t flags = header.data[header.size-1];
                if (flags & 0x80) {
                    if (hevc) reconstructed.push_back({uint8_t((header.data[0]&0x81)|((flags&63)<<1)),header.data[1]});
                    else reconstructed.push_back({uint8_t((header.data[0]&0xe0)|(flags&31))});
                }
                reconstructed.back().insert(reconstructed.back().end(),payload.data,payload.data+payload.size);
            }
            if (marker) markers++;
        });
    assert(reconstructed == nals);
    assert(markers == 1);
}
int main() {
    for (bool hevc : {false,true}) {
        for (size_t size=2;size<=1101;++size) {
            Bytes nal(size,0x55); nal[0] = hevc ? 0x02 : 0x41;
            if (hevc) nal[1] = 1;
            verify({nal},hevc,false);
            // A small first NAL must not hide the next NAL or invent a keyframe.
            verify({nal,nal},hevc,false);
            nal[0] = hevc ? 0x28 : 0x65;
            verify({nal},hevc,true);
        }
        Bytes large(65536,0x55);large[0] = hevc ? 0x02 : 0x41;large[1] = 1;
        verify({large},hevc,false);
    }
    // Genuine Annex B remains explicit; scanning accepts both start-code lengths.
    for (Bytes bytes : {Bytes{0,0,1,0x28,1,7},Bytes{0,0,0,1,0x28,1,7},
                       annex({{0x40,1,9},{0x42,1,8},{0x44,1,7},{0x28,1,6}},true)}) {
        wheelplay::VideoBuffer frame;frame.pool = std::make_shared<wheelplay::VideoPool>();
        frame.bytes = bytes;
        wheelplay::annexB(frame,wheelplay::VideoInputFormat::AnnexB);
        assert(frame.bytes == bytes && frame.hevcKeyframe);
    }
    for (Bytes bytes : {Bytes{},Bytes{0,0,0},Bytes{0,0,0,0},Bytes{0,0,1,0x20,0x28,1},
                       Bytes{0,0,0,2,0x28,1,0},Bytes{0,0,0,2,0x28,1,0,0,0,9,2,1}}) {
        wheelplay::VideoBuffer frame;frame.pool = std::make_shared<wheelplay::VideoPool>();
        frame.bytes = bytes;frame.avcKeyframe = frame.hevcKeyframe = true;
        wheelplay::annexB(frame,wheelplay::VideoInputFormat::LengthPrefixed);
        assert(frame.bytes == bytes && !frame.avcKeyframe && !frame.hevcKeyframe);
    }
    // Even a valid three-byte Annex B access unit can also form a valid length record.
    // Its format must come from the caller, not from a heuristic over these bytes.
    auto ambiguous = lengths({Bytes(380,0x55)});
    ambiguous[4] = 2;ambiguous[5] = 1;
    wheelplay::VideoBuffer frame;frame.pool = std::make_shared<wheelplay::VideoPool>();
    frame.bytes = ambiguous;
    wheelplay::annexB(frame,wheelplay::VideoInputFormat::AnnexB);
    assert(frame.bytes == ambiguous);
    std::cout << "Native conversion: length boundaries, framing contracts, keyframe flags, malformed records and RTP roundtrip passed\n";
}
