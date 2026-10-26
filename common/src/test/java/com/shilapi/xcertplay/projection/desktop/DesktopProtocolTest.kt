package com.shilapi.xcertplay.projection.desktop

import android.os.BadParcelableException
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
    @Test fun structuredResultsPreserveNestedStatusAndNullableHandshakeInfo() {
        val serviceInfo = DesktopNegotiation.negotiate(version = 5, requested = DesktopFeatures.HOST_SUPPORTED, helperUid = 2000)
        val success = DesktopHandshakeResult().apply { status = DesktopStatus.OK; info = serviceInfo }
        val decoded = parcelRoundTrip(success, DesktopHandshakeResult.CREATOR)
        assertEquals(success, decoded)
        assertSame(DesktopStatus.OK, decoded.status)
        val failed = DesktopHandshakeResult().apply { status = DesktopStatus(9001, "future error"); info = null }
        assertEquals(failed, parcelRoundTrip(failed, DesktopHandshakeResult.CREATOR))
        val started = DesktopStartResult().apply { status = DesktopStatus.OK; displayId = 42 }
        assertEquals(started, parcelRoundTrip(started, DesktopStartResult.CREATOR))
        val missing = parcelRoundTrip(DesktopStartResult(), DesktopStartResult.CREATOR)
        assertEquals(DesktopErrorCode.PROTOCOL_MISMATCH, assertThrows(DesktopException::class.java) {
            missing.status.checkSuccess()
        }.category)
    }

    @Test fun structuredSizeHeadersSkipUnknownFieldsAndRejectInvalidOrIncompleteInfo() {
        val info = DesktopNegotiation.negotiate(version = 5, requested = DesktopFeatures.HOST_SUPPORTED, helperUid = 2000)
        withParcel { parcel ->
            info.writeToParcel(parcel, 0)
            parcel.writeInt(123) // Simulate an appended future field within the declared object size.
            val end = parcel.dataPosition()
            parcel.setDataPosition(0); parcel.writeInt(end); parcel.setDataPosition(0)
            assertEquals(info, DesktopServiceInfo.CREATOR.createFromParcel(parcel))
            assertEquals(0, parcel.dataAvail())
        }
        withParcel { parcel ->
            parcel.writeInt(4); parcel.setDataPosition(0) // Struct with all fields absent.
            val empty = DesktopServiceInfo.CREATOR.createFromParcel(parcel)
            assertEquals(-1, empty.uid)
            assertThrows(DesktopException::class.java) { empty.validate() }
        }
        withParcel { parcel ->
            parcel.writeInt(3); parcel.setDataPosition(0)
            assertThrows(BadParcelableException::class.java) { DesktopServiceInfo.CREATOR.createFromParcel(parcel) }
        }
    }

    @Test fun handshakeIntersectsCapabilitiesAndPreservesLimitsAcrossParcel() {
        val info = DesktopNegotiation.negotiate(5, DesktopFeatures.HOST_SUPPORTED or DesktopFeatures.KEYBOARD or (1L shl 60), 2000)
        assertEquals(DesktopFeatures.HOST_SUPPORTED, info.enabledFeatures)
        info.validate()
        assertTrue(info.accepts(DesktopDisplayConfig(1080, 1920, 320)))
        assertFalse(info.copyWith { maxSize = 1280 }.accepts(DesktopDisplayConfig(1920, 1080, 160)))
        assertEquals(info, parcelRoundTrip(info, DesktopServiceInfo.CREATOR))
    }

    @Test fun optionalInputCanBeDisabledAndMalformedNegotiationIsRejected() {
        val info = DesktopNegotiation.negotiate(5, DesktopFeatures.REQUIRED, 2000)
        assertEquals(0, info.maxTouchContacts)
        info.validate(DesktopFeatures.REQUIRED)
        val capabilities = DesktopFeatures.projection(info.enabledFeatures, info.maxTouchContacts)
        assertEquals(setOf(VideoIngressKind.SURFACE), capabilities.video)
        assertEquals(0, capabilities.maxTouchContacts); assertFalse(capabilities.backButton)
        assertFalse(capabilities.keyboardInput); assertFalse(capabilities.pointerInput)
        assertFalse(capabilities.audioPlayback); assertFalse(capabilities.microphone)
        assertFalse(capabilities.browserAudio); assertFalse(capabilities.browserMicrophone)
        assertEquals(DesktopErrorCode.PROTOCOL_MISMATCH, assertThrows(DesktopException::class.java) {
            DesktopNegotiation.negotiate(4, DesktopFeatures.HOST_SUPPORTED, 2000)
        }.category)
        assertEquals(DesktopErrorCode.UNSUPPORTED_CAPABILITY, assertThrows(DesktopException::class.java) {
            DesktopNegotiation.negotiate(5, DesktopFeatures.TOUCH, 2000)
        }.category)
        for (invalid in listOf(info.copyWith { uid = 12345 }, info.copyWith { enabledFeatures = enabledFeatures or DesktopFeatures.KEYBOARD },
            info.copyWith { maxTouchContacts = 10 }, info.copyWith { minSize = -1 }, info.copyWith { maxPixels = 0 })) {
            assertThrows(DesktopException::class.java) { invalid.validate(DesktopFeatures.REQUIRED) }
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
            withParcel { old ->
                withParcel { packed ->
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
                }
            }
        }
    }

    @Test fun touchDecoderRejectsHugeCountsTruncationAndInvalidBooleansBeforeInjection() {
        for (count in listOf(-1, 11, Int.MAX_VALUE, 1)) {
            withParcel { parcel ->
                parcel.writeInt(1); parcel.writeInt(count); parcel.setDataPosition(0)
                assertThrows(BadParcelableException::class.java) { DesktopTouchFrame.CREATOR.createFromParcel(parcel) }
            }
        }
        withParcel { parcel ->
            parcel.writeInt(1); parcel.writeInt(1); parcel.writeInt(0)
            parcel.writeFloat(.5f); parcel.writeFloat(.5f); parcel.writeInt(2); parcel.setDataPosition(0)
            assertThrows(BadParcelableException::class.java) { DesktopTouchFrame.CREATOR.createFromParcel(parcel) }
        }
    }

    @Test fun serviceErrorsPreserveStableCodeAndDiagnosticsIncludingUnknownCodes() {
        for (code in listOf(DesktopErrorCode.LAUNCH_FAILED.wire, 9001)) {
            val wire = desktopServiceStatus { throw DesktopException(code, "launch detail") }
            withParcel { parcel ->
                parcel.writeTypedObject(wire, 0); parcel.setDataPosition(0)
                val error = assertThrows(DesktopException::class.java) { parcel.readTypedObject(DesktopStatus.CREATOR)!!.throwIfError() }
                assertEquals(code, error.code); assertTrue(error.diagnostic.contains("launch detail"))
                assertEquals(if (code == 9001) DesktopErrorCode.UNKNOWN else DesktopErrorCode.LAUNCH_FAILED, error.category)
            }
        }
        assertEquals(DesktopErrorCode.INVALID_ARGUMENT.wire, desktopServiceStatus { require(false) { "invalid input" } }.code)
    }
}
