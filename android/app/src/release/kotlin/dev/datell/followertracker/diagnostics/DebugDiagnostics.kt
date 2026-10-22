@file:Suppress("UNUSED_PARAMETER")

package dev.datell.followertracker.diagnostics

import android.content.Context

/** Release has no queue, file writer, Logcat output, identifier generator or initialization side effects. */
internal object DebugDiagnostics {
    fun initialize(context: Context) = Unit
    fun nextAttempt(): Long? = null
    fun record(entry: DiagnosticEntry) = Unit
    suspend fun flush() = Unit
}
