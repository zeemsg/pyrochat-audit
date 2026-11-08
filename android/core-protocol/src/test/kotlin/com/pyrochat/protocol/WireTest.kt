package com.pyrochat.protocol

import kotlinx.serialization.SerializationException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JSON shapes byte-for-byte as the iOS `Wire.Out`/`Wire.In` structs and the Node clients put them on the wire. */
class WireTest {
    private val hash = "a".repeat(64)

    @Test
    fun registerFrameHasExactFieldNamesAndOmitsMissingSig() {
        val frame = Wire.Out.Register(
            user = hash,
            authPub = "QVVUSA==",
            regId = 123,
            identityKey = "SUQ=",
            signedPreKey = Wire.PreKeyJson(1, "U1BL", "U1BLU0lH"),
            kyberPreKey = Wire.PreKeyJson(1, "S1lC", "S1lCU0lH"),
            oneTimePreKeys = listOf(Wire.PreKeyJson(1, "T1RLMQ=="), Wire.PreKeyJson(2, "T1RLMg==")),
        )
        assertEquals(
            """{"t":"register","user":"$hash","auth_pub":"QVVUSA==","reg_id":123,"identity_key":"SUQ=",""" +
                """"signed_prekey":{"id":1,"pub":"U1BL","sig":"U1BLU0lH"},"kyber_prekey":{"id":1,"pub":"S1lC","sig":"S1lCU0lH"},""" +
                """"one_time_prekeys":[{"id":1,"pub":"T1RLMQ=="},{"id":2,"pub":"T1RLMg=="}]}""",
            Wire.encode(frame),
        )
    }

    /** GROUPS.md §2: the `group` object and the roster fields ride inside the E2E payload; unknown fields are ignored. */
    @Test
    fun groupPayloadFieldsAndControls() {
        val gid = "0f9d2c44-1111-4222-8333-444455556666"
        val bob = "b".repeat(64)
        val carol = "c".repeat(64)
        val text = Wire.Payload(kind = "text", text = "hi all", senderName = "alice", burnAfter = 20.0, group = GroupRef(gid, 3))
        assertEquals("""{"kind":"text","text":"hi all","senderName":"alice","burnAfter":20.0,"group":{"id":"$gid","epoch":3}}""", String(text.encode(), Charsets.UTF_8))
        val create = Wire.Payload(
            kind = "control", control = GroupControl.CREATE, group = GroupRef(gid, 0), name = "ops", creator = hash,
            members = listOf(GroupMember(hash, "alice", 780000000.0), GroupMember(bob, "bob", 780000000.0)),
            policy = "burn_remove", createdAt = 780000000.0,
        )
        assertEquals(
            """{"kind":"control","control":"group_create","group":{"id":"$gid","epoch":0},"name":"ops","creator":"$hash",""" +
                """"members":[{"hash":"$hash","name":"alice","joinedAt":7.8E8},{"hash":"$bob","name":"bob","joinedAt":7.8E8}],""" +
                """"policy":"burn_remove","createdAt":7.8E8}""",
            String(create.encode(), Charsets.UTF_8),
        )
        assertEquals(create, Wire.Payload.decode(create.encode()))
        // burnSeconds is the GROUP default, separate from a row's burnAfter; `member` for add/remove
        val add = Wire.Payload(kind = "control", control = GroupControl.ADD, group = GroupRef(gid, 4), member = GroupMember(carol, "carol", 780000100.5))
        assertEquals("""{"kind":"control","control":"group_add","group":{"id":"$gid","epoch":4},"member":{"hash":"$carol","name":"carol","joinedAt":7.800001005E8}}""", String(add.encode(), Charsets.UTF_8))
        val remove = Wire.Payload.decode("""{"kind":"control","control":"group_remove","group":{"id":"$gid","epoch":5},"member":{"hash":"$carol"}}""".toByteArray())
        assertEquals(carol, remove.member!!.hash)
        assertNull(remove.member!!.name)
        val policy = Wire.Payload.decode("""{"kind":"control","control":"group_policy","group":{"id":"$gid","epoch":6},"policy":"alert","burnSeconds":60}""".toByteArray())
        assertEquals(60.0, policy.burnSeconds); assertEquals("alert", policy.policy); assertEquals(6, policy.group!!.epoch)
        // a payload from a newer client: unknown group fields and unknown top-level fields are ignored; Swift's integral dates decode
        val future = Wire.Payload.decode(
            """{"kind":"control","control":"group_rename","group":{"id":"$gid","epoch":7,"colour":"red"},"name":"ops2","vibe":"chill","createdAt":780000000}""".toByteArray(),
        )
        assertEquals("ops2", future.name); assertEquals(780000000.0, future.createdAt); assertEquals(GroupRef(gid, 7), future.group)
        // the optional former list round-trips and is simply absent when nil
        val invite = create.copy(control = GroupControl.INVITE, former = listOf(GroupMember(carol, "carol", 779990000.0)))
        assertTrue(String(invite.encode(), Charsets.UTF_8).endsWith(""","createdAt":7.8E8,"former":[{"hash":"$carol","name":"carol","joinedAt":7.7999E8}]}"""))
        assertEquals(invite, Wire.Payload.decode(invite.encode()))
        assertNull(Wire.Payload.decode(create.encode()).former)
        // a 1:1 payload still has no group keys at all
        assertEquals("""{"kind":"control","control":"typing"}""", String(Wire.Payload(kind = "control", control = "typing").encode(), Charsets.UTF_8))
        assertNull(Wire.Payload.decode("""{"kind":"text","text":"x"}""".toByteArray()).group)
    }

