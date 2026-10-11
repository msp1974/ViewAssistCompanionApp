package com.msp1974.vacompanion.rtsp

import kotlin.random.Random

/**
 * Packetizes H.264 NAL units into RTP packets per RFC 6184 (single-NAL mode, with FU-A
 * fragmentation for NAL units larger than the MTU), plus a minimal RTCP Sender Report builder.
 * One instance per RTSP session/stream - sequence numbers and SSRC are per-session state.
 */
class RtpH264Packetizer(
    val ssrc: Int = Random.nextInt(),
    private var sequenceNumber: Int = Random.nextInt(0, 0xFFFF),
) {
    var packetCount: Long = 0
        private set
    var octetCount: Long = 0
        private set

    fun toRtpTimestamp(ptsUs: Long): Long = (ptsUs * CLOCK_RATE) / 1_000_000L

    /** Packetizes one access unit (all the NAL units that make up one encoded video frame). */
    fun packetizeAccessUnit(nals: List<ByteArray>, ptsUs: Long): List<ByteArray> {
        val timestamp = toRtpTimestamp(ptsUs)
        val packets = mutableListOf<ByteArray>()
        for ((index, nal) in nals.withIndex()) {
            val isLastNal = index == nals.lastIndex
            if (nal.size <= MTU) {
                packets.add(buildPacket(nal, timestamp, marker = isLastNal))
            } else {
                packets.addAll(fragment(nal, timestamp, markerOnLast = isLastNal))
            }
        }
        return packets
    }

    private fun fragment(nal: ByteArray, timestamp: Long, markerOnLast: Boolean): List<ByteArray> {
        val header = nal[0]
        val nalType = header.toInt() and 0x1F
        val nri = header.toInt() and 0x60
        val payload = nal.copyOfRange(1, nal.size)
        val maxFragmentSize = MTU - FU_HEADER_SIZE

        val packets = mutableListOf<ByteArray>()
        var offset = 0
        var first = true
        while (offset < payload.size) {
            val remaining = payload.size - offset
            val chunkSize = minOf(maxFragmentSize, remaining)
            val isLastFragment = offset + chunkSize >= payload.size

            val fuIndicator = (nri or 28).toByte() // type 28 = FU-A
            val s = if (first) 0x80 else 0
            val e = if (isLastFragment) 0x40 else 0
            val fuHeader = (s or e or nalType).toByte()

            val chunk = ByteArray(FU_HEADER_SIZE + chunkSize)
            chunk[0] = fuIndicator
            chunk[1] = fuHeader
            System.arraycopy(payload, offset, chunk, FU_HEADER_SIZE, chunkSize)

            val marker = isLastFragment && markerOnLast
            packets.add(buildPacket(chunk, timestamp, marker))

            offset += chunkSize
            first = false
        }
        return packets
    }

    private fun buildPacket(payload: ByteArray, timestamp: Long, marker: Boolean): ByteArray {
        val packet = ByteArray(RTP_HEADER_SIZE + payload.size)
        packet[0] = 0x80.toByte() // V=2, P=0, X=0, CC=0
        packet[1] = (((if (marker) 1 else 0) shl 7) or PAYLOAD_TYPE).toByte()

        val seq = sequenceNumber and 0xFFFF
        packet[2] = (seq ushr 8).toByte()
        packet[3] = (seq and 0xFF).toByte()
        sequenceNumber = (sequenceNumber + 1) and 0xFFFF

        val ts = timestamp and 0xFFFFFFFFL
        packet[4] = (ts ushr 24).toByte()
        packet[5] = (ts ushr 16).toByte()
        packet[6] = (ts ushr 8).toByte()
        packet[7] = (ts and 0xFF).toByte()

        writeInt32(packet, 8, ssrc)
        System.arraycopy(payload, 0, packet, RTP_HEADER_SIZE, payload.size)

        packetCount++
        octetCount += payload.size
        return packet
    }

    /** Builds a minimal RTCP Sender Report (no report blocks) for this session's stream. */
    fun buildSenderReport(ntpSeconds: Long, ntpFraction: Long, rtpTimestamp: Long): ByteArray {
        val packet = ByteArray(28)
        packet[0] = 0x80.toByte() // V=2, P=0, RC=0
        packet[1] = 200.toByte() // PT=SR
        val length = (packet.size / 4) - 1
        packet[2] = (length ushr 8).toByte()
        packet[3] = (length and 0xFF).toByte()

        writeInt32(packet, 4, ssrc)
        writeInt32(packet, 8, ntpSeconds.toInt())
        writeInt32(packet, 12, ntpFraction.toInt())
        writeInt32(packet, 16, rtpTimestamp.toInt())
        writeInt32(packet, 20, packetCount.toInt())
        writeInt32(packet, 24, octetCount.toInt())
        return packet
    }

    private fun writeInt32(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value ushr 24).toByte()
        buf[offset + 1] = (value ushr 16).toByte()
        buf[offset + 2] = (value ushr 8).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    companion object {
        const val PAYLOAD_TYPE = 96
        const val CLOCK_RATE = 90_000
        private const val MTU = 1400
        private const val RTP_HEADER_SIZE = 12
        private const val FU_HEADER_SIZE = 2
    }
}
