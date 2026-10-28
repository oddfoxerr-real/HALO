package dev.oddfoxerr.halo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Volatile in-process activity. Never stores message bodies or contacts. */
object NotificationActivityState {
    var activeCount by mutableStateOf(0)
        private set
    var listenerConnected by mutableStateOf(false)
        private set
    var recent by mutableStateOf<List<NotificationUiEvent>>(emptyList())
        private set

    fun connected(count: Int) {
        listenerConnected = true
        activeCount = count
    }
    fun event(packageName: String, type: EventType, count: Int) {
        activeCount = count
        recent = (listOf(NotificationUiEvent(packageName, System.currentTimeMillis(), type)) + recent).take(15)
    }
    fun disconnected() {
        listenerConnected = false
        activeCount = 0
    }
}
