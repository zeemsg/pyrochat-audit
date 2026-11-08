package com.pyrochat.app.backend

import android.content.Context
import android.content.SharedPreferences
import com.pyrochat.service.NoticeCodec
import com.pyrochat.service.NoticeStore

/**
 * [NoticeStore] over plain `SharedPreferences` — deliberately OUTSIDE the encrypted store: operator
 * notices are the relay's public announcements (the same list for everyone, no account involved),
 * so they need no secrecy and must be readable before a login. Keys `pyro.notices` (the JSON array
 * [NoticeCodec] writes), `pyro.notices.seen` (a JSON array of ids) and `pyro.versions` (the last
 * accepted `versions` entry for this platform, the object [com.pyrochat.service.VersionCodec]
 * writes — the update banner when the app opens offline). [clear] runs with every local wipe
 * (total wipe, duress, dead-man, a rolled-back registration).
 */
class PrefsNoticeStore(context: Context) : NoticeStore {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun getNotices(): String? = prefs.getString(KEY_NOTICES, null)

    override fun putNotices(json: String) {
        prefs.edit().putString(KEY_NOTICES, json).apply()
    }

    override fun getSeen(): Set<String> = NoticeCodec.decodeIds(prefs.getString(KEY_SEEN, null))

    override fun putSeen(ids: Set<String>) {
        prefs.edit().putString(KEY_SEEN, NoticeCodec.encodeIds(ids)).apply()
    }

    override fun getVersions(): String? = prefs.getString(KEY_VERSIONS, null)

    override fun putVersions(json: String?) {
        prefs.edit().apply { if (json == null) remove(KEY_VERSIONS) else putString(KEY_VERSIONS, json) }.apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_NOTICES).remove(KEY_SEEN).remove(KEY_VERSIONS).commit()
    }

    companion object {
        const val FILE = "pyro-notices"
        const val KEY_NOTICES = "pyro.notices"
        const val KEY_SEEN = "pyro.notices.seen"
        const val KEY_VERSIONS = "pyro.versions"
    }
}
