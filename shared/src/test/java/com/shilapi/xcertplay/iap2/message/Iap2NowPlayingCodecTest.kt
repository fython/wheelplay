package com.shilapi.xcertplay.iap2.message

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2CsmFramer
import com.shilapi.xcertplay.iap2.wire.Iap2ProtocolException
import com.shilapi.xcertplay.media.*
import org.junit.Assert.*
import org.junit.Test

class Iap2NowPlayingCodecTest {
    private fun fixture(name: String) = Iap2CsmFramer().offer(
        javaClass.getResource("/media/$name.hex")!!.readText().trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()).single()

    @Test fun replayFullThenPauseKeepsMetadataAndStopsMonotonicProgress() {
        val full = Iap2NowPlayingCodec.decode(fixture("now-playing-full"))
        var state = NowPlayingReducer.update(NowPlayingState(connected = true), full, 1000)
        assertEquals("Sample", state.title); assertEquals("Artist", state.artist)
        assertEquals("Album", state.album); assertEquals(240000L, state.durationMs)
        assertEquals(123L, state.persistentId); assertEquals(128, state.artworkTransferId)
        assertEquals(1.5f, state.speed, 0f)
        assertEquals(91500L, state.positionAt(2000))
        state = NowPlayingReducer.update(state, Iap2NowPlayingCodec.decode(fixture("now-playing-pause")), 2000)
        assertEquals(PhonePlaybackStatus.PAUSED, state.status)
        assertEquals(91500L, state.positionAt(99000))
        assertEquals("Artist", state.artist)
        assertTrue(state.setElapsedTimeAvailable)
        assertFalse("Unverified outbound units must never advertise seek", state.canSeek)
    }

    @Test fun trackChangesClearArtworkAndProgressButRetainAbsentFields() {
        val artwork = MediaArtwork(byteArrayOf(1, 2))
        val old = NowPlayingState(connected = true, persistentId = 1, title = "First", artist = "Same artist",
            artworkTransferId = 128, artwork = artwork, positionMs = 1234, status = PhonePlaybackStatus.PLAYING)
        val next = NowPlayingReducer.update(old, NowPlayingUpdate(persistentId = 2, title = "Second"), 9000)
        assertNull(next.artwork); assertNull(next.artworkTransferId); assertNull(next.positionMs)
        assertEquals("Same artist", next.artist); assertEquals(PhonePlaybackStatus.PLAYING, next.status)
        assertEquals(old.trackRevision + 1, next.trackRevision)
        val corrected = NowPlayingReducer.update(old, NowPlayingUpdate(persistentId = 1, title = "Corrected"), 9000)
        assertSame(artwork, corrected.artwork)
    }

    @Test fun unknownFieldsAreIgnoredAndInvalidKnownWidthsAreRejected() {
        val future = Iap2Messages.buildRaw(0x5001) { raw(100, byteArrayOf(1)); group(1) { u8(0, 1); raw(99, byteArrayOf(2)) } }
        assertEquals(PhonePlaybackStatus.PLAYING, Iap2NowPlayingCodec.decode(future).status)
        for (invalid in listOf(
            Iap2Messages.buildRaw(0x5001) { group(1) { u8(0, 250) } },
            Iap2Messages.buildRaw(0x5001) { group(1) { u8(13, 2) } },
        )) assertThrows(Iap2ProtocolException::class.java) { Iap2NowPlayingCodec.decode(invalid) }
        val bad = Iap2Messages.buildRaw(0x5001) { group(1) { u16(0, 1) } }
        assertThrows(Iap2ProtocolException::class.java) { Iap2NowPlayingCodec.decode(bad) }
    }

    @Test fun reannouncingArtworkOnNewLinkPreservesCoverButNewTrackStillClearsIt() {
        val cover = MediaArtwork(byteArrayOf(1,2))
        val handoff = NowPlayingState(connected = true, persistentId = 1, title = "Current",
            artwork = cover, artworkTransferId = null)
        val refreshed = NowPlayingReducer.update(handoff, NowPlayingUpdate(persistentId = 1, artworkTransferId = 129), 10)
        assertSame(cover, refreshed.artwork)
        assertEquals(129, refreshed.artworkTransferId)
        val changedCover = NowPlayingReducer.update(refreshed, NowPlayingUpdate(artworkTransferId = 130), 20)
        assertSame(cover, changedCover.artwork)
        assertEquals(130, changedCover.artworkTransferId)
        val changedTrack = NowPlayingReducer.update(refreshed, NowPlayingUpdate(persistentId = 2), 20)
        assertNull(changedTrack.artwork)
    }

    @Test fun learningInitialTitleAndIdentityDoesNotInvalidateAnAlreadyAdvertisedCover() {
        val cover = MediaArtwork(byteArrayOf(1,2))
        val initial = NowPlayingState(connected = true, trackRevision = 7, artworkTransferId = 128,
            artwork = cover, positionMs = 1200)
        val titled = NowPlayingReducer.update(initial, NowPlayingUpdate(title = "Current"), 10)
        val identified = NowPlayingReducer.update(titled, NowPlayingUpdate(persistentId = 1), 20)
        assertEquals(7L, identified.trackRevision)
        assertEquals(128, identified.artworkTransferId)
        assertSame(cover, identified.artwork)
        assertEquals(1200L, identified.positionMs)
        val next = NowPlayingReducer.update(identified, NowPlayingUpdate(persistentId = 2), 30)
        assertEquals(8L, next.trackRevision)
        assertNull(next.artwork)
    }

