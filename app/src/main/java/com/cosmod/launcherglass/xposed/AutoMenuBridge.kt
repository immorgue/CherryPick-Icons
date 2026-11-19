package com.cosmod.launcherglass.xposed

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.cosmod.launcherglass.ui.SettingsProvider
import com.cosmod.launcherglass.util.AutoIconStyle

/** The launcher keeps working while Android starts or restarts the settings provider. */
internal object AutoMenuBridge {
    private val main = Handler(Looper.getMainLooper())
    private val worker by lazy {
        Handler(HandlerThread("CherryPickAppearanceSettings", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }.looper)
    }
    private var context: Context? = null
    private var confirmed: Bundle? = null
    @Volatile private var journal = Bundle()
    private val pending = Bundle()
    var transition: LauncherAppearanceTransition? = null
    private var applying = false
    private var writing = false
    private var refreshAfterWrite = false
    private var reading = false
    private var retryCount = 0
    var hasRead = false
        private set
    private val listeners = LinkedHashSet<(Bundle, Boolean) -> Unit>()
    private val flushTask = Runnable { flush() }

    fun isEditableSetting(key:String)=key==SettingsProvider.KEY_HAZE_ENABLED ||
        (key!=AutoIconStyle.ENGINE && key in AutoIconStyle.defaults)

    fun initialize(host: Context) {
        if (context != null) return
        context = host.applicationContext
        journal = restore(host, "appearance_pending").takeIf {
            it.keySet().all { key -> isEditableSetting(key) &&
                if(key==SettingsProvider.KEY_HAZE_ENABLED)it.get(key) is Boolean else it.get(key) is Int }
        } ?: Bundle()
    }

    private fun restore(host: Context, name: String): Bundle = Bundle().apply {
        for ((key, value) in host.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
            when (value) {
                is Int -> putInt(key, value)
                is Boolean -> putBoolean(key, value)
                is String -> putString(key, value)
            }
        }
    }

    private fun store(host: Context, name: String, snapshot: Bundle) {
        val file = host.getSharedPreferences(name, Context.MODE_PRIVATE)
        val saved = snapshot.keySet().associateWith { snapshot.get(it) }
        if (file.all == saved) return
        file.edit().clear().apply {
            for ((key, value) in saved) when (value) {
                is Int -> putInt(key, value)
                is Boolean -> putBoolean(key, value)
                is String -> putString(key, value)
            }
        }.commit()
    }

    fun cachedSettings(host: Context): Bundle? = restore(host, "appearance_settings").takeUnless { it.isEmpty }

    fun remember(host: Context, snapshot: Bundle) {
        val copy = Bundle(snapshot)
        worker.post { store(host, "appearance_settings", copy) }
    }

    fun effective(snapshot: Bundle): Bundle = Bundle(snapshot).apply {
        if (getInt(AutoIconStyle.ENGINE, AutoIconStyle.DEFAULT_ENGINE) == 1) putAll(journal)
    }

    private fun current() = PrefsHelper.settingsSnapshot() ?: confirmed
    val hasSettings: Boolean get() = current() != null

    fun snapshot(): Bundle = effective(current() ?: Bundle()).apply {
        if (getInt(AutoIconStyle.ENGINE, AutoIconStyle.DEFAULT_ENGINE) == 1) putAll(pending)
    }

    fun subscribe(context: Context, listener: (Bundle, Boolean) -> Unit) {
        initialize(context)
        listeners.add(listener)
        listener(snapshot(), hasSettings)
        refresh()
    }

    fun unsubscribe(listener: (Bundle, Boolean) -> Unit) { listeners.remove(listener) }

    fun reconnect() {
        if (!writing && !journal.isEmpty && pending.isEmpty) defer(0)
    }

    fun deferProviderNotification(): Boolean {
        if (!writing) return false
        refreshAfterWrite = true
        return true
    }

    private fun publish() {
        val value = snapshot()
        listeners.toList().forEach { listener -> runCatching { listener(Bundle(value), hasSettings) } }
    }

    private fun read(host: Context) = runCatching {
        host.contentResolver.call(SettingsProvider.CONTENT_URI, SettingsProvider.METHOD_GET_ALL, null, null)
    }.getOrNull()

    fun refresh() {
        val host = context ?: return
        if (reading || writing) return
        reading = true
        worker.post {
            val result = read(host)
            main.post {
                reading = false
                hasRead = true
                if (result != null) {
                    confirmed = result
                    PrefsHelper.applyAutoMenuSettings(host, effective(result))
                }
                publish()
                if (!journal.isEmpty && pending.isEmpty) defer(0)
            }
        }
    }

    fun choose(changes: Bundle) {
        if (current()?.getInt(AutoIconStyle.ENGINE, AutoIconStyle.DEFAULT_ENGINE) != 1) return
        for (key in changes.keySet()) {
            if(!isEditableSetting(key))continue
            if(key==SettingsProvider.KEY_HAZE_ENABLED)pending.putBoolean(key,changes.getBoolean(key))
            else pending.putInt(key, changes.getInt(key).coerceIn(AutoIconStyle.range(key)))
        }
        retryCount = 0
        defer(16)
    }

    private fun defer(delay: Long) {
        main.removeCallbacks(flushTask)
        if (!pending.isEmpty || !journal.isEmpty) main.postDelayed(flushTask, delay)
    }

    private fun flush() {
        val host = context ?: return
        if (applying) return
        if (!pending.isEmpty) {
            applying = true
            val apply = {
                applying = false
                val base = current()
                if (base?.getInt(AutoIconStyle.ENGINE, AutoIconStyle.DEFAULT_ENGINE) == 1) {
                    journal = Bundle(journal).apply { putAll(pending) }
                    pending.clear()
                    val copy = Bundle(journal)
                    worker.post { store(host, "appearance_pending", copy) }
                    PrefsHelper.applyAutoMenuSettings(host, effective(base))
                } else pending.clear()
                publish()
                persist(host)
            }
            transition?.change(apply) ?: apply()
        } else {
            persist(host)
        }
    }

    private fun persist(host: Context) {
        if (writing || journal.isEmpty) return
        val change = Bundle(journal)
        writing = true
        worker.post {
            val reply = runCatching {
                host.contentResolver.call(SettingsProvider.CONTENT_URI,
                    SettingsProvider.METHOD_SET_AUTO_APPEARANCE, null, change)
            }.getOrNull()
            val result = read(host)
            main.post {
                writing = false
                hasRead = true
                if (reply != null && reply.getBoolean("saved", true)) {
                    retryCount = 0
                    val accepted = reply.getBoolean("enabled")
                    journal = if (!accepted) Bundle() else Bundle(journal).apply {
                        for (key in change.keySet()) if (containsKey(key) && get(key) == change.get(key)) remove(key)
                    }
                    val remaining = Bundle(journal)
                    worker.post { store(host, "appearance_pending", remaining) }
                    if (!accepted) pending.clear()
                    persist(host)
                } else if (!journal.isEmpty && retryCount++ < 3) {
                    defer(750L * retryCount)
                }
                if (result != null) {
                    confirmed = result
                    PrefsHelper.applyAutoMenuSettings(host, effective(result))
                    remember(host, result)
                }
                publish()
                if (!pending.isEmpty) defer(16)
                if (refreshAfterWrite && !writing) {
                    refreshAfterWrite = false
                    refresh()
                }
            }
        }
    }
}
