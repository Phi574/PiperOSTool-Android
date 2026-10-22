package com.piperostool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PiperDataTransferProgressStateTest {
    @Test
    fun progressTracksTheActiveItemAndRetainsBytesForUnknownSizes() {
        val session = UUID.randomUUID().toString()
        PiperDataTransferProgressState.begin(
            session,
            PiperDataTransferProgressActivity.ROLE_SEND,
            listOf(
                PiperDataTransferProgressState.Item("photo.jpg", "image/jpeg", 100),
                PiperDataTransferProgressState.Item("clip.mp4", "video/mp4", -1)
            ),
            -1
        )

        PiperDataTransferProgressState.update(session, 0, 60, 60, -1)
        val first = requireNotNull(PiperDataTransferProgressState.snapshot(session))
        assertEquals(PiperDataTransferProgressState.ItemState.ACTIVE, first.items[0].state)
        assertEquals(60L, first.items[0].transferred)

        PiperDataTransferProgressState.update(session, 1, 25, 125, -1)
        val second = requireNotNull(PiperDataTransferProgressState.snapshot(session))
        assertEquals(PiperDataTransferProgressState.ItemState.COMPLETE, second.items[0].state)
        assertEquals(100L, second.items[0].transferred)
        assertEquals(PiperDataTransferProgressState.ItemState.ACTIVE, second.items[1].state)

        PiperDataTransferProgressState.complete(session, 140, -1)
        val done = requireNotNull(PiperDataTransferProgressState.snapshot(session))
        assertTrue(done.complete)
        assertEquals(25L, done.items[1].transferred)
        assertTrue(done.items.all { it.state == PiperDataTransferProgressState.ItemState.COMPLETE })
    }
}
