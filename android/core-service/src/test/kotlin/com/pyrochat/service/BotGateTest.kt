package com.pyrochat.service

import com.pyrochat.service.Live.waitUntil
import com.pyrochat.store.model.ChatMessage.Direction
import com.pyrochat.store.model.ChatMessage.Kind
import com.pyrochat.store.model.ChatMessage.Status
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GATE A — port of `ios/Tests/BotIntegrationTests.swift`: pyrobot (`bot/index.mjs`, a real
 * libsignal Node client) must be running against the dev relay on 127.0.0.1:7444. Proves the
 * Android ChatService can add the bot, get real receipts (DELIVERED → READ), receive the tour
 * including a self-destructing message, and be echoed.
 */
class BotGateTest {
    @BeforeEach
    fun requireHarness() {
        Live.requireRelay()
        Assumptions.assumeTrue(
            Live.pyrobotRunning(),
            "SKIPPED: pyrobot is not running — `RELAY_URL=ws://127.0.0.1:7444/ws BOT_USERNAME=pyrobot node bot/index.mjs`",
        )
    }

    @Test
    fun `pyrobot tours a new Android user`(): Unit = runBlocking {
        val me = Live.makeService("bt")
        try {
            me.svc.register(me.username)
            me.svc.addContact("pyrobot")
            val bot = me.svc.contacts.value.first { it.name == "pyrobot" }

            me.svc.sendText("hi there", bot)
            // the bot marks our message delivered then READ (it sends real receipts)
            assertTrue(waitUntil(15_000) {
                me.svc.messages.value[bot.hash]?.firstOrNull { it.direction == Direction.OUTGOING }?.status == Status.READ
            }, "bot receipts must upgrade our message to READ")
            println("GATE A: outgoing 'hi there' → ${me.svc.messages.value[bot.hash]!!.first { it.direction == Direction.OUTGOING }.status}")

            // the welcome tour: 5 messages, one of them self-destructing
            assertTrue(waitUntil(30_000) {
                (me.svc.messages.value[bot.hash] ?: emptyList()).count { it.direction == Direction.INCOMING && it.kind == Kind.TEXT } >= 5
            }, "tour must arrive")
            val incoming = (me.svc.messages.value[bot.hash] ?: emptyList()).filter { it.direction == Direction.INCOMING }
            assertTrue(incoming.any { it.burnAfter == 20.0 }, "tour includes a 20 s burn message")
            assertTrue(incoming.any { it.text.contains("pyrobot") }, "bot introduces itself")
            assertEquals("pyrobot", bot.name)
            println("GATE A: tour arrived — ${incoming.size} incoming rows, burn row burnAfter=${incoming.first { it.burnAfter != null }.burnAfter}")

            // echo mode after the tour
            me.svc.sendText("echo me", bot)
            assertTrue(waitUntil(15_000) {
                (me.svc.messages.value[bot.hash] ?: emptyList()).any { it.direction == Direction.INCOMING && it.text == "you said: echo me" }
            }, "bot echoes after the tour")
            println("GATE A: echo received — 'you said: echo me'")
            me.svc.goOffline()
        } finally {
            me.close()
        }
    }
}