    @Test fun subscriptionIncludesArtworkIdentityRateAndSeekCapability() {
        val body = Iap2BodyReader.of(Iap2NowPlayingCodec.subscription())
        for (id in listOf(0,1,4,6,12,26)) assertTrue(body.group(0).has(id))
        for (id in listOf(0,1,2,7,12,13,16)) assertTrue(body.group(1).has(id))
        val raw = Iap2NowPlayingCodec.setElapsedTime(0x12345678)
        assertEquals(0x5003, raw.messageId)
        assertArrayEquals(byteArrayOf(0x12,0x34,0x56,0x78), Iap2BodyReader.of(raw).raw(0))
    }

    @Test fun speedZeroMeansUnknownAndArtworkSnapshotsOwnTheirBytes() {
        val old = NowPlayingState(reportedSpeed = 2f, status = PhonePlaybackStatus.PLAYING)
        assertEquals(1f, NowPlayingReducer.update(old, NowPlayingUpdate(speed = 0f), 10).speed, 0f)
        val bytes = byteArrayOf(1,2); val art = MediaArtwork(bytes)
        bytes[0] = 9; art.bytes()[1] = 9
        assertArrayEquals(byteArrayOf(1,2), art.bytes())
        assertEquals(listOf(1,2,3,4,5), MediaCommand.entries.map { it.hidIndex })
    }

    @Test fun lyricUpdatesWithoutIdentityFieldsKeepTheCoverAndProgress() {
        val cover = MediaArtwork(byteArrayOf(1,2))
        var state = NowPlayingState(connected = true, trackRevision = 24, persistentId = 123,
            queueIndex = 3, title = "Song", artist = "Artist", album = "Album", artworkTransferId = 137,
            artwork = cover, positionMs = 10000, positionAtMs = 1000, status = PhonePlaybackStatus.PLAYING)
        for (now in listOf(4500L,8000L,11500L)) {
            state = NowPlayingReducer.update(state, NowPlayingUpdate(title = "Lyric $now",
                artist = "Displayed artist $now", album = "Displayed album $now"), now)
            assertEquals("Lyric $now", state.title)
            assertEquals(24L, state.trackRevision)
            assertEquals(123L, state.persistentId)
            assertEquals(3L, state.queueIndex)
            assertEquals(137, state.artworkTransferId)
            assertSame(cover, state.artwork)
            assertEquals(10000L + now - 1000, state.positionAt(now))
        }
    }

    @Test fun titleRefreshWithoutAnyStableIdentityDoesNotGuessATrackBoundary() {
        val cover = MediaArtwork(byteArrayOf(1,2))
        val old = NowPlayingState(trackRevision = 7, title = "Song", artworkTransferId = 128,
            artwork = cover, positionMs = 1000)
        for (title in listOf("Lyric", "")) {
            val next = NowPlayingReducer.update(old, NowPlayingUpdate(title = title), 10)
            assertEquals(title, next.title)
            assertEquals(7L, next.trackRevision)
            assertEquals(128, next.artworkTransferId)
            assertSame(cover, next.artwork)
            assertEquals(1000L, next.positionMs)
        }
    }

    @Test fun confirmedTrackBoundaryRetiresOldIdentitySoLateFieldsDoNotClearTheNewCover() {
        val cover = MediaArtwork(byteArrayOf(1,2))
        val old = NowPlayingState(trackRevision = 7, persistentId = 1, queueIndex = 0,
            title = "Old", artworkTransferId = 128, artwork = cover, positionMs = 1000)
        val byQueue = NowPlayingReducer.update(old, NowPlayingUpdate(queueIndex = 1), 10)
        assertEquals(8L, byQueue.trackRevision)
        assertNull(byQueue.persistentId)
        assertNull(byQueue.artwork)
        assertNull(byQueue.positionMs)
        val completed = byQueue.copy(artworkTransferId = 129, artwork = cover)
        val identified = NowPlayingReducer.update(completed, NowPlayingUpdate(persistentId = 2, title = "New"), 20)
        assertEquals(8L, identified.trackRevision)
        assertSame(cover, identified.artwork)
        val byPid = NowPlayingReducer.update(old, NowPlayingUpdate(persistentId = 2), 10)
        assertNull(byPid.queueIndex)
        assertEquals(8L, NowPlayingReducer.update(byPid, NowPlayingUpdate(queueIndex = 1), 20).trackRevision)
    }

    @Test fun switchingPlayersClearsCoverProgressAndOldIdentity() {
        val old = NowPlayingState(trackRevision = 7, persistentId = 1, queueIndex = 0,
            title = "Song", appBundleId = "player.old", artworkTransferId = 128,
            artwork = MediaArtwork(byteArrayOf(1)), positionMs = 1000)
        val next = NowPlayingReducer.update(old, NowPlayingUpdate(appBundleId = "player.new"), 10)
        assertEquals(8L, next.trackRevision)
        assertNull(next.artwork); assertNull(next.artworkTransferId)
        assertNull(next.positionMs); assertNull(next.persistentId); assertNull(next.queueIndex)
    }
}
