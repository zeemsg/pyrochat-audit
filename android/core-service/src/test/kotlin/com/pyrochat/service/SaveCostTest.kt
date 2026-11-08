package com.pyrochat.service

import com.pyrochat.store.SecureStore
import com.pyrochat.store.StoreContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/DECOY-ACCOUNT.md §1, "What a save costs, measured" — the measurement itself, kept runnable.
 *
 * The number in §1 ("typing one sentence produced 19 saves") was taken by hand once and would
 * otherwise rot. Here it is the test: the same keystroke trace is replayed twice against a real
 * container on disk — once with [SecureStore.measureWithoutLazyLane] on, which is exactly how the
 * store behaved before saves were split by durability, and once with it off, which is how it
 * behaves now. Both numbers are printed, so `./gradlew :core-service:test --tests '*SaveCostTest*'
 * -i` is the before/after, and a regression in the split brings the before figure back as a
 * failure rather than as a bigger flash bill nobody notices.
 *
 * Every save writes a whole [StoreContainer.HALF_BYTES], because the plaintext is padded to fill
 * its half and AES-GCM ciphertext is exactly as long as its plaintext. That is not avoidable
 * inside this format; how OFTEN it happens is entirely a question of the call sites.
 */
class SaveCostTest {

    /**
     * The "chatty day" §1 priced at roughly 2 GB, written down so the figure is reproducible: a
     * person who types and sends [SENT_PER_DAY] messages and is part of [MESSAGES_PER_DAY]
     * messages in all. Change these and the day changes with them; nothing else depends on them.
     */
    private val SENT_PER_DAY = 40
    private val MESSAGES_PER_DAY = 100

    /**
     * One sentence, at a speed a person actually types: 47 characters at 10 a second. Against the
     * 250 ms debounce that is ~19 windows — the trace that produced §1's 19 saves.
     */
    private val sentence = "meet me at the usual place tomorrow at seven pm"
    private val keystrokeMs = 100L

    private suspend fun typeOneSentence(u: TestUser, thread: String) {
        for (i in 1..sentence.length) {
            u.svc.setDraft(sentence.take(i), thread)
            delay(keystrokeMs)
        }
    }

    private fun mib(saves: Long): String = "%.1f MiB".format(saves * StoreContainer.HALF_BYTES / 1048576.0)

    @Test
    fun `typing one sentence`(): Unit = runBlocking {
        val thread = "a".repeat(64)

        // ---- before: every keystroke is a durable save, which is what the app used to do --------
        var before = 0L
        var beforeBytes = 0L
        Live.makeService("sc").let { u ->
            try {
                u.svc.store.measureWithoutLazyLane = true
                settleWrites(u)
                val base = u.svc.store.saveCount
                val baseBytes = u.svc.store.bytesWritten
                typeOneSentence(u, thread)
                settleWrites(u)
                before = u.svc.store.saveCount - base
                beforeBytes = u.svc.store.bytesWritten - baseBytes
            } finally {
                u.close()
            }
        }

        // ---- after: the draft is lazy, so the keystrokes cost nothing at all --------------------
        var duringTyping = 0L
        var afterBlur = 0L
        Live.makeService("sc").let { u ->
            try {
                settleWrites(u)
                val base = u.svc.store.saveCount
                typeOneSentence(u, thread)
                settleWrites(u)
                duringTyping = u.svc.store.saveCount - base
                // the composer loses focus / the screen is left / the app backgrounds
                u.svc.persistDeferred()
                afterBlur = u.svc.store.saveCount - base
                assertEquals(sentence, u.svc.draft(thread), "the draft is intact in memory the whole time")
                assertEquals(sentence, u.reopen().drafts?.get(thread), "and on disk once something flushes")
            } finally {
                u.close()
            }
        }

        println(
            """
            |
            |── docs/DECOY-ACCOUNT.md §1: what typing one sentence costs ──
            |  sentence                 ${sentence.length} characters at ${1000 / keystrokeMs} a second
            |  BEFORE (every save durable)
            |    saves                  $before
            |    written                ${mib(before)}   (${beforeBytes / 1048576.0} MiB actual)
            |  AFTER (drafts lazy)
            |    saves while typing     $duringTyping
            |    saves incl. the flush  $afterBlur
            |    written                ${mib(afterBlur)}
            |
            """.trimMargin(),
        )

        assertTrue(before >= 10, "the before figure should be the debounce running flat out, was $before")
        assertEquals(0L, duringTyping, "a keystroke must never reach the store")
        assertEquals(1L, afterBlur, "and the whole sentence costs exactly one save when something flushes")
    }

