package com.msp1974.vacompanion.rtsp

/** Minimal Annex-B H.264 NAL unit helpers, shared by [H264Encoder] and [RtspSession]. */
object NalUtils {

    private data class StartCode(val codeStart: Int, val nalStart: Int)

    /** Splits an Annex-B byte stream (one or more "00 00 01"/"00 00 00 01"-prefixed NAL units)
     *  into individual NAL units, each still including its 1-byte header but with the start
     *  code stripped. */
    fun splitNalUnits(data: ByteArray): List<ByteArray> {
        if (data.isEmpty()) return emptyList()

        val codes = mutableListOf<StartCode>()
        var i = 0
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    codes.add(StartCode(i, i + 3))
                    i += 3
                    continue
                } else if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    codes.add(StartCode(i, i + 4))
                    i += 4
                    continue
                }
            }
            i++
        }
        if (codes.isEmpty()) return emptyList()

        val nals = mutableListOf<ByteArray>()
        for (idx in codes.indices) {
            val nalStart = codes[idx].nalStart
            // A NAL unit ends exactly where the next start code begins - no guessing needed.
            val nalEnd = if (idx + 1 < codes.size) codes[idx + 1].codeStart else data.size
            if (nalEnd > nalStart) nals.add(data.copyOfRange(nalStart, nalEnd))
        }
        return nals
    }

    fun nalType(nal: ByteArray): Int = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1F

    /** Finds the SPS (type 7) and PPS (type 8) NAL units within a codec-config blob. */
    fun extractSpsPps(configBytes: ByteArray): Pair<ByteArray?, ByteArray?> {
        val nals = splitNalUnits(configBytes)
        val sps = nals.find { nalType(it) == 7 }
        val pps = nals.find { nalType(it) == 8 }
        return sps to pps
    }
}
