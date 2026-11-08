package com.pyrochat.service

import com.pyrochat.service.Live.waitUntil
import com.pyrochat.store.model.ChatMessage
import com.pyrochat.store.model.ChatMessage.Direction
import com.pyrochat.store.model.ChatMessage.Kind
import com.pyrochat.store.model.ChatMessage.Status
import com.pyrochat.store.model.Contact
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of `ios/Tests/UXTests.swift`: drafts, contact management, single-message delete, typing, first-contact naming. */
class UxTest {
    private fun row(id: String, kind: Kind = Kind.TEXT, text: String = "hi", attachmentId: String? = null) = ChatMessage(
        id = id, direction = Direction.INCOMING, kind = kind, text = text, timestamp = Instant.now(), status = Status.READ,
        attachmentId = attachmentId, fileName = attachmentId?.let { "x" }, fileMime = attachmentId?.let { "text/plain" },
        fileBytes = attachmentId?.let { 5 },
    )

    @Test
    fun `drafts persist and clear`(): Unit = runBlocking {
        val u = Live.makeService()
        try {
            val t = "a".repeat(64)
            u.svc.setDraft("half-typed thought", t)
            assertEquals("half-typed thought", u.svc.draft(t))
            assertEquals("half-typed thought", u.svc.store.blob.drafts?.get(t))
            u.svc.setDraft("   ", t) // whitespace-only clears
            assertEquals("", u.svc.draft(t))
            assertNull(u.svc.store.blob.drafts?.get(t))
            assertNull(u.svc.store.blob.drafts, "drafts becomes nil (key omitted) when the map empties")
        } finally {
            u.close()
        }
    }

    @Test
    fun `rename and delete contact`(): Unit = runBlocking {
        val hash = "b".repeat(64)
        val attId = UUID.randomUUID().toString().lowercase()
        val u = Live.makeService {
            it.copy(
                contacts = listOf(Contact(hash, "oldname", Instant.now())),
                messages = mapOf(hash to listOf(row("m1"))),
                drafts = mapOf(hash to "wip"),
            )
        }
        try {
            u.svc.renameContact(hash, "  newname  ")
            assertEquals("newname", u.svc.contacts.value.first().name)
            u.svc.renameContact(hash, "   ") // blank refused
            assertEquals("newname", u.svc.contacts.value.first().name)

            // delete: contact + messages + attachment file + draft all go
            u.svc.store.writeAttachment(attId, "bytes".toByteArray())
            u.svc.store.update { it.copy(messages = mapOf(hash to listOf(row("m1"), row("m2", Kind.FILE, "[FILE] x", attId)))) }
            // the service holds its own copy like iOS's @Published messages — rebuild it from the store
            val svc2 = ChatService(u.svc.store)
            svc2.deleteContact(hash)
            assertTrue(svc2.contacts.value.isEmpty())
            assertNull(svc2.messages.value[hash])
            assertNull(u.svc.store.readAttachment(attId), "attachment purged")
            assertEquals("", svc2.draft(hash), "draft purged")
            assertNull(u.svc.store.blob.messages[hash])
            svc2.close()
        } finally {
            u.close()
        }
    }

    @Test
    fun `delete single message purges its attachment`(): Unit = runBlocking {
        val t = "c".repeat(64)
        val attId = UUID.randomUUID().toString().lowercase()
        val u = Live.makeService()
        try {
            u.svc.store.writeAttachment(attId, "img".toByteArray())
            u.svc.store.update {
                it.copy(messages = mapOf(t to listOf(row("keep", text = "stay"), row("kill", Kind.IMAGE, "[IMG]", attId))))
            }
            val svc2 = ChatService(u.svc.store)
            svc2.deleteMessage("kill", t)
            assertEquals(listOf("keep"), svc2.messages.value[t]?.map { it.id })
            assertNull(u.svc.store.readAttachment(attId))
            assertEquals(listOf("keep"), u.svc.store.blob.messages[t]?.map { it.id })
            svc2.close()
        } finally {
            u.close()
        }
    }