    @Test
    fun `a twenty-message exchange`(): Unit = runBlocking {
        Live.requireRelay()
        val results = LinkedHashMap<String, Pair<Long, Long>>()   // label -> (alice saves, bob saves)
        for (lazyLane in listOf(false, true)) {
            val alice = Live.makeService("sa") { it.copy(settings = it.settings.copy(sendReadReceipts = true)) }
            val bob = Live.makeService("sb") { it.copy(settings = it.settings.copy(sendReadReceipts = true)) }
            try {
                alice.svc.store.measureWithoutLazyLane = !lazyLane
                bob.svc.store.measureWithoutLazyLane = !lazyLane
                alice.svc.register(alice.username)
                bob.svc.register(bob.username)
                alice.svc.addContact(bob.username)
                settleWrites(alice)
                settleWrites(bob)
                val aliceBase = alice.svc.store.saveCount
                val bobBase = bob.svc.store.saveCount

                // ten each way, read on arrival — the ordinary shape of a conversation
                repeat(10) { i ->
                    alice.svc.sendText("from alice $i", alice.svc.contacts.value.first())
                    Live.waitUntil { bob.svc.messages.value[alice.hash]?.size == i + 1 }
                    bob.svc.messages.value[alice.hash]?.lastOrNull()?.let { bob.svc.noteRowVisible(it.id, alice.hash) }
                    bob.svc.sendText("from bob $i", bob.svc.contacts.value.first())
                    Live.waitUntil { alice.svc.messages.value[bob.hash]?.size == (i + 1) * 2 }
                    alice.svc.messages.value[bob.hash]?.lastOrNull()?.let { alice.svc.noteRowVisible(it.id, bob.hash) }
                }
                settleWrites(alice)
                settleWrites(bob)
                results[if (lazyLane) "after" else "before"] =
                    (alice.svc.store.saveCount - aliceBase) to (bob.svc.store.saveCount - bobBase)
            } finally {
                alice.close(); bob.close()
            }
        }
        val (beforeA, beforeB) = results.getValue("before")
        val (afterA, afterB) = results.getValue("after")
        val before = maxOf(beforeA, beforeB)
        val after = maxOf(afterA, afterB)

        // and the day, from the two primitives rather than from anyone's arithmetic
        val typingBefore = measureTyping(lazyLane = false)
        val typingAfter = measureTyping(lazyLane = true)
        val dayBefore = SENT_PER_DAY * typingBefore + (MESSAGES_PER_DAY / 20) * before
        val dayAfter = SENT_PER_DAY * typingAfter + (MESSAGES_PER_DAY / 20) * after

        println(
            """
            |
            |── docs/DECOY-ACCOUNT.md §1: what a twenty-message exchange costs ──
            |  BEFORE   $before saves per device   ${mib(before)}
            |  AFTER    $after saves per device   ${mib(after)}
            |
            |── and a chatty day ──
            |  the day, as modelled: $SENT_PER_DAY messages typed and sent, $MESSAGES_PER_DAY exchanged in all
            |  BEFORE   $dayBefore saves   ${gb(dayBefore)}
            |  AFTER    $dayAfter saves   ${gb(dayAfter)}
            |  of which typing          BEFORE ${SENT_PER_DAY * typingBefore} saves   AFTER ${SENT_PER_DAY * typingAfter} saves
            |
            """.trimMargin(),
        )
        assertTrue(after <= before, "the split must not cost more saves: before $before, after $after")
        assertTrue(dayAfter * 5 < dayBefore, "§1 asks for well over an order of magnitude: $dayBefore -> $dayAfter")
    }

    /**
     * The typing primitive on its own, so the day can be rolled up from measurements instead of
     * from arithmetic in a document.
     */
    private suspend fun measureTyping(lazyLane: Boolean): Long {
        val u = Live.makeService("sc")
        return try {
            u.svc.store.measureWithoutLazyLane = !lazyLane
            settleWrites(u)
            val base = u.svc.store.saveCount
            typeOneSentence(u, "b".repeat(64))
            settleWrites(u)
            u.svc.persistDeferred()                 // the composer loses focus
            u.svc.store.saveCount - base
        } finally {
            u.close()
        }
    }

    private fun gb(saves: Long): String = "%.2f GB".format(saves * StoreContainer.HALF_BYTES / 1e9)

    /** Let the 250 ms debounce fire and the write land. */
    private suspend fun settleWrites(u: TestUser) {
        delay(SecureStore.DEBOUNCE_MS + 300)
    }
}
