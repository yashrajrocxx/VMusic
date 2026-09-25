package app.pulse.android.backup

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal reader/writer for AndroidX DataStore's `settings.preferences_pb` file format
 * (the `Preferences` proto: `map<string, Value> preferences = 1`), so backups stay
 * interoperable with MetroList/InnerTune without adding a protobuf dependency.
 *
 * Value oneof: 1 = boolean (varint), 2 = float (fixed32), 3 = int (varint),
 * 4 = long (varint), 5 = string (length-delimited), 6 = string set (embedded),
 * 7 = bytes (length-delimited). StringSet: repeated string field 1.
 */
object PreferencesProto {
    sealed interface PrefValue {
        data class BooleanV(val value: Boolean) : PrefValue
        data class FloatV(val value: Float) : PrefValue
        data class IntV(val value: Int) : PrefValue
        data class LongV(val value: Long) : PrefValue
        data class StringV(val value: String) : PrefValue
        data class StringSetV(val value: Set<String>) : PrefValue
        data class BytesV(val value: ByteArray) : PrefValue {
            override fun equals(other: Any?): Boolean =
                other is BytesV && value.contentEquals(other.value)

            override fun hashCode(): Int = value.contentHashCode()
        }
    }

    fun encode(entries: Map<String, PrefValue>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((key, value) in entries) {
            val entry = ByteArrayOutputStream()
            writeField(entry, 1, WireType.LengthDelimited, key.toByteArray(Charsets.UTF_8))
            writeField(entry, 2, WireType.LengthDelimited, encodeValue(value))
            writeField(out, 1, WireType.LengthDelimited, entry.toByteArray())
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): Map<String, PrefValue>? {
        return runCatching {
            val result = linkedMapOf<String, PrefValue>()
            val reader = ProtoReader(bytes)
            while (!reader.isAtEnd) {
                val (field, wire) = reader.readTag()
                if (field == 1 && wire == WireType.LengthDelimited) {
                    readEntry(reader.readBytes())?.let { (key, value) ->
                        result[key] = value
                    }
                } else {
                    reader.skipField(wire)
                }
            }
            result
        }.getOrNull()
    }

    private fun readEntry(bytes: ByteArray): Pair<String, PrefValue>? {
        var key: String? = null
        var value: PrefValue? = null
        val reader = ProtoReader(bytes)
        while (!reader.isAtEnd) {
            val (field, wire) = reader.readTag()
            when {
                field == 1 && wire == WireType.LengthDelimited ->
                    key = reader.readBytes().toString(Charsets.UTF_8)

                field == 2 && wire == WireType.LengthDelimited ->
                    value = decodeValue(reader.readBytes())

                else -> reader.skipField(wire)
            }
        }
        return if (key != null && value != null) key to value else null
    }

    private fun decodeValue(bytes: ByteArray): PrefValue? {
        val reader = ProtoReader(bytes)
        while (!reader.isAtEnd) {
            val (field, wire) = reader.readTag()
            when (field) {
                1 -> if (wire == WireType.Varint) return PrefValue.BooleanV(reader.readVarint() != 0L)
                2 -> if (wire == WireType.Fixed32) return PrefValue.FloatV(reader.readFixed32())
                3 -> if (wire == WireType.Varint) return PrefValue.IntV(reader.readVarint().toInt())
                4 -> if (wire == WireType.Varint) return PrefValue.LongV(reader.readVarint())
                5 -> if (wire == WireType.LengthDelimited) {
                    return PrefValue.StringV(reader.readBytes().toString(Charsets.UTF_8))
                }

                6 -> if (wire == WireType.LengthDelimited) {
                    return PrefValue.StringSetV(decodeStringSet(reader.readBytes()))
                }

                7 -> if (wire == WireType.LengthDelimited) return PrefValue.BytesV(reader.readBytes())
            }
            reader.skipField(wire)
        }
        return null
    }

    private fun decodeStringSet(bytes: ByteArray): Set<String> {
        val result = linkedSetOf<String>()
        val reader = ProtoReader(bytes)
        while (!reader.isAtEnd) {
            val (field, wire) = reader.readTag()
            if (field == 1 && wire == WireType.LengthDelimited) {
                result.add(reader.readBytes().toString(Charsets.UTF_8))
            } else {
                reader.skipField(wire)
            }
        }
        return result
    }

    private fun encodeValue(value: PrefValue): ByteArray {
        val out = ByteArrayOutputStream()
        when (value) {
            is PrefValue.BooleanV ->
                writeField(out, 1, WireType.Varint, if (value.value) 1L else 0L)

            is PrefValue.FloatV ->
                writeField(out, 2, WireType.Fixed32, value.value)

            is PrefValue.IntV ->
                writeField(out, 3, WireType.Varint, value.value.toLong() and 0xFFFFFFFFL)

            is PrefValue.LongV ->
                writeField(out, 4, WireType.Varint, value.value)

            is PrefValue.StringV ->
                writeField(out, 5, WireType.LengthDelimited, value.value.toByteArray(Charsets.UTF_8))

            is PrefValue.StringSetV -> {
                val set = ByteArrayOutputStream()
                for (item in value.value) {
                    writeField(set, 1, WireType.LengthDelimited, item.toByteArray(Charsets.UTF_8))
                }
                writeField(out, 6, WireType.LengthDelimited, set.toByteArray())
            }

            is PrefValue.BytesV ->
                writeField(out, 7, WireType.LengthDelimited, value.value)
        }
        return out.toByteArray()
    }

    private object WireType {
        const val Varint = 0
        const val Fixed32 = 5
        const val LengthDelimited = 2
    }

    private fun writeField(out: ByteArrayOutputStream, field: Int, wireType: Int, value: Long) {
        writeVarint(out, ((field shl 3) or wireType).toLong())
        writeVarint(out, value)
    }

    private fun writeField(out: ByteArrayOutputStream, field: Int, wireType: Int, value: Float) {
        writeVarint(out, ((field shl 3) or wireType).toLong())
        val bits = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(value).array()
        out.write(bits)
    }

    private fun writeField(out: ByteArrayOutputStream, field: Int, wireType: Int, value: ByteArray) {
        writeVarint(out, ((field shl 3) or wireType).toLong())
        writeVarint(out, value.size.toLong())
        out.write(value)
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write((v and 0x7F or 0x80).toInt())
            v = v ushr 7
        }
    }

    private class ProtoReader(private val bytes: ByteArray) {
        var position = 0
        val isAtEnd get() = position >= bytes.size

        fun readTag(): Pair<Int, Int> {
            val tag = readVarint().toInt()
            return (tag ushr 3) to (tag and 0x07)
        }

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (position >= bytes.size || shift >= 64) error("Malformed varint")
                val b = bytes[position++].toLong() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0L) return result
                shift += 7
            }
        }

        fun readFixed32(): Float {
            if (position + 4 > bytes.size) error("Truncated fixed32")
            val value = ByteBuffer.wrap(bytes, position, 4).order(ByteOrder.LITTLE_ENDIAN).float
            position += 4
            return value
        }

        fun readBytes(): ByteArray {
            val length = readVarint().toInt()
            if (length < 0 || position + length > bytes.size) error("Truncated field")
            val value = bytes.copyOfRange(position, position + length)
            position += length
            return value
        }

        fun skipField(wireType: Int) {
            when (wireType) {
                WireType.Varint -> readVarint()
                1 -> position += 8 // fixed64
                WireType.LengthDelimited -> position += readVarint().toInt()
                WireType.Fixed32 -> position += 4
                else -> error("Unknown wire type $wireType")
            }
            if (position > bytes.size) error("Truncated skip")
        }
    }
}
