# Opt in only WheelPlay's independently owned video packets. Other media/RTCP keep upstream behavior.
wheelplay_replace("${libdatachannel_SOURCE_DIR}/include/rtc/message.hpp"
    "#include \"common.hpp\"" "#include \"common.hpp\"\n#include \"srtp_packet.hpp\"")
wheelplay_replace("${libdatachannel_SOURCE_DIR}/include/rtc/message.hpp"
    "\tshared_ptr<FrameInfo> frameInfo;" "\tshared_ptr<FrameInfo> frameInfo;\n\tstd::shared_ptr<wheelplay::SrtpPacketState> wheelplaySrtp;")
wheelplay_replace("${libdatachannel_SOURCE_DIR}/src/impl/dtlssrtptransport.hpp"
    "\tstd::mutex sendMutex;" "\tstd::mutex sendMutex;\n\tconst std::shared_ptr<const int> wheelplayContext = std::make_shared<const int>(0);")
wheelplay_replace("${libdatachannel_SOURCE_DIR}/src/impl/dtlssrtptransport.cpp"
    "\tint size = int(message->size());\n\tPLOG_VERBOSE << \"Send size=\" << size;" [=[
	if (message->wheelplaySrtp) {
		return wheelplay::sendOwnedSrtp(message, wheelplayContext, SRTP_MAX_TRAILER_LEN,
		    [this](void* bytes, int* length) { return int(srtp_protect(mSrtpOut, bytes, length)); },
		    [this](message_ptr packet) { return Transport::outgoing(packet); });
	}
	int size = int(message->size());
	PLOG_VERBOSE << "Send size=" << size;]=])
# Dedicated packets have immutable DSCP set before NACK publication; do not race with retransmission.
wheelplay_replace("${libdatachannel_SOURCE_DIR}/src/impl/track.cpp"
    "\t\tif (mMediaDescription.type() == \"audio\")\n\t\t\tmessage->dscp = 46; // EF: Expedited Forwarding\n\t\telse\n\t\t\tmessage->dscp = 36; // AF42: Assured Forwarding class 4, medium drop probability" [=[
		if (!message->wheelplaySrtp) {
			if (mMediaDescription.type() == "audio")
				message->dscp = 46;
			else
				message->dscp = 36;
		}]=])
wheelplay_replace("${libdatachannel_SOURCE_DIR}/src/capi.cpp"
    "int rtcGetCurrentTrackTimestamp(int id, uint32_t *timestamp) {" [=[
extern "C" RTC_C_EXPORT int wheelplaySrtpStats(int tr, int64_t *values) {
	return wrap([&] {
		auto handler = std::dynamic_pointer_cast<wheelplay::VideoRtpHandler>(getTrack(tr)->getMediaHandler());
		if (!handler || !values) return RTC_ERR_INVALID;
		auto s = handler->metrics();
		values[0] = s->protectedPackets.load(); values[1] = s->retransmissions.load();
		values[2] = s->protectNs.load(); values[3] = s->sendNs.load();
		values[4] = s->bytes.load(); values[5] = s->failures.load();
		return RTC_ERR_SUCCESS;
	});
}

int rtcGetCurrentTrackTimestamp(int id, uint32_t *timestamp) {]=])
