#include "video_rtp_handler.hpp"
#include <rtc/rtc.hpp>
#include <cassert>
#include <iostream>
#include <chrono>

struct Capture : rtc::MediaHandler {
    rtc::message_vector packets;
    void outgoing(rtc::message_vector& messages, const rtc::message_callback&) override { packets = messages; }
};
int main() {
    rtc::InitLogger(rtc::LogLevel::Error);
    for (bool hevc : {false,true}) {
        rtc::PeerConnection pc;
        rtc::Description::Video video("video",rtc::Description::Direction::SendOnly);
        if (hevc) video.addH265Codec(96); else video.addH264Codec(96);
        video.addSSRC(42,"test");
        auto track = pc.addTrack(video);
        auto config = std::make_shared<rtc::RtpPacketizationConfig>(42,"test",96,90000);
        config->timestamp = 0x12345678; config->sequenceNumber = 65535;
        auto handler = std::make_shared<wheelplay::VideoRtpHandler>(config,hevc,1100);
        auto capture = std::make_shared<Capture>();
        handler->addToChain(std::make_shared<rtc::RtcpNackResponder>(512));
        handler->addToChain(capture); track->setMediaHandler(handler);
        auto send = [&](wheelplay::VideoView frame, wheelplay::VideoView parameters) {
            try { handler->send(*track,frame,parameters); }
            catch (const std::runtime_error& e) {
                // Exercise the real media chain without starting ICE or a network connection.
                if (std::string(e.what()) != "Track is not open") throw;
            }
        };
        std::vector<uint8_t> frame(4096,0x55);
        frame[0]=frame[1]=frame[2]=0;frame[3]=1;frame[4]=hevc ? 0x26 : 0x65;frame[5]=1;
        const auto original = frame;
        uint8_t parameters[] = {0,0,0,1,uint8_t(hevc ? 0x40 : 0x67),1,9};
        send({frame.data(),frame.size()},{parameters,sizeof(parameters)});
        assert(capture->packets.size() == 5);
        auto saved = capture->packets;
        std::fill(frame.begin(),frame.end(),0); // The entire chain owns its RTP payloads.
        for (size_t i=0;i<saved.size();++i) {
            auto* b = reinterpret_cast<const uint8_t*>(saved[i]->data());
            assert(saved[i]->size() <= 1112);
            assert(b[0]==0x80 && b[1]==(96 | (i+1==saved.size() ? 0x80 : 0)));
            assert(((b[2]<<8)|b[3]) == uint16_t(65535+i));
            assert(b[4]==0x12 && b[5]==0x34 && b[6]==0x56 && b[7]==0x78);
            assert(b[8]==0 && b[9]==0 && b[10]==0 && b[11]==42);
        }
        assert((*saved[1])[saved[1]->size()-1] == std::byte{0x55});
        bool threw = false;
        try { send({frame.data(),frame.size()},{}); }
        catch (const std::invalid_argument&) { threw = true; }
        assert(threw);
        rtc::message_vector empty;
        bool cleared = false;
        try { handler->outgoing(empty,{}); }
        catch (const std::logic_error& e) { cleared = std::string(e.what()) == "Video frame must use borrowed send"; }
        assert(cleared);
        // A failing send must clear the borrowed stack pointer before the next call.
        frame = original;
        send({frame.data(),frame.size()},{});
        assert(capture->packets.size()==4);
        assert((*saved[1])[saved[1]->size()-1] == std::byte{0x55});
        uint8_t request[] = {0x81,205,0,3, 0,0,0,1, 0,0,0,42, 0xff,0xff,0,0};
        auto nack = rtc::make_message(sizeof(request),rtc::Message::Control);
        std::memcpy(nack->data(),request,sizeof(request));
        rtc::message_vector incoming{nack};
        rtc::message_ptr retransmitted;
        handler->incomingChain(incoming,[&](rtc::message_ptr packet){retransmitted = packet;});
        assert(retransmitted == saved.front());
        track->close(); pc.close();
    }
    std::cout << "Real media chain: RTP headers, sequence wrap, timestamps, borrowed lifetime, NACK retention and exception recovery passed\n";
}
