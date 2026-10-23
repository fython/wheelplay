package com.shilapi.xcertplay.androidauto

/** Fixed 720p H.264 profile. Only channels implemented by the receiver are advertised. */
object AapServices {
    const val WIDTH = 1280
    const val HEIGHT = 720
    val channels = setOf(1, 2, 3, 4, 5, 6)
    fun discovery(microphone: Boolean) = AapProto.build {
        bytes(1, AapProto.build { number(1, 1); bytes(2, AapProto.build {
            bytes(1, AapProto.build { number(1, 10) })
        }) })
        bytes(1, AapProto.build { number(1, 2); bytes(3, AapProto.build {
            number(1, 3); number(5, 1)
            bytes(4, AapProto.build { number(1, 2); number(2, 2); number(3, 0); number(4, 0); number(5, 160); number(8, 10000); number(10, 3) })
        }) })
        bytes(1, AapProto.build { number(1, 3); bytes(4, AapProto.build {
            bytes(2, AapProto.build { number(1, WIDTH.toLong()); number(2, HEIGHT.toLong()) })
        }) })
        for (channel in 4..6) bytes(1, AapProto.build { number(1, channel.toLong()); bytes(3, AapProto.build {
            number(1, 1); number(2, (channel - 3).toLong()); bytes(3, audioConfig(channel))
        }) })
        if (microphone) bytes(1, AapProto.build { number(1, 7); bytes(5, AapProto.build { number(1, 1); bytes(2, audioConfig(7)) }) })
        text(2, "WheelPlay"); text(3, "Projection"); text(4, "2026"); text(5, "WheelPlay")
        number(6, 0); text(7, "WheelPlay"); text(8, "Android"); text(9, "1"); text(10, "1"); number(11, 0)
        text(14, "WheelPlay")
    }
    fun audioConfig(channel: Int) = AapProto.build {
        number(1, if (channel == 6) 48000 else 16000); number(2, 16); number(3, if (channel == 6) 2 else 1)
    }
    fun night(night: Boolean) = AapProto.build { bytes(10, AapProto.build { number(1, if (night) 1 else 0) }) }
}