    @Test
    fun loginAuthAndSmallFrames() {
        assertEquals("""{"t":"login","user":"$hash"}""", Wire.encode(Wire.Out.Login(hash)))
        // e2e.mjs / bot: only `sig` (allow-all token mode)
        assertEquals("""{"t":"auth","sig":"U0lH"}""", Wire.encode(Wire.Out.Auth(sig = "U0lH")))
        // iOS: token + token_sig + push_token ("" when push is off); Android adds platform
        assertEquals(
            """{"t":"auth","sig":"U0lH","token":"VE9L","token_sig":"VFNJRw==","push_token":"","platform":"fcm"}""",
            Wire.encode(Wire.Out.Auth(sig = "U0lH", token = "VE9L", tokenSig = "VFNJRw==", pushToken = "", platform = "fcm")),
        )
        assertEquals("""{"t":"ping"}""", Wire.encode(Wire.Out.Ping))
        assertEquals("""{"t":"deregister"}""", Wire.encode(Wire.Out.Deregister))
        assertEquals("""{"t":"prekey_fetch","user":"$hash"}""", Wire.encode(Wire.Out.PreKeyFetch(hash)))
        assertEquals(
            """{"t":"prekey_upload","one_time_prekeys":[{"id":33,"pub":"QQ=="}]}""",
            Wire.encode(Wire.Out.PreKeyUpload(listOf(Wire.PreKeyJson(33, "QQ==")))),
        )
        assertEquals(
            """{"t":"send","id":"0b3d0a2e-7d5c-4b8a-9d1e-2f3a4b5c6d7e","to":"$hash","ttl":172800,"kind":"msg","body":"QUJD"}""",
            Wire.encode(Wire.Out.Send("0b3d0a2e-7d5c-4b8a-9d1e-2f3a4b5c6d7e", hash, Protocol.TTL_MSG, "msg", "QUJD")),
        )
        assertEquals("""{"t":"ack","id":"x"}""", Wire.encode(Wire.Out.Ack("x")))
        assertEquals("""{"t":"push_token","push_token":""}""", Wire.encode(Wire.Out.PushToken("")))
        assertEquals("""{"t":"report","user":"$hash"}""", Wire.encode(Wire.Out.Report(hash)))
    }

    @Test
    fun decodesRelayReplies() {
        assertEquals(Wire.In.Registered, Wire.decodeIn("""{"t":"registered"}"""))
        assertEquals(Wire.In.Challenge("AAAA"), Wire.decodeIn("""{"t":"challenge","nonce":"AAAA"}"""))
        assertEquals(Wire.In.Ready(3), Wire.decodeIn("""{"t":"ready","queued":3}"""))
        assertEquals(Wire.In.PreKeyCount(12), Wire.decodeIn("""{"t":"prekey_count","n":12}"""))
        assertEquals(Wire.In.Error("username_taken"), Wire.decodeIn("""{"t":"error","code":"username_taken"}"""))
        assertEquals(
            Wire.In.Error("unknown_user", user = hash),
            Wire.decodeIn("""{"t":"error","code":"unknown_user","user":"$hash"}"""),
        )
        assertEquals(Wire.In.Error("rate_limited", ref = "id1"), Wire.decodeIn("""{"t":"error","code":"rate_limited","ref":"id1"}"""))
        assertEquals(Wire.In.Sent("id1"), Wire.decodeIn("""{"t":"sent","id":"id1"}"""))
        assertEquals(
            Wire.In.Recv("id1", hash, "msg", "QUJD", 1700000000000L),
            Wire.decodeIn("""{"t":"recv","id":"id1","from":"$hash","kind":"msg","body":"QUJD","ts":1700000000000}"""),
        )
        assertEquals(Wire.In.Pong, Wire.decodeIn("""{"t":"pong"}"""))
        assertEquals(Wire.In.PairOpened("abc", 600), Wire.decodeIn("""{"t":"pair_opened","pair":"abc","ttl":600}"""))
        assertEquals(Wire.In.PairClosed("abc", "peer_gone"), Wire.decodeIn("""{"t":"pair_closed","pair":"abc","reason":"peer_gone"}"""))
    }

