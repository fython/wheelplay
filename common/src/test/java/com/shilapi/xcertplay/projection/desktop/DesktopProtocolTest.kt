package com.shilapi.xcertplay.projection.desktop

import android.os.BadParcelableException
import android.os.Parcel
import com.shilapi.xcertplay.projection.ProjectionContact
import com.shilapi.xcertplay.projection.VideoIngressKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DesktopProtocolTest {
    @Test fun handshakeIntersectsCapabilitiesAndPreservesLimitsAcrossParcel() {
        val info = DesktopServiceInfo.negotiate(4, DesktopFeatures.HOST_SUPPORTED or DesktopFeatures.KEYBOARD or (1L shl 60), 2000)
        assertEquals(DesktopFeatures.HOST_SUPPORTED, info.enabledFeatures)
        DesktopServiceInfo.validate(info)
        assertTrue(info.accepts(DesktopDisplayConfig(1080, 1920, 320)))
        assertFalse(info.copy(maxSize = 1280).accepts(DesktopDisplayConfig(1920, 1080, 160)))
        val parcel = Parcel.obtain()
        try {
            parcel.writeTypedObject(info, 0); parcel.setDataPosition(0)
            assertEquals(info, parcel.readTypedObject(DesktopServiceInfo.CREATOR))
        } finally { parcel.recycle() }
    }

    @Test fun optionalInputCanBeDisabledAndMalformedNegotiationIsRejected() {
        val info = DesktopServiceInfo.negotiate(4, DesktopFeatures.REQUIRED, 2000)
        assertEquals(0, info.maxTouchContacts)
        DesktopServiceInfo.validate(info, DesktopFeatures.REQUIRED)
        val capabilities = DesktopFeatures.projection(info.enabledFeatures, info.maxTouchContacts)
        assertEquals(setOf(VideoIngressKind.SURFACE), capabilities.video)
        assertEquals(0, capabilities.maxTouchContacts); assertFalse(capabilities.backButton)
        assertFalse(capabilities.keyboardInput); assertFalse(capabilities.pointerInput)
        assertFalse(capabilities.audioPlayback); assertFalse(capabilities.microphone)
        assertFalse(capabilities.browserAudio); assertFalse(capabilities.browserMicrophone)
        assertEquals(DesktopErrorCode.PROTOCOL_MISMATCH, assertThrows(DesktopException::class.java) {
            DesktopServiceInfo.negotiate(2, DesktopFeatures.HOST_SUPPORTED, 2000)
        }.category)
        assertEquals(DesktopErrorCode.UNSUPPORTED_CAPABILITY, assertThrows(DesktopException::class.java) {
            DesktopServiceInfo.negotiate(4, DesktopFeatures.TOUCH, 2000)
        }.category)
        for (invalid in listOf(info.copy(uid = 12345), info.copy(enabledFeatures = info.enabledFeatures or DesktopFeatures.KEYBOARD),
            info.copy(maxTouchContacts = 10), info.copy(minSize = -1), info.copy(maxPixels = 0))) {
            assertThrows(DesktopException::class.java) { DesktopServiceInfo.validate(invalid, DesktopFeatures.REQUIRED) }
        }
    }

    @Test fun audioRoutesRemainSeparateFromLocalAudioAndOtherInputs() {
        val local = DesktopFeatures.projection(DesktopFeatures.VIDEO_ENCODED or DesktopFeatures.AUDIO_PLAYBACK or
            DesktopFeatures.MICROPHONE or DesktopFeatures.NIGHT_MODE, 0)
        assertTrue(local.audioPlayback); assertTrue(local.microphone); assertTrue(local.nightMode)
        assertFalse(local.browserAudio); assertFalse(local.browserMicrophone)
        val remote = DesktopFeatures.projection(DesktopFeatures.VIDEO_FRAMEBUFFER or DesktopFeatures.POINTER or DesktopFeatures.KEYBOARD, 0)
        assertTrue(remote.pointerInput); assertTrue(remote.keyboardInput); assertFalse(remote.backButton)
        val orphanRoute = DesktopFeatures.projection(DesktopFeatures.BROWSER_AUDIO or DesktopFeatures.BROWSER_MICROPHONE, 0)
        assertFalse(orphanRoute.browserAudio); assertFalse(orphanRoute.browserMicrophone)
    }

    @Test fun packedTouchRoundTripsAndUsesEightFewerBytesThanParallelArrays() {
        for (count in listOf(0, 1, 2, 10)) {
            val frame = DesktopTouchFrame.from(List(count) { ProjectionContact(it, it / 10.0, 1.0 - it / 10.0, it % 2 == 0) })
            val old = Parcel.obtain(); val packed = Parcel.obtain()
            try {
                old.writeString("same-session-token"); old.writeInt(frame.layout)
                old.writeIntArray(frame.ids); old.writeFloatArray(frame.xs); old.writeFloatArray(frame.ys); old.writeBooleanArray(frame.down)
                packed.writeString("same-session-token"); packed.writeTypedObject(frame, 0)
                assertEquals("contacts=$count", old.dataSize() - 8, packed.dataSize())
                packed.setDataPosition(0); assertEquals("same-session-token", packed.readString())
                val decoded = packed.readTypedObject(DesktopTouchFrame.CREATOR)!!
                assertEquals(frame.layout, decoded.layout)
                assertArrayEquals(frame.ids, decoded.ids); assertArrayEquals(frame.xs, decoded.xs, 0f)
                assertArrayEquals(frame.ys, decoded.ys, 0f); assertArrayEquals(frame.down, decoded.down)
                assertEquals(0, packed.dataAvail())
                // A oneway touch has no reply Parcel at all; only the compact request is sent.
            } finally { old.recycle(); packed.recycle() }
        }
    }

    @Test fun touchDecoderRejectsHugeCountsTruncationAndInvalidBooleansBeforeInjection() {
        for (count in listOf(-1, 11, Int.MAX_VALUE, 1)) {
            val parcel = Parcel.obtain()
            try {
                parcel.writeInt(1); parcel.writeInt(count); parcel.setDataPosition(0)
                assertThrows(BadParcelableException::class.java) { DesktopTouchFrame.CREATOR.createFromParcel(parcel) }
            } finally { parcel.recycle() }
        }
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(1); parcel.writeInt(1); parcel.writeInt(0)
            parcel.writeFloat(.5f); parcel.writeFloat(.5f); parcel.writeInt(2); parcel.setDataPosition(0)
            assertThrows(BadParcelableException::class.java) { DesktopTouchFrame.CREATOR.createFromParcel(parcel) }
        } finally { parcel.recycle() }
    }

    @Test fun serviceErrorsPreserveStableCodeAndDiagnosticsIncludingUnknownCodes() {
        for (code in listOf(DesktopErrorCode.LAUNCH_FAILED.wire, 9001)) {
            val wire = desktopServiceStatus { throw DesktopException(code, "launch detail") }
            val parcel = Parcel.obtain()
            try {
                parcel.writeTypedObject(wire, 0); parcel.setDataPosition(0)
                val error = assertThrows(DesktopException::class.java) { parcel.readTypedObject(DesktopStatus.CREATOR)!!.throwIfError() }
                assertEquals(code, error.code); assertTrue(error.diagnostic.contains("launch detail"))
                assertEquals(if (code == 9001) DesktopErrorCode.UNKNOWN else DesktopErrorCode.LAUNCH_FAILED, error.category)
            } finally { parcel.recycle() }
        }
        assertEquals(DesktopErrorCode.INVALID_ARGUMENT.wire, desktopServiceStatus { require(false) { "invalid input" } }.code)
    }
}