    @Test
    fun `typing indicator reaches the peer and expires`(): Unit = runBlocking {
        Live.requireRelay()
        val alice = Live.makeService("al")
        val bob = Live.makeService("bo")
        try {
            alice.svc.register(alice.username); bob.svc.register(bob.username)
            bob.svc.addContact(alice.username)
            // alice needs the contact so the typing control passes the stranger guard; bob established the session
            alice.svc.addContact(bob.username)
            bob.svc.sendText("open the channel", bob.svc.contacts.value.first())
            assertTrue(waitUntil { alice.svc.messages.value[bob.hash]?.isNotEmpty() == true })
            // With sealed sender a typing signal is never sent to someone who has not answered yet
            // (docs/SEALED-SENDER.md §5.2: it would spend their first-contact allowance). DELIVERED
            // means alice's phone answered; without sealed sender the wait changes nothing.
            waitUntil { bob.svc.messages.value[alice.hash]?.firstOrNull()?.status == Status.DELIVERED }

            bob.svc.noteComposerActivity(alice.hash)
            assertTrue(waitUntil { alice.svc.typingPeers.value.containsKey(bob.hash) }, "typing signal must reach the peer")
            assertTrue(waitUntil(10_000) { !alice.svc.typingPeers.value.containsKey(bob.hash) }, "typing row must expire after ~6s of silence")
            alice.svc.goOffline(); bob.svc.goOffline()
        } finally {
            alice.close(); bob.close()
        }
    }

    /** First contact must show the sender's real username, not anon-… */
    @Test
    fun `first contact shows the real username`(): Unit = runBlocking {
        Live.requireRelay()
        val alice = Live.makeService("al")
        val bob = Live.makeService("bo")
        try {
            alice.svc.register(alice.username); bob.svc.register(bob.username)
            alice.svc.addContact(bob.username)
            alice.svc.sendText("hi, it's me", alice.svc.contacts.value.first())
            assertTrue(waitUntil { bob.svc.messages.value[alice.hash]?.isNotEmpty() == true })
            val contact = bob.svc.contacts.value.firstOrNull { it.hash == alice.hash }
            assertEquals(alice.username, contact?.name, "receiver must see the sender's chosen username")
            assertFalse(contact?.name?.startsWith("anon-") ?: true)
            alice.svc.goOffline(); bob.svc.goOffline()
        } finally {
            alice.close(); bob.close()
        }
    }

    @Test
    fun `display name sanitization`() {
        assertEquals("ghost", ChatService.sanitizeDisplayName("ghost"))
        assertEquals("ghost", ChatService.sanitizeDisplayName("<gh ost>💀"))
        assertNull(ChatService.sanitizeDisplayName("ab"), "too short falls back to anon")
        assertNull(ChatService.sanitizeDisplayName(null))
        assertEquals(24, ChatService.sanitizeDisplayName("x".repeat(99))?.length)
        assertEquals("pyrobot", ChatService.sanitizeDisplayName("pyrobot"))
        // grapheme clusters count as one character, like Swift `String.count`
        assertEquals(24, ChatService.sanitizeDisplayName("é".repeat(30))!!.codePoints().filter { it == 'e'.code }.count().toInt())
    }

    @Test
    fun `username validation mirrors the registration charset`() {
        assertTrue(ChatService.isValidUsername("zee_01"))
        assertFalse(ChatService.isValidUsername("zee 01"))
        assertFalse(ChatService.isValidUsername("al"))
        assertFalse(ChatService.isValidUsername("x".repeat(25)))
        assertFalse(ChatService.isValidUsername("alice​"))
        assertFalse(ChatService.isValidUsername("alice\nbob"))
        assertFalse(ChatService.isValidUsername("a‮lice"))
        assertTrue(ChatService.isValidUsername("四四四"), "CJK numerals are letters (Alphabetic) on both platforms")
        assertTrue(ChatService.isValidUsername("Ⅻ١٢"))
    }
}
