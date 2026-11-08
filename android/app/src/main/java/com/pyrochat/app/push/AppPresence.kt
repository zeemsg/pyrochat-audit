package com.pyrochat.app.push

/**
 * What the wake handler needs to know about the running app, RAM only: is the activity started
 * (`RealBackend.noteForeground/noteBackground`, iOS scene phase), is the relay link up, and how
 * many unread rows the unlocked store holds. Bound by `RealBackend.attach`, cleared when the
 * session ends; the defaults describe a cold or locked process.
 */
object AppPresence {
    @Volatile
    var foreground: Boolean = false

    @Volatile
    var online: () -> Boolean = { false }
        private set

    @Volatile
    var totalUnread: () -> Int? = { null }
        private set

    /**
     * docs/DECOY-ACCOUNT.md §6.6 — could the OPEN slot be the one this wake is for?
     *
     * A wake push is `{"kind":"wake"}` and nothing else, by design: it names no account, so the
     * payload can never say which slot it belongs to. What the app does know is whether the open
     * session ever asked the relay to wake it. A session that did not cannot be the one being
     * woken, and a notification for it would be rendering something for a slot that is not the
     * open one, which §6.6 forbids.
     *
     * A cold or locked process answers true: there is no open slot to be wrong about, and the
     * banner then says exactly what it always said.
     */
    @Volatile
    var wakesThisSlot: () -> Boolean = { true }
        private set

    /** True while a store is unlocked in this process — set by `bind`, cleared by `unbind`. */
    @Volatile
    var sessionOpen: Boolean = false
        private set

    fun bind(online: () -> Boolean, totalUnread: () -> Int?, wakesThisSlot: () -> Boolean = { true }) {
        this.online = online
        this.totalUnread = totalUnread
        this.wakesThisSlot = wakesThisSlot
        this.sessionOpen = true
    }

    fun unbind() {
        online = { false }
        totalUnread = { null }
        wakesThisSlot = { true }
        sessionOpen = false
    }
}
