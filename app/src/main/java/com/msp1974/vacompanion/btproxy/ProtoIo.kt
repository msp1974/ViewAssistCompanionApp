package com.msp1974.vacompanion.btproxy

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal protobuf (proto3) wire-format writer. The ESPHome native API only needs varints,
 * length-delimited fields and packed repeated varints, so this avoids pulling in the protobuf
 * runtime. Default values (0, false, empty) are omitted as proto3 requires.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun tag(field: Int, wireType: Int) = writeVarint(((field shl 3) or wireType).toLong())

    private fun writeVarint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun uint(field: Int, value: Long): ProtoWriter {
        if (value != 0L) {
            tag(field, 0)
            writeVarint(value)
        }
        return this
    }

    fun uint(field: Int, value: Int): ProtoWriter = uint(field, value.toLong() and 0xFFFFFFFFL)

    /** Plain `int32` (negative values are sign-extended to 10 bytes). */
    fun int32(field: Int, value: Int): ProtoWriter = uint(field, value.toLong())

    fun sint32(field: Int, value: Int): ProtoWriter =
        uint(field, ((value shl 1) xor (value shr 31)).toLong() and 0xFFFFFFFFL)

    fun bool(field: Int, value: Boolean): ProtoWriter = if (value) uint(field, 1L) else this

    fun string(field: Int, value: String): ProtoWriter =
        if (value.isEmpty()) this else bytes(field, value.toByteArray(Charsets.UTF_8))

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        if (value.isNotEmpty()) {
            tag(field, 2)
            writeVarint(value.size.toLong())
            out.write(value)
        }
        return this
    }

    /** Embedded message; always written, even if empty (repeated entries must keep position). */
    fun message(field: Int, value: ByteArray): ProtoWriter {
        tag(field, 2)
        writeVarint(value.size.toLong())
        out.write(value)
        return this
    }

    fun packedUInt64(field: Int, values: List<Long>): ProtoWriter {
        if (values.isEmpty()) return this
        val inner = ProtoWriter()
        values.forEach { inner.writeVarint(it) }
        return bytes(field, inner.toByteArray())
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Decoded fields of one protobuf message. Unknown/fixed-width fields are skipped. */
class ProtoFields(private val varints: Map<Int, Long>, private val blobs: Map<Int, ByteArray>) {
    fun long(field: Int): Long = varints[field] ?: 0L
    fun int(field: Int): Int = (varints[field] ?: 0L).toInt()
    fun bool(field: Int): Boolean = (varints[field] ?: 0L) != 0L
    fun bytes(field: Int): ByteArray = blobs[field] ?: ByteArray(0)
    fun string(field: Int): String = String(bytes(field), Charsets.UTF_8)

    companion object {
        val EMPTY = ProtoFields(emptyMap(), emptyMap())

        fun parse(data: ByteArray): ProtoFields {
            val varints = HashMap<Int, Long>()
            val blobs = HashMap<Int, ByteArray>()
            var pos = 0

            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (pos >= data.size || shift > 63) throw IOException("Malformed varint")
                    val b = data[pos++].toInt()
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
            }

            while (pos < data.size) {
                val key = readVarint().toInt()
                val field = key ushr 3
                when (key and 7) {
                    0 -> varints[field] = readVarint()
                    1 -> pos += 8
                    2 -> {
                        val len = readVarint().toInt()
                        if (len < 0 || pos + len > data.size) throw IOException("Malformed field length")
                        blobs[field] = data.copyOfRange(pos, pos + len)
                        pos += len
                    }
                    5 -> pos += 4
                    else -> throw IOException("Unsupported wire type ${key and 7}")
                }
            }
            return ProtoFields(varints, blobs)
        }
    }
}

/** Varint helpers for the plaintext API frame header. */
internal fun InputStream.readFrameVarint(): Int {
    var result = 0
    var shift = 0
    while (true) {
        val b = read()
        if (b < 0) throw IOException("Connection closed")
        result = result or ((b and 0x7F) shl shift)
        if (b and 0x80 == 0) return result
        shift += 7
        if (shift > 28) throw IOException("Frame varint too long")
    }
}

internal fun OutputStream.writeFrameVarint(value: Int) {
    var v = value
    while (v and 0x7F.inv() != 0) {
        write((v and 0x7F) or 0x80)
        v = v ushr 7
    }
    write(v)
}
