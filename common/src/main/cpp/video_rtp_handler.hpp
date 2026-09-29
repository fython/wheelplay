#pragma once
#include "video_packetizer.hpp"
#include <rtc/mediahandler.hpp>
#include <rtc/rtppacketizationconfig.hpp>
#include <rtc/track.hpp>
#include <cstring>
#include <mutex>
#include "srtp_packet.hpp"
#include <srtp.h>

namespace wheelplay {
// Dedicated to WheelPlay's fixed RTP setup (no RTP header extensions or aggregation).
// The pinned Track::send runs outgoingChain synchronously. The caller retains the input frame
// until send returns; only independently owned RTP packets reach RTCP/NACK/SRTP handlers.
class VideoRtpHandler final : public rtc::MediaHandler {
public:
    VideoRtpHandler(std::shared_ptr<rtc::RtpPacketizationConfig> config, bool hevc, size_t maxPayload)
        : config_(std::move(config)), hevc_(hevc), maxPayload_(maxPayload) {}
    std::shared_ptr<SrtpMetrics> metrics() const { return metrics_; }
    bool send(rtc::Track& track, VideoView frame, VideoView parameters) {
        std::lock_guard<std::mutex> lock(sendMutex_);
        Input input{frame, parameters};
        struct Reset { const Input*& target; ~Reset() { target = nullptr; } } reset{input_};
        input_ = &input;
        return track.send(rtc::binary{}); // Empty envelope; never copies the compressed frame.
    }
    void outgoing(rtc::message_vector& messages, const rtc::message_callback&) override {
        if (!input_) throw std::logic_error("Video frame must use borrowed send");
        rtc::message_vector packets;
        packetizeVideo(input_->frame, input_->parameters, hevc_, maxPayload_,
            [&](VideoView header, VideoView payload, bool marker) {
                auto packet = rtc::make_message(0);
                const size_t size = 12 + header.size + payload.size;
                packet->reserve(size + SRTP_MAX_TRAILER_LEN);
                packet->resize(size);
                packet->dscp = 36;
                packet->wheelplaySrtp = std::make_shared<SrtpPacketState>();
                packet->wheelplaySrtp->metrics = metrics_;
                packet->wheelplaySrtp->owner = packet.get();
                auto* bytes = reinterpret_cast<uint8_t*>(packet->data());
                bytes[0] = 0x80;
                bytes[1] = config_->payloadType | (marker ? 0x80 : 0);
                const uint16_t sequence = config_->sequenceNumber++;
                bytes[2] = sequence >> 8; bytes[3] = sequence;
                for (int i = 0; i < 4; ++i) {
                    bytes[4+i] = config_->timestamp >> (24-8*i);
                    bytes[8+i] = config_->ssrc >> (24-8*i);
                }
                if (header.size) std::memcpy(bytes + 12, header.data, header.size);
                std::memcpy(bytes + 12 + header.size, payload.data, payload.size);
                packets.push_back(std::move(packet));
            });
        messages.swap(packets);
    }
private:
    struct Input { VideoView frame, parameters; };
    std::shared_ptr<SrtpMetrics> metrics_ = std::make_shared<SrtpMetrics>();
    const Input* input_ = nullptr;
    std::mutex sendMutex_;
    std::shared_ptr<rtc::RtpPacketizationConfig> config_;
    bool hevc_;
    size_t maxPayload_;
};
}
