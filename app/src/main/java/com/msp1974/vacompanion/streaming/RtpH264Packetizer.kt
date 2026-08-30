package com.msp1974.vacompanion.streaming

import kotlin.random.Random

/**
 * Builds RTP packets (RFC 3550) carrying H.264 Access Units per RFC 6184 - Single NAL
 * Unit packets when a NAL fits in one packet, FU-A fragmentation otherwise (STAP-A/B and
 * MTAP-A/B aggregation are not needed for this single-stream use case).
 *
 * One instance is used for the lifetime of a single RTSP viewer session so that sequence
 * numbers and the SSRC stay consistent for that session.
 */
class RtpH264Packetizer(
    private val payloadType: Int = DEFAULT_PAYLOAD_TYPE,
    private val ssrc: Int = Random.nextInt(),
    private val maxPayloadSize: Int = DEFAULT_MAX_PAYLOAD_SIZE,
) {
    private var sequenceNumber: Int = Random.nextInt(0, 0xFFFF)

    /**
     * Packetizes one H.264 NAL unit (Annex-B start code already stripped) into one or
     * more RTP packets. [isLastNalInAccessUnit] should be true only for the final NAL of
     * an access unit (video frame) so the RTP marker bit is set correctly.
     */
    fun packetize(nal: ByteArray, timestamp90k: Long, isLastNalInAccessUnit: Boolean): List<ByteArray> {
        if (nal.isEmpty()) return emptyList()

        return if (nal.size <= maxPayloadSize) {
            listOf(buildPacket(nal, timestamp90k, marker = isLastNalInAccessUnit))
        } else {
            fragment(nal, timestamp90k, isLastNalInAccessUnit)
        }
    }

    private fun fragment(nal: ByteArray, timestamp90k: Long, isLastNalInAccessUnit: Boolean): List<ByteArray> {
        val nalHeader = nal[0].toInt() and 0xFF
        val nalType = nalHeader and 0x1F
        val nalRefIdc = nalHeader and 0x60
        val payload = nal.copyOfRange(1, nal.size) // NAL payload without the 1-byte header

        val fuIndicator = (nalRefIdc or FU_A_INDICATOR_TYPE).toByte()
        val chunkSize = maxPayloadSize - FU_A_HEADER_SIZE
        val packets = mutableListOf<ByteArray>()

        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + chunkSize, payload.size)
            val isFirst = offset == 0
            val isLast = end == payload.size

            var fuHeader = nalType
            if (isFirst) fuHeader = fuHeader or 0x80
            if (isLast) fuHeader = fuHeader or 0x40

            val fragment = ByteArray(FU_A_HEADER_SIZE + (end - offset))
            fragment[0] = fuIndicator
            fragment[1] = fuHeader.toByte()
            System.arraycopy(payload, offset, fragment, FU_A_HEADER_SIZE, end - offset)

            packets.add(buildPacket(fragment, timestamp90k, marker = isLast && isLastNalInAccessUnit))
            offset = end
        }
        return packets
    }

    private fun buildPacket(payload: ByteArray, timestamp90k: Long, marker: Boolean): ByteArray {
        val packet = ByteArray(RTP_HEADER_SIZE + payload.size)
        packet[0] = 0x80.toByte() // V=2, P=0, X=0, CC=0
        packet[1] = ((if (marker) 0x80 else 0x00) or (payloadType and 0x7F)).toByte()
        packet[2] = (sequenceNumber shr 8).toByte()
        packet[3] = (sequenceNumber and 0xFF).toByte()
        writeUInt32(packet, 4, timestamp90k and 0xFFFFFFFFL)
        writeUInt32(packet, 8, ssrc.toLong() and 0xFFFFFFFFL)
        System.arraycopy(payload, 0, packet, RTP_HEADER_SIZE, payload.size)
        sequenceNumber = (sequenceNumber + 1) and 0xFFFF
        return packet
    }

    private fun writeUInt32(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = (value shr 24).toByte()
        buf[offset + 1] = (value shr 16).toByte()
        buf[offset + 2] = (value shr 8).toByte()
        buf[offset + 3] = value.toByte()
    }

    companion object {
        const val DEFAULT_PAYLOAD_TYPE = 96
        // Comfortably under a typical 1500-byte Ethernet/Wi-Fi MTU once RTP (12 bytes)
        // and the interleaved TCP framing (4 bytes) are added on top.
        const val DEFAULT_MAX_PAYLOAD_SIZE = 1400
        private const val RTP_HEADER_SIZE = 12
        private const val FU_A_HEADER_SIZE = 2
        private const val FU_A_INDICATOR_TYPE = 28
    }
}
