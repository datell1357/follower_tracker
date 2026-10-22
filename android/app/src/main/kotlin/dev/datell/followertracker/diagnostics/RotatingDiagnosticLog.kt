package dev.datell.followertracker.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Fixed application-owned slots. No arbitrary paths, file deletion, network or user data. */
internal class RotatingDiagnosticLog(private val directory: File, private val slotBytes: Long = 2 * 1024 * 1024,
    private val slots: Int = 4) {
    @Serializable private data class Header(val format: Int = 1, val generation: Long)
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private var index: Int
    private var generation: Long

    init {
        require(slotBytes >= 1_024 && slots in 2..8)
        check(directory.isDirectory || directory.mkdirs())
        val existing = (0 until slots).mapNotNull { slot ->
            val header = runCatching {
                file(slot).bufferedReader().use { reader ->
                    json.decodeFromString<Header>(checkNotNull(reader.readLine())).takeIf { it.format == 1 && it.generation > 0 }
                }
            }.getOrNull()
            header?.let { slot to it.generation }
        }.maxByOrNull { it.second }
        index = existing?.first ?: 0
        generation = existing?.second ?: 1
        if (existing == null) openSlot() else recoverIncompleteTail()
    }

    @Synchronized fun append(record: DiagnosticRecord) {
        val line = (json.encodeToString(record) + "\n").toByteArray(Charsets.UTF_8)
        require(line.size <= 4_096 && line.size < slotBytes / 2)
        if (file(index).length() + line.size > slotBytes) {
            index = (index + 1) % slots
            generation = Math.addExact(generation, 1)
            openSlot()
        }
        FileOutputStream(file(index), true).use { it.write(line) }
    }

    private fun file(slot: Int) = File(directory, "collection-$slot.jsonl")
    private fun openSlot() {
        file(index).writeText(json.encodeToString(Header(generation = generation)) + "\n", Charsets.UTF_8)
    }

    /** A killed process may leave one partial record; keep all preceding complete entries. */
    private fun recoverIncompleteTail() {
        RandomAccessFile(file(index), "rw").use { file ->
            val length = file.length()
            if (length == 0L) return
            file.seek(length - 1)
            if (file.read() == '\n'.code) return
            val start = maxOf(0L, length - 4_096)
            val tail = ByteArray((length - start).toInt())
            file.seek(start); file.readFully(tail)
            val end = tail.indexOfLast { it == '\n'.code.toByte() }
            check(end >= 0) { "Invalid diagnostic record tail" }
            file.setLength(start + end + 1)
        }
    }
}
