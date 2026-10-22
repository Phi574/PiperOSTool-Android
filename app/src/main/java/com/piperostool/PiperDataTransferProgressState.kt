package com.piperostool

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/** In-process transfer progress shared between the transport worker and its dedicated screen. */
object PiperDataTransferProgressState {
    data class Item(
        val name: String,
        val mime: String,
        val size: Long,
        val transferred: Long = 0L,
        val state: ItemState = ItemState.WAITING
    )

    enum class ItemState { WAITING, ACTIVE, COMPLETE }

    data class Snapshot(
        val sessionId: String,
        val role: String,
        val items: List<Item>,
        val transferred: Long,
        val total: Long,
        val currentIndex: Int,
        val complete: Boolean = false,
        val error: String? = null,
        val receivedDirectory: String? = null,
        val awaitingConfirmation: Boolean = false
    )

    private val snapshots = ConcurrentHashMap<String, Snapshot>()
    private val observers = ConcurrentHashMap<String, CopyOnWriteArraySet<(Snapshot) -> Unit>>()
    private val lastUpdateAt = ConcurrentHashMap<String, Long>()
    private val cancelHandlers = ConcurrentHashMap<String, () -> Unit>()

    fun begin(sessionId: String, role: String, items: List<Item>, total: Long) {
        publish(Snapshot(sessionId, role, items.toList(), 0L, total, 0))
    }

    fun update(sessionId: String, currentIndex: Int, itemTransferred: Long, transferred: Long, total: Long) {
        val old = snapshots[sessionId] ?: return
        val now = System.currentTimeMillis()
        val last = lastUpdateAt[sessionId] ?: 0L
        if (old.currentIndex == currentIndex && now - last < 120L) return
        lastUpdateAt[sessionId] = now
        val updated = old.items.mapIndexed { index, item ->
            when {
                index < currentIndex -> item.copy(
                    transferred = if (item.size >= 0L) item.size else item.transferred,
                    state = ItemState.COMPLETE
                )
                index == currentIndex -> item.copy(
                    transferred = item.size.takeIf { it > 0 }?.let { itemTransferred.coerceIn(0L, it) } ?: itemTransferred,
                    state = ItemState.ACTIVE
                )
                else -> item
            }
        }
        publish(old.copy(items = updated, transferred = transferred, total = total, currentIndex = currentIndex))
    }

    fun complete(sessionId: String, transferred: Long, total: Long, receivedDirectory: String? = null) {
        lastUpdateAt.remove(sessionId)
        cancelHandlers.remove(sessionId)
        val old = snapshots[sessionId] ?: return
        publish(old.copy(
            items = old.items.map { item ->
                item.copy(transferred = if (item.size >= 0L) item.size else item.transferred, state = ItemState.COMPLETE)
            },
            transferred = transferred,
            total = total,
            currentIndex = old.items.lastIndex,
            complete = true,
            receivedDirectory = receivedDirectory,
            awaitingConfirmation = false,
            error = null
        ))
    }

    fun fail(sessionId: String, message: String) {
        val old = snapshots[sessionId] ?: return
        cancelHandlers.remove(sessionId)
        publish(old.copy(error = message.take(240), awaitingConfirmation = false))
    }

    fun awaitingConfirmation(sessionId: String) {
        val old = snapshots[sessionId] ?: return
        if (!old.complete && old.error == null) {
            val finishedItems = old.items.map { item ->
                item.copy(
                    transferred = if (item.size >= 0L) item.size else item.transferred,
                    state = ItemState.COMPLETE
                )
            }
            publish(old.copy(
                items = finishedItems,
                transferred = if (old.total >= 0L) old.total else old.transferred,
                currentIndex = old.items.size,
                awaitingConfirmation = true
            ))
        }
    }

    fun setCancelHandler(sessionId: String, handler: (() -> Unit)?) {
        if (handler == null) cancelHandlers.remove(sessionId) else cancelHandlers[sessionId] = handler
    }

    fun requestCancel(sessionId: String): Boolean {
        val snapshot = snapshots[sessionId] ?: return false
        if (snapshot.complete || snapshot.error != null) return false
        val handler = cancelHandlers[sessionId] ?: return false
        handler()
        return true
    }

    fun observe(sessionId: String, observer: (Snapshot) -> Unit): AutoCloseable {
        val set = observers.getOrPut(sessionId) { CopyOnWriteArraySet() }
        set.add(observer)
        snapshots[sessionId]?.let(observer)
        return AutoCloseable { set.remove(observer) }
    }

    fun snapshot(sessionId: String): Snapshot? = snapshots[sessionId]

    fun remove(sessionId: String) {
        snapshots.remove(sessionId)
        lastUpdateAt.remove(sessionId)
        cancelHandlers.remove(sessionId)
        observers.remove(sessionId)
    }

    private fun publish(snapshot: Snapshot) {
        snapshots[snapshot.sessionId] = snapshot
        observers[snapshot.sessionId]?.forEach { observer -> runCatching { observer(snapshot) } }
    }
}