    @Test
    fun decodesOperatorNotices() {
        // exactly what relay NoticeBoard.Notice.toFrame() emits (epoch seconds; url omitted when empty)
        assertEquals(
            Wire.In.Notice("6f1c…", "relay maintenance tonight", "https://pyrochat.ca/", 1_760_000_000L, 1_760_604_800L),
            Wire.decodeIn("""{"t":"notice","id":"6f1c…","text":"relay maintenance tonight","url":"https://pyrochat.ca/","ts":1760000000,"expires":1760604800}"""),
        )
        assertEquals(
            Wire.In.Notice("n2", "no link", null, 1L, 2L),
            Wire.decodeIn("""{"t":"notice","id":"n2","text":"no link","ts":1,"expires":2,"future":{"x":1}}"""),
        )
        assertEquals(Wire.In.NoticeGone("n2"), Wire.decodeIn("""{"t":"notice_gone","id":"n2"}"""))
        // a notice with no id at all is structurally broken — dropped by the link like any undecodable frame
        assertFailsWith<SerializationException> { Wire.decodeIn("""{"t":"notice","text":"x"}""") }
        assertFailsWith<SerializationException> { Wire.decodeIn("""{"t":"notice_gone"}""") }
    }

    @Test
    fun decodesPreKeyBundleWithAndWithoutOptionalParts() {
        val full = Wire.decodeIn(
            """{"t":"prekey_bundle","user":"$hash","reg_id":77,"identity_key":"SUQ=",""" +
                """"signed_prekey":{"id":1,"pub":"U1BL","sig":"U0lH"},"kyber_prekey":{"id":1,"pub":"S1lC","sig":"S1NJRw=="},""" +
                """"one_time_prekey":{"id":7,"pub":"T1RL"}}""",
        )
        assertEquals(
            Wire.In.PreKeyBundle(hash, 77, "SUQ=", Wire.PreKeyJson(1, "U1BL", "U0lH"), Wire.PreKeyJson(1, "S1lC", "S1NJRw=="), Wire.PreKeyJson(7, "T1RL")),
            full,
        )
        val exhausted = assertIs<Wire.In.PreKeyBundle>(
            Wire.decodeIn("""{"t":"prekey_bundle","user":"$hash","reg_id":77,"identity_key":"SUQ=","signed_prekey":{"id":1,"pub":"U1BL","sig":"U0lH"}}"""),
        )
        assertNull(exhausted.oneTimePreKey)
        assertNull(exhausted.kyberPreKey)
    }

    @Test
    fun unknownKeysIgnoredDiscriminatorAnywhereUnknownTypeTolerated() {
        assertEquals(Wire.In.Registered, Wire.decodeIn("""{"t":"registered","extra":1,"more":{"x":[1,2]}}"""))
        assertEquals(Wire.In.Ready(1), Wire.decodeIn("""{"queued":1,"t":"ready"}"""))
        assertEquals(Wire.In.Unknown("future_frame"), Wire.decodeIn("""{"t":"future_frame","x":1}"""))
        assertEquals(Wire.In.Unknown("later"), Wire.decodeIn("""{"a":{"b":2},"t":"later"}"""))
        assertFailsWith<SerializationException> { Wire.decodeIn("""{"nonce":"x"}""") }        // no t
        assertFailsWith<SerializationException> { Wire.decodeIn("""{"t":"challenge"}""") }     // known type, missing field
        assertFailsWith<SerializationException> { Wire.decodeIn("""not json""") }
    }

    @Test
    fun envelopeMatchesBotIndexMjsByteForByte() {
        val env = Wire.Envelope.of(Protocol.CIPHERTEXT_PREKEY, byteArrayOf(1, 2))
        val jsonText = Wire.json.encodeToString(Wire.Envelope.serializer(), env)
        // JSON.stringify({ v: 1, type: ct.type(), data: b64(ct.serialize()) })
        assertEquals("""{"v":1,"type":3,"data":"AQI="}""", jsonText)
        assertEquals(Base64.getEncoder().encodeToString(jsonText.toByteArray()), env.toBody())
        assertEquals(env, Wire.Envelope.fromBody(env.toBody()))
        assertEquals(listOf<Byte>(1, 2), env.ciphertext().toList())
    }

    @Test
    fun envelopeRequiresVersionLikeIos() {
        val noV = Base64.getEncoder().encodeToString("""{"type":3,"data":"AQI="}""".toByteArray())
        assertFailsWith<SerializationException> { Wire.Envelope.fromBody(noV) }
        // type tolerates a float literal on iOS (`3.0`) — kotlinx does not; both accept ints, which is all anyone sends
        val ok = Base64.getEncoder().encodeToString("""{"data":"AQI=","type":2,"v":1}""".toByteArray())
        assertEquals(Wire.Envelope(1, 2, "AQI="), Wire.Envelope.fromBody(ok))
    }

    @Test
    fun payloadOmitsAbsentFields() {
        val p = Wire.Payload(kind = "text", text = "hi", senderName = "Alice")
        assertEquals("""{"kind":"text","text":"hi","senderName":"Alice"}""", String(p.encode()))
        assertEquals(p, Wire.Payload.decode(p.encode()))
        // Swift-encoded payload with a field we don't know is still readable
        assertEquals(
            Wire.Payload(kind = "control", control = "receipt", receipt = "read", refIds = listOf("a", "b")),
            Wire.Payload.decode("""{"kind":"control","control":"receipt","receipt":"read","refIds":["a","b"],"newThing":true}""".toByteArray()),
        )
    }
}
