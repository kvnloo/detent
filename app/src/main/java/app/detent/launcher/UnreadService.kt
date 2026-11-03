package app.detent.launcher

import android.app.Notification
import android.app.PendingIntent
import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.ref.WeakReference

/** A button of a notification that runs without typing (reply buttons need a text box, so they drop). */
class NoteAction(val title: String, val intent: PendingIntent)

/** One clearable notification, as quick panes show it. */
class Note(val key: String, val title: String, val text: String, val time: Long, val intent: PendingIntent?, val actions: List<NoteAction>)

/** Latest post time of a clearable notification, keyed by package plus user serial. */
object Unread {
    val state = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** The clearable notifications per package plus user serial, newest first. */
    val notes = MutableStateFlow<Map<String, List<Note>>>(emptyMap())

    private var service = WeakReference<UnreadService>(null)

    internal fun attach(s: UnreadService?) {
        service = WeakReference(s)
    }

    /** Clears notification [key]. False when the listener is not connected. */
    fun dismiss(key: String): Boolean {
        val s = service.get() ?: return false
        return runCatching { s.cancelNotification(key) }.isSuccess
    }
}

/** Feeds the unread dots and the notification panes. It runs only after the user grants notification access. */
class UnreadService : NotificationListenerService() {
    override fun onListenerConnected() {
        Unread.attach(this)
        refresh()
    }

    override fun onListenerDisconnected() {
        Unread.attach(null)
        Unread.state.value = emptyMap()
        Unread.notes.value = emptyMap()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    private fun refresh() {
        val list = try {
            activeNotifications
        } catch (e: RuntimeException) {
            null
        } ?: return
        val um = getSystemService(UserManager::class.java)
        val map = HashMap<String, Long>()
        val notes = HashMap<String, MutableList<Note>>()
        for (sbn in list) {
            val flags = sbn.notification.flags
            if (sbn.isOngoing) continue
            if (flags and (Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY) != 0) continue
            val key = sbn.packageName + "#" + um.getSerialNumberForUser(sbn.user)
            map[key] = maxOf(map[key] ?: 0L, sbn.postTime)
            noteOf(sbn)?.let { notes.getOrPut(key) { ArrayList() } += it }
        }
        Unread.state.value = map
        Unread.notes.value = notes.mapValues { (_, v) -> v.sortedByDescending { it.time } }
    }

    private fun noteOf(sbn: StatusBarNotification): Note? {
        val n = sbn.notification
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_TEXT) ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT))
            ?.toString()?.trim()?.replace('\n', ' ').orEmpty()
        if (title.isEmpty() && text.isEmpty()) return null
        val actions = n.actions.orEmpty()
            .filter { it.remoteInputs.isNullOrEmpty() && it.actionIntent != null && !it.title.isNullOrBlank() }
            .take(3)
            .map { NoteAction(it.title.toString(), it.actionIntent) }
        return Note(sbn.key, title, text, sbn.postTime, n.contentIntent, actions)
    }
}
