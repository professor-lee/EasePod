package app.easepod

import androidx.lifecycle.ViewModelStore
import app.easepod.plugins.InstallCandidate
import app.easepod.ui.SystemEffect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingExportTest {
    @Test fun detachedInstallReachesReplacementHostExactlyOnce() {
        val retained = PendingExport()
        val effect = SystemEffect.Install(InstallCandidate("apk", "Example", "1", "/private/example.apk"))
        val received = mutableListOf<SystemEffect>()
        retained.attach({ it == 17L }) { received += it }
        retained.detach()
        retained.dispatch(effect, 17L)
        assertTrue(received.isEmpty())
        retained.attach({ it == 17L }) { received += it }
        assertEquals(listOf(effect), received)
        retained.detach()
        retained.attach({ it == 17L }) { received += it }
        assertEquals(1, received.size)
    }

    @Test fun staleOrClearedInstallNeverReplays() {
        val effect = SystemEffect.Install(InstallCandidate("apk", "Example", "1", "/private/example.apk"))
        val received = mutableListOf<SystemEffect>()
        for (clear in listOf(false, true)) {
            val retained = PendingExport()
            retained.dispatch(effect, 2L)
            if (clear) retained.clear()
            retained.attach({ it == 3L }) { received += it }
            retained.detach()
            retained.attach({ it == 2L }) { received += it }
        }
        assertTrue(received.isEmpty())
    }

    @Test fun queuedInstallReplacingExportErasesExportBytes() {
        val retained = PendingExport()
        val bytes = byteArrayOf(4, 2)
        val effect = SystemEffect.Install(InstallCandidate("apk", "Example", "1", "/private/example.apk"))
        retained.dispatch(SystemEffect.ExportBackup(bytes), 4L)
        retained.dispatch(effect, 4L)
        assertArrayEquals(ByteArray(2), bytes)
        val received = mutableListOf<SystemEffect>()
        retained.attach({ it == 4L }) { received += it }
        assertEquals(listOf(effect), received)
    }

    @Test fun detachedExportReachesReplacementHostOnceForItsGeneration() {
        for (effect in listOf(SystemEffect.ExportBackup(byteArrayOf(1, 2)), SystemEffect.ExportDiagnostics(byteArrayOf(3, 4)))) {
            val retained = PendingExport()
            val oldHost = mutableListOf<SystemEffect>()
            val newHost = mutableListOf<SystemEffect>()
            retained.attach({ it == 17L }) { oldHost += it }
            retained.detach()
            retained.dispatch(effect, 17L)
            assertTrue(oldHost.isEmpty())
            retained.attach({ it == 17L }) { newHost += it }
            assertEquals(1, newHost.size)
            assertSame(effect, newHost.single())
            retained.detach()
            retained.attach({ it == 17L }) { newHost += it }
            assertEquals(1, newHost.size)
        }
    }

    @Test fun staleExportIsErasedAndCannotReplayAfterAnotherAttachment() {
        val retained = PendingExport()
        val bytes = byteArrayOf(9, 8, 7)
        val received = mutableListOf<SystemEffect>()
        retained.dispatch(SystemEffect.ExportBackup(bytes), 2L)
        retained.attach({ it == 3L }) { received += it }
        assertArrayEquals(ByteArray(3), bytes)
        retained.detach()
        retained.attach({ it == 2L }) { received += it }
        assertTrue(received.isEmpty())
    }

    @Test fun clearErasesPickerAndQueuedBytesAndRemovesPendingDelivery() {
        val retained = PendingExport()
        val picker = byteArrayOf(2, 3)
        val queued = byteArrayOf(5, 6, 7)
        val received = mutableListOf<SystemEffect>()
        retained.bytes = picker
        retained.dispatch(SystemEffect.ExportDiagnostics(queued), 3L)
        retained.clear()
        assertNull(retained.bytes)
        assertArrayEquals(ByteArray(2), picker)
        assertArrayEquals(ByteArray(3), queued)
        retained.attach({ true }) { received += it }
        assertTrue(received.isEmpty())
        retained.clear()
    }

    @Test fun viewModelRemovalErasesPendingDataAndDetachesTheHost() {
        val retained = PendingExport()
        val store = ViewModelStore()
        store.put("export", retained)
        val picker = byteArrayOf(1, 2, 3)
        val queued = byteArrayOf(4, 5)
        retained.bytes = picker
        retained.dispatch(SystemEffect.ExportBackup(queued), 6L)
        store.clear()
        assertNull(retained.bytes)
        assertArrayEquals(ByteArray(3), picker)
        assertArrayEquals(ByteArray(2), queued)
        val received = mutableListOf<SystemEffect>()
        retained.attach({ true }) { received += it }
        assertTrue(received.isEmpty())

        val attached = PendingExport()
        store.put("attached", attached)
        attached.attach({ true }) { received += it }
        store.clear()
        attached.dispatch(SystemEffect.PickFolder, 7L)
        assertTrue(received.isEmpty())
    }

    @Test fun detachedNonExportEffectsAreNotReplayedOrAllowedToReplaceAnExport() {
        val retained = PendingExport()
        val received = mutableListOf<SystemEffect>()
        val effect = SystemEffect.ExportDiagnostics(byteArrayOf(4, 2))
        retained.dispatch(effect, 8L)
        for (systemEffect in listOf(SystemEffect.PickFolder, SystemEffect.PickPlugin, SystemEffect.ImportBackup,
            SystemEffect.Unlock, SystemEffect.OpenExternal("https://example.com"))) {
            retained.dispatch(systemEffect, 8L)
        }
        retained.attach({ it == 8L }) { received += it }
        assertEquals(1, received.size)
        assertSame(effect, received.single())
    }

    @Test fun replacingQueuedExportErasesOnlyThePreviousPayload() {
        val retained = PendingExport()
        val discarded = byteArrayOf(1, 2)
        val current = byteArrayOf(3, 4)
        retained.dispatch(SystemEffect.ExportBackup(discarded), 1L)
        retained.dispatch(SystemEffect.ExportDiagnostics(current), 2L)
        assertArrayEquals(ByteArray(2), discarded)
        assertArrayEquals(byteArrayOf(3, 4), current)
        val received = mutableListOf<SystemEffect>()
        retained.attach({ it == 2L }) { received += it }
        assertSame(current, (received.single() as SystemEffect.ExportDiagnostics).bytes)
    }

    @Test fun attachedEffectsAreImmediateAndDetachStopsUsingTheOldHost() {
        val retained = PendingExport()
        val received = mutableListOf<SystemEffect>()
        retained.attach({ true }) { received += it }
        retained.dispatch(SystemEffect.PickFolder, 1L)
        assertEquals(listOf(SystemEffect.PickFolder), received)
        retained.detach()
        retained.dispatch(SystemEffect.PickPlugin, 1L)
        retained.attach({ true }) { received += it }
        assertEquals(listOf(SystemEffect.PickFolder), received)
    }
}
