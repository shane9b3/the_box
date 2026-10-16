package app.thebox.core.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** A file the reader refuses; [failure] is what the restore reports (reason + next step on screen). */
class BackupFormatException(val failure: RestoreFailure, message: String) : Exception(message)

/**
 * Writes a `.thebox` file (docs/design/m5-setup-settings.md §3.10.1): UTF-8 JSON Lines with LF, line 1 the header,
 * then one record per line, then `End(records)`. Streams: nothing but the current line is held.
 */
class BackupWriter(private val out: OutputStream) {
    private var records = 0
    private var finished = false

    /** Writes the header and returns its exact bytes (without the LF): the associated data of the `Secrets` record. */
    fun writeHeader(header: BackupHeader): ByteArray {
        check(records == 0) { "header must come first" }
        val bytes = BackupJson.encodeToString(BackupHeader.serializer(), header).encodeToByteArray()
        out.write(bytes)
        out.write(LF)
        return bytes
    }

    fun write(record: BackupRecord) {
        check(!finished) { "already finished" }
        require(record !is BackupRecord.End) { "End is written by finish()" }
        out.write(BackupJson.encodeToString(BackupRecord.serializer(), record).encodeToByteArray())
        out.write(LF)
        records++
    }

    /** Writes `End(records)` and flushes. */
    fun finish(): Int {
        out.write(BackupJson.encodeToString(BackupRecord.serializer(), BackupRecord.End(records)).encodeToByteArray())
        out.write(LF)
        out.flush()
        finished = true
        return records
    }

    private companion object { const val LF = '\n'.code }
}

/**
 * Reads a `.thebox` file line by line in constant memory: header checks (NOT_A_BACKUP, NEWER_SCHEMA), the size limits
 * ([BACKUP_MAX_BYTES], [BACKUP_MAX_LINE_BYTES] → TOO_LARGE), unknown record types skipped, and a missing or wrong
 * `End` → TRUNCATED.
 */
class BackupReader(
    input: InputStream,
    private val maxBytes: Long = BACKUP_MAX_BYTES,
    private val maxLineBytes: Int = BACKUP_MAX_LINE_BYTES,
) {
    private val input = BufferedInputStream(input, 64 * 1024)
    private val line = ByteArrayOutputStream(4096)
    private var total = 0L
    private var seen = 0
    private var ended = false

    /** Record lines of unknown types that were skipped. */
    var skipped = 0
        private set

    lateinit var header: BackupHeader
        private set
    /** The header line's exact bytes (AD of the secrets). */
    lateinit var headerBytes: ByteArray
        private set

    fun readHeader(): BackupHeader {
        val bytes = try {
            nextLine()
        } catch (e: BackupFormatException) {
            throw BackupFormatException(RestoreFailure.NOT_A_BACKUP, "unreadable first line")
        } ?: throw BackupFormatException(RestoreFailure.NOT_A_BACKUP, "empty file")
        val obj = parseObject(bytes) ?: throw BackupFormatException(RestoreFailure.NOT_A_BACKUP, "first line is not JSON")
        if ((obj["format"] as? JsonPrimitive)?.contentOrNull != BackupHeader.FORMAT) throw BackupFormatException(RestoreFailure.NOT_A_BACKUP, "wrong format")
        val schema = (obj["schema"] as? JsonPrimitive)?.intOrNull ?: throw BackupFormatException(RestoreFailure.NOT_A_BACKUP, "no schema")
        if (schema > BackupHeader.SCHEMA) throw BackupFormatException(RestoreFailure.NEWER_SCHEMA, "schema $schema")
        header = try {
            BackupJson.decodeFromJsonElement(BackupHeader.serializer(), obj)
        } catch (e: SerializationException) {
            throw BackupFormatException(RestoreFailure.NOT_A_BACKUP, "bad header")
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException(RestoreFailure.NOT_A_BACKUP, "bad header")
        }
        headerBytes = bytes
        return header
    }

    /** The next known record; null after a valid `End`. */
    fun next(): BackupRecord? {
        check(::header.isInitialized) { "readHeader() first" }
        if (ended) return null
        while (true) {
            val bytes = nextLine() ?: throw BackupFormatException(RestoreFailure.TRUNCATED, "no End record")
            val obj = parseObject(bytes) ?: throw BackupFormatException(RestoreFailure.TRUNCATED, "unparsable line")
            val type = (obj["t"] as? JsonPrimitive)?.contentOrNull
            if (type == "end") {
                val end = decode(obj) as BackupRecord.End
                if (end.records != seen) throw BackupFormatException(RestoreFailure.TRUNCATED, "End says ${end.records}, read $seen")
                ended = true
                return null
            }
            seen++
            if (type == null || type !in KNOWN_RECORD_TYPES) {
                skipped++
                continue
            }
            return decode(obj)
        }
    }

    private fun decode(obj: JsonObject): BackupRecord = try {
        BackupJson.decodeFromJsonElement(BackupRecord.serializer(), obj)
    } catch (e: SerializationException) {
        throw BackupFormatException(RestoreFailure.TRUNCATED, "bad record")
    } catch (e: IllegalArgumentException) {
        throw BackupFormatException(RestoreFailure.TRUNCATED, "bad record")
    }

    private fun parseObject(bytes: ByteArray): JsonObject? = try {
        BackupJson.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
    } catch (e: SerializationException) {
        null
    } catch (e: CharacterCodingException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** One line without its LF; null at EOF before any byte. A final line without LF is still returned. */
    private fun nextLine(): ByteArray? {
        line.reset()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.size() == 0) null else line.toByteArray()
            if (++total > maxBytes) throw BackupFormatException(RestoreFailure.TOO_LARGE, "file over $maxBytes bytes")
            if (b == '\n'.code) return line.toByteArray()
            if (line.size() >= maxLineBytes) throw BackupFormatException(RestoreFailure.TOO_LARGE, "line over $maxLineBytes bytes")
            line.write(b)
        }
    }
}
