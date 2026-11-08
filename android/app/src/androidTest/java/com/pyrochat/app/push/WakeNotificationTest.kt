package com.pyrochat.app.push

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.google.firebase.messaging.RemoteMessage
import com.pyrochat.app.R
import com.pyrochat.app.backend.PushRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The notification side on the emulator: channel, one visible zero-content entry, coalescing,
 * clear-on-foreground, and the FCM service driven with a synthetic `RemoteMessage` (no Firebase
 * project needed — `onMessageReceived` is plain code over the data map).
 */
@RunWith(AndroidJUnit4::class)
class WakeNotificationTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager = context.getSystemService(NotificationManager::class.java)

    @Before fun clean() {
        PushRegistration.clearNotifications()
        AppPresence.foreground = false
        AppPresence.unbind()
        assertEquals(0, awaitActive(0).size)
    }

    /** `notify`/`cancel` round-trip through system_server: poll (≤ 3 s) until the app's active set has [n] entries. */
    private fun awaitActive(n: Int): Array<android.service.notification.StatusBarNotification> {
        val deadline = System.currentTimeMillis() + 3_000
        while (true) {
            val a = nm.activeNotifications
            if (a.size == n || System.currentTimeMillis() > deadline) return a
            Thread.sleep(50)
        }
    }

    @Test fun channelIsHighWithDefaultSoundAndVibration() {
        val ch = WakeNotifier.ensureChannel(context)
        assertEquals("transmissions", ch.id)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, ch.importance)
        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, ch.sound)
        assertTrue(ch.shouldVibrate())
        assertTrue(ch.canShowBadge())
        assertEquals(context.getString(R.string.push_channel_name), ch.name)
        assertEquals(ch.id, nm.getNotificationChannel(WakeNotifier.CHANNEL_ID).id)
    }

    @Test fun wakePostsOneZeroContentNotification() {
        assertEquals(WakeHandler.Outcome.POSTED, WakeHandler.handle(context, mapOf("kind" to "wake"), now = 10_000))
        val active = awaitActive(1)
        assertEquals(1, active.size)
        val n = active[0]
        assertEquals(WakeNotifier.NOTIFICATION_ID, n.id)
        assertEquals(WakeNotifier.CHANNEL_ID, n.notification.channelId)
        assertEquals("incoming transmission", n.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        assertNull(n.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT))
        assertEquals(1, n.notification.number) // locked store: a wake proves ≥ 1
        assertTrue(n.notification.contentIntent != null)
        assertTrue(WakeNotifier.isShowing(context))
    }

    @Test fun burstCoalescesIntoOneEntryThenReAlerts() {
        assertEquals(WakeHandler.Outcome.POSTED, WakeHandler.handle(context, mapOf("kind" to "wake"), now = 10_000))
        assertEquals(WakeHandler.Outcome.COALESCED, WakeHandler.handle(context, mapOf("kind" to "wake"), now = 10_500))
        assertEquals(WakeHandler.Outcome.COALESCED, WakeHandler.handle(context, mapOf("kind" to "wake"), now = 12_000))
        assertEquals(1, awaitActive(1).size)
        assertEquals(WakeHandler.Outcome.POSTED, WakeHandler.handle(context, mapOf("kind" to "wake"), now = 10_000 + WakeCoalescing.QUIET_WINDOW_MS))
        Thread.sleep(200)
        assertEquals(1, awaitActive(1).size) // still one entry
    }

    @Test fun liveAppSuppressesAndForegroundClears() {
        AppPresence.foreground = true
        AppPresence.bind(online = { true }, totalUnread = { 3 })
        assertEquals(WakeHandler.Outcome.LIVE, WakeHandler.handle(context, mapOf("kind" to "wake")))
        Thread.sleep(200)
        assertEquals(0, awaitActive(0).size)

        AppPresence.foreground = false // backgrounded, link still up: a wake means the socket is gone
        assertEquals(WakeHandler.Outcome.POSTED, WakeHandler.handle(context, mapOf("kind" to "wake"), now = 1))
        assertEquals(3, awaitActive(1)[0].notification.number) // badge = total unread

        PushRegistration.syncBadge(5) // background transition refreshes the number silently
        Thread.sleep(300)
        assertEquals(5, awaitActive(1)[0].notification.number)

        PushRegistration.clearNotifications() // RealBackend.noteForeground
        assertEquals(0, awaitActive(0).size)
        assertFalse(WakeNotifier.isShowing(context))
        // the shade was cleared: the very next wake alerts again regardless of the quiet window
        assertEquals(WakeHandler.Outcome.POSTED, WakeHandler.handle(context, mapOf("kind" to "wake"), now = 2))
    }

    /**
     * docs/DECOY-ACCOUNT.md §6.6 — "nothing may render for a slot that is not the open one",
     * asserted on the shade rather than on the rule: after a wake that belongs to another slot,
     * the app has posted nothing at all.
     */
    @Test fun aWakeForAnotherSlotRendersNothing() {
        // a decoy is open; it never asked the relay to wake it, so this wake is not for it
        AppPresence.bind(online = { false }, totalUnread = { 0 }, wakesThisSlot = { false })
        assertEquals(WakeHandler.Outcome.OTHER_SLOT, WakeHandler.handle(context, mapOf("kind" to "wake"), now = 20_000))
        assertEquals(0, awaitActive(0).size)

        // the account that DID register for wake push: the banner is exactly what it always was
        AppPresence.bind(online = { false }, totalUnread = { 0 }, wakesThisSlot = { true })
        assertEquals(WakeHandler.Outcome.POSTED, WakeHandler.handle(context, mapOf("kind" to "wake"), now = 40_000))
        assertEquals(1, awaitActive(1).size)
    }

    /**
     * docs/DECOY-ACCOUNT.md §6.6 — the badge is the one thing a content-free push carries, and it
     * is computed from ONE slot's unread count. It must not keep rendering once that slot closes:
     * a decoy with an empty inbox wearing the real account's number is the leak, and there are no
     * local notifications for it to hide behind.
     */
    @Test fun aBadgeFromOneSlotDoesNotSurviveThatSlotClosing() {
        AppPresence.bind(online = { false }, totalUnread = { 5 }, wakesThisSlot = { true })
        assertEquals(WakeHandler.Outcome.POSTED, WakeHandler.handle(context, mapOf("kind" to "wake"), now = 70_000))
        assertEquals(5, awaitActive(1).single().notification.number)

        // the session ends — what `RealBackend.lock()` does
        PushRegistration.clearNotifications()
        AppPresence.unbind()
        assertEquals("the number belonged to a slot that is no longer open", 0, awaitActive(0).size)

        // the next session's own wake carries the next session's own count, and nothing before it
        AppPresence.bind(online = { false }, totalUnread = { 0 }, wakesThisSlot = { true })
        assertEquals(WakeHandler.Outcome.POSTED, WakeHandler.handle(context, mapOf("kind" to "wake"), now = 90_000))
        assertEquals("a wake proves one message waits, never five", 1, awaitActive(1).single().notification.number)
    }

    /** §6.6 again, locked: no slot is open, so the banner says what it has always said. */
    @Test fun aWakeIntoALockedAppStillSurfaces() {
        AppPresence.unbind()
        assertEquals(WakeHandler.Outcome.POSTED, WakeHandler.handle(context, mapOf("kind" to "wake"), now = 60_000))
        assertEquals(1, awaitActive(1).size)
    }

    @Test fun foreignPayloadsAreIgnored() {
        assertEquals(WakeHandler.Outcome.IGNORED, WakeHandler.handle(context, emptyMap()))
        assertEquals(WakeHandler.Outcome.IGNORED, WakeHandler.handle(context, mapOf("kind" to "typing")))
        assertEquals(WakeHandler.Outcome.IGNORED, WakeHandler.handle(context, mapOf("title" to "x", "body" to "y")))
        Thread.sleep(200)
        assertEquals(0, awaitActive(0).size)
    }

    @Test fun fcmServiceDispatchesASyntheticWake() {
        val service = PyroFcmService().apply { attachForTest(context) }
        val wake = RemoteMessage.Builder("123456789012@fcm.googleapis.com").addData("kind", "wake").setMessageId("m1").build()
        service.onMessageReceived(wake)
        val active = awaitActive(1)
        assertEquals(1, active.size)
        assertEquals("incoming transmission", active[0].notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())

        val other = RemoteMessage.Builder("123456789012@fcm.googleapis.com").addData("kind", "nope").setMessageId("m2").build()
        service.onMessageReceived(other)
        Thread.sleep(200)
        assertEquals(1, awaitActive(1).size)
    }
}
