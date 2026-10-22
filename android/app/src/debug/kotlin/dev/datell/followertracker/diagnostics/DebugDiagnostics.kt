package dev.datell.followertracker.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Enabled automatically only in Debug. Files stay in private, non-backed-up app storage. */
internal object DebugDiagnostics {
    private sealed interface Message {
        data class Entry(val value: DiagnosticRecord) : Message
        data class Flush(val done: CompletableDeferred<Unit>) : Message
    }
    private val sequence = AtomicLong()
    private val dropped = AtomicLong()
    private val json = Json { explicitNulls = false }
    private val run = UUID.randomUUID().toString().replace("-", "")
    @Volatile private var queue: Channel<Message>? = null

    @Synchronized fun initialize(context: Context) {
        if (queue != null) return
        val directory = runCatching { File(context.applicationContext.noBackupFilesDir, "diagnostics") }.getOrNull() ?: return
        val channel = Channel<Message>(128)
        queue = channel
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var log: RotatingDiagnosticLog? = null
            var warned = false
            for (message in channel) {
                if (message is Message.Flush) { message.done.complete(Unit); continue }
                message as Message.Entry
                try {
                    val writer = log ?: RotatingDiagnosticLog(directory).also { log = it }
                    val lost = dropped.get()
                    if (lost > 0) {
                        writer.append(DiagnosticRecord(System.currentTimeMillis(), run,
                            DiagnosticEntry(DiagnosticKind.ENTRIES_DROPPED, droppedEntries = lost)))
                        dropped.addAndGet(-lost)
                    }
                    writer.append(message.value)
                } catch (_: Exception) {
                    log = null
                    dropped.incrementAndGet()
                    if (!warned) { Log.w("FollowerDiagnostics", "diagnostic_file_unavailable"); warned = true }
                }
            }
        }
        record(DiagnosticEntry(DiagnosticKind.PROCESS_START))
    }

    fun nextAttempt(): Long? = queue?.let { sequence.incrementAndGet() }

    fun record(entry: DiagnosticEntry) {
        val channel = queue ?: return
        val value = DiagnosticRecord(System.currentTimeMillis(), run, entry)
        // File I/O is never performed on the UI/collection caller. Logging cannot reject a collection.
        runCatching { Log.d("FollowerDiagnostics", json.encodeToString(value)) }
        if (channel.trySend(Message.Entry(value)).isFailure) dropped.incrementAndGet()
    }

    /** Bounded by callers in isolated runtime tests; the real collection path never waits for disk. */
    suspend fun flush() {
        val channel = queue ?: return
        val done = CompletableDeferred<Unit>()
        channel.send(Message.Flush(done)); done.await()
    }
}
