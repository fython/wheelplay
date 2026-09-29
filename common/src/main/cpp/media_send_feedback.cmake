include("${CMAKE_CURRENT_LIST_DIR}/dependency_patch.cmake")
wheelplay_replace("${libdatachannel_SOURCE_DIR}/src/impl/track.cpp"
    "#include \"track.hpp\"" "#include \"track.hpp\"\n#include \"wheelplay_send_result.hpp\"")
wheelplay_replace("${libdatachannel_SOURCE_DIR}/src/impl/track.cpp" [=[
		bool ret = false;
		for (auto &m : messages)
			ret = transportSend(std::move(m));

		return ret;]=] [=[
		return wheelplay::sendAll(messages, [this](message_ptr m) {
			return transportSend(std::move(m));
		});]=])
# Keep DataChannel's buffered-send semantics untouched. Only media gets a checked entry point.
wheelplay_replace("${libdatachannel_SOURCE_DIR}/src/capi.cpp"
    "int rtcSendMessage(int id, const char *data, int size) {" [=[
extern "C" RTC_C_EXPORT int wheelplaySendMedia(int id, const char *data, int size) {
	return wrap([&] {
		if (!data || size <= 0) throw std::invalid_argument("Invalid media frame");
		auto b = reinterpret_cast<const byte *>(data);
		return getTrack(id)->send(binary(b, b + size)) ? RTC_ERR_SUCCESS : RTC_ERR_FAILURE;
	});
}

int rtcSendMessage(int id, const char *data, int size) {]=])
target_include_directories(datachannel-static PUBLIC "$<BUILD_INTERFACE:${CMAKE_CURRENT_LIST_DIR}>")
target_include_directories(datachannel PUBLIC "$<BUILD_INTERFACE:${CMAKE_CURRENT_LIST_DIR}>")

# WheelPlay-only borrowed-frame entry points. Existing libdatachannel packetizers stay intact.
wheelplay_replace("${libdatachannel_SOURCE_DIR}/src/capi.cpp"
    "#include \"rtc.hpp\"" "#include \"rtc.hpp\"\n#include \"video_rtp_handler.hpp\"")
wheelplay_replace("${libdatachannel_SOURCE_DIR}/src/capi.cpp"
    "int rtcSetH264Packetizer(int tr, const rtcPacketizerInit *init) {" [=[
extern "C" RTC_C_EXPORT int wheelplaySetVideoPacketizer(int tr, const rtcPacketizerInit *init, bool hevc) {
	return wrap([&] {
		auto track = getTrack(tr);
		auto config = createRtpPacketizationConfig(init);
		emplaceRtpConfig(config, tr);
		track->setMediaHandler(std::make_shared<wheelplay::VideoRtpHandler>(config, hevc, 1100));
		return RTC_ERR_SUCCESS;
	});
}

extern "C" RTC_C_EXPORT int wheelplaySendVideoParts(int tr, const uint8_t *frame, size_t size,
                                                   const uint8_t *parameters, size_t parameterSize) {
	return wrap([&] {
		auto track = getTrack(tr);
		auto handler = std::dynamic_pointer_cast<wheelplay::VideoRtpHandler>(track->getMediaHandler());
		if (!handler) throw std::logic_error("Missing WheelPlay packetizer");
		return handler->send(*track, {frame, size}, {parameters, parameterSize})
		    ? RTC_ERR_SUCCESS : RTC_ERR_FAILURE;
	});
}

int rtcSetH264Packetizer(int tr, const rtcPacketizerInit *init) {]=])
