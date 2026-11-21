package dev.toastmod.ancs.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri

data class ANCSNotificationAction(
    val uid: Int,
    val action: Byte,
    val session: String,
    val revision: Long,
    val label: String
) {
    fun toIntent(context: Context): Intent =
        Intent(context, ANCSAndroidService::class.java).setAction(INTENT_ACTION)
            .setData(Uri.Builder().scheme("ancs").authority("notification-action")
                .appendPath(session).appendPath(uid.toString())
                .appendPath(revision.toString()).appendPath(action.toString()).build())
            .putExtra("uid", uid)
            .putExtra("action", action.toInt())
            .putExtra("session", session)
            .putExtra("revision", revision)
            .putExtra("label", label)

    companion object {
        const val INTENT_ACTION = "dev.toastmod.ancs.NOTIFICATION_ACTION"

        fun fromIntent(intent: Intent): ANCSNotificationAction? {
            if (intent.action != INTENT_ACTION ||
                !intent.hasExtra("uid") || !intent.hasExtra("action") ||
                !intent.hasExtra("revision")
            ) return null
            val action = intent.getIntExtra("action", -1)
            val session = intent.getStringExtra("session")
            val label = intent.getStringExtra("label")
            if (action !in 0..1 || session.isNullOrBlank() || label.isNullOrBlank()) return null
            return ANCSNotificationAction(intent.getIntExtra("uid", 0), action.toByte(),
                session, intent.getLongExtra("revision", -1), label)
        }
    }
}
