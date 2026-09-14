package com.openminis.app.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.ref.WeakReference

internal data class TaskCenterEntry(val sessionId: String, val title: String, val run: ChatRunState)

/** Only this process owns runnable jobs. Never restore a RUNNING label from disk. */
internal object TaskCenter {
    val entries = MutableStateFlow<List<TaskCenterEntry>>(emptyList())
    private val owners = mutableMapOf<String, WeakReference<ChatViewModel>>()
    @Synchronized fun update(id: String, title: String, runs: Collection<ChatRunState>, vm: ChatViewModel) {
        val previousIds = owners.filter { it.value.get() === vm }.keys.toSet()
        owners.entries.removeAll { it.value.get() == null || (it.value.get() === vm && it.key != id) }
        owners[id] = WeakReference(vm)
        val merged = entries.value.filterNot { it.sessionId == id || it.sessionId in previousIds } +
            runs.filter { it.status != ChatRunStatus.NotStarted }.map { TaskCenterEntry(id, title, it) }
        // Retain all live work; cap settled history.
        entries.value = merged.filter { !it.run.status.isTerminal } +
            merged.filter { it.run.status.isTerminal }.sortedByDescending { it.run.finishedAtMs }.take(100)
    }
    @Synchronized fun remove(id: String) {
        owners.remove(id)
        entries.value = entries.value.filterNot { it.sessionId == id }
    }
    fun stop(id: String) {
        val vm = owners[id]?.get() ?: return
        vm.promptQueue.value.toList().forEach { vm.removeQueuedPrompt(it.id) }
        vm.cancelStream()
    }
}
