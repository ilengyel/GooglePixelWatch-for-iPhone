package dev.toastmod.ancs.presentation

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NotificationApp(
    val identifier: String,
    val displayName: String = identifier,
    val enabled: Boolean = true
)

class NotificationApps(
    private val preferences: SharedPreferences,
    private val phoneAddress: String,
    private val onChanged: (List<NotificationApp>) -> Unit = {}
) {
    private val names = linkedMapOf<String, String?>()
    private val filterChanges = mutableMapOf<String, Long>()
    var revision: Long = 0
        private set
    private var blocked = preferences.getStringSet(blockedKey(phoneAddress), emptySet())
        .orEmpty().toSet()
    private val mutableState = MutableStateFlow<List<NotificationApp>>(emptyList())
    val state = mutableState.asStateFlow()

    fun isEnabled(identifier: String): Boolean = identifier !in blocked

    fun changedAfter(identifier: String, revision: Long): Boolean =
        (filterChanges[identifier] ?: 0) > revision

    fun observe(identifier: String) {
        if (identifier in names) return
        if (names.size >= 128) names.remove(names.keys.first())
        names[identifier] = null
        publish()
    }

    fun needsName(identifier: String): Boolean = identifier in names && names[identifier] == null

    fun displayName(identifier: String): String = names[identifier] ?: identifier

    fun onName(identifier: String, name: String) {
        if (identifier !in names) return
        names[identifier] = name.ifBlank { identifier }
        publish()
    }

    fun setEnabled(identifier: String, enabled: Boolean) {
        val updated = if (enabled) blocked - identifier else blocked + identifier
        check(preferences.edit().putStringSet(blockedKey(phoneAddress), updated).commit()) {
            "Could not save notification app preference"
        }
        blocked = updated
        filterChanges[identifier] = ++revision
        publish()
    }

    fun clearSession() {
        names.clear()
        filterChanges.clear()
        revision = 0
        publish()
    }

    private fun publish() {
        mutableState.value = names.map { (id, name) ->
            NotificationApp(id, name ?: id, isEnabled(id))
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
        onChanged(mutableState.value)
    }

    companion object {
        fun blockedKey(address: String): String = "blocked-apps:$address"
    }
}
