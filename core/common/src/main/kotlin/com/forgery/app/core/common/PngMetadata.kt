package com.forgery.app.core.common

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.zip.Inflater
import kotlin.math.round

/**
 * PNG metadata reader — ports `readPngMetadata` (utils.js): walks PNG chunks
 * and extracts `tEXt` / `zTXt` / `iTXt` text entries (A1111 stores generation
 * parameters in a `parameters` tEXt chunk).
 */
data class PngMetadata(
    val parameters: String?,
    val entries: Map<String, String>,
)

enum class FileFormat {
    PNG,
    JPEG,
    WEBP,
    UNKNOWN,
}

data class FileInfo(
    val format: FileFormat,
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
    val dpiX: Double?,
    val dpiY: Double?,
    val bitDepth: Int? = null,
    val colorType: Int? = null,
)

private data class IhdrData(
    val width: Int,
    val height: Int,
    val bitDepth: Int,
    val colorType: Int,
)

private data class PhysData(
    val unit: Int,
    val ppux: Long,
    val ppuy: Long,
)

private fun parseIhdr(data: ByteArray): IhdrData? {
    if (data.size < 13) return null
    val width = ((data[0].toInt() and 0xFF) shl 24) or
        ((data[1].toInt() and 0xFF) shl 16) or
        ((data[2].toInt() and 0xFF) shl 8) or
        (data[3].toInt() and 0xFF)
    val height = ((data[4].toInt() and 0xFF) shl 24) or
        ((data[5].toInt() and 0xFF) shl 16) or
        ((data[6].toInt() and 0xFF) shl 8) or
        (data[7].toInt() and 0xFF)
    val bitDepth = data[8].toInt() and 0xFF
    val colorType = data[9].toInt() and 0xFF
    return IhdrData(width, height, bitDepth, colorType)
}

private fun parsePhys(data: ByteArray): PhysData? {
    if (data.size < 9) return null
    val ppux = ((data[0].toLong() and 0xFF) shl 24) or
        ((data[1].toLong() and 0xFF) shl 16) or
        ((data[2].toLong() and 0xFF) shl 8) or
        (data[3].toLong() and 0xFF)
    val ppuy = ((data[4].toLong() and 0xFF) shl 24) or
        ((data[5].toLong() and 0xFF) shl 16) or
        ((data[6].toLong() and 0xFF) shl 8) or
        (data[7].toLong() and 0xFF)
    val unit = data[8].toInt() and 0xFF
    return PhysData(unit, ppux, ppuy)
}

private fun ppmToDpi(ppm: Long): Double = round(ppm * 0.0254 * 10.0) / 10.0

fun readPngMetadata(bytes: ByteArray): PngMetadata? {
    if (bytes.size < 8) return null
    val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    if (!bytes.copyOf(8).contentEquals(signature)) return null
    val entries = linkedMapOf<String, String>()
    var ihdr: IhdrData? = null
    var phys: PhysData? = null
    val input = DataInputStream(ByteArrayInputStream(bytes, 8, bytes.size - 8))
    try {
        while (input.available() > 0) {
            if (input.available() < 8) break
            val length = input.readInt()
            if (length < 0 || length > 32 * 1024 * 1024) break
            val typeBytes = ByteArray(4)
            input.readFully(typeBytes)
            val type = typeBytes.toString(Charsets.US_ASCII)
            val data = ByteArray(length)
            if (length > 0) input.readFully(data)
            input.readInt() // CRC, ignored
            when (type) {
                "IHDR" -> if (ihdr == null) ihdr = parseIhdr(data)
                "pHYs" -> if (phys == null) phys = parsePhys(data)
                "tEXt" -> parseKeywordText(data)?.let { entries[it.first] = it.second }
                "zTXt" -> parseCompressedText(data)?.let { entries[it.first] = it.second }
                "iTXt" -> parseInternationalText(data)?.let { entries[it.first] = it.second }
                "IEND" -> break
            }
        }
    } catch (_: Exception) {
        return null
    }
    // IHDR/pHYs are captured in the same chunk walk for fileInfo(); text result is unchanged.
    @Suppress("UNUSED_VARIABLE")
    val fileHeaders = ihdr to phys
    if (entries.isEmpty()) return PngMetadata(parameters = null, entries = emptyMap())
    return PngMetadata(parameters = entries["parameters"], entries = entries)
}

/**
 * File-level info by signature: PNG dimensions/DPI from IHDR/pHYs,
 * JPEG dimensions from SOF0/1/2 scan, WebP without dimension parsing.
 * Returns null only when [bytes] is empty.
 */
fun fileInfo(bytes: ByteArray): FileInfo? {
    if (bytes.isEmpty()) return null
    val sizeBytes = bytes.size.toLong()
    val format = detectFormat(bytes)
    return when (format) {
        FileFormat.PNG -> {
            val (ihdr, phys) = parsePngHeaders(bytes)
            val dpiX = if (phys != null && phys.unit == 1) ppmToDpi(phys.ppux) else null
            val dpiY = if (phys != null && phys.unit == 1) ppmToDpi(phys.ppuy) else null
            FileInfo(
                format = FileFormat.PNG,
                sizeBytes = sizeBytes,
                width = ihdr?.width,
                height = ihdr?.height,
                dpiX = dpiX,
                dpiY = dpiY,
                bitDepth = ihdr?.bitDepth,
                colorType = ihdr?.colorType,
            )
        }
        FileFormat.JPEG -> {
            val dims = parseJpegDimensions(bytes)
            FileInfo(
                format = FileFormat.JPEG,
                sizeBytes = sizeBytes,
                width = dims?.first,
                height = dims?.second,
                dpiX = null,
                dpiY = null,
            )
        }
        FileFormat.WEBP, FileFormat.UNKNOWN -> FileInfo(
            format = format,
            sizeBytes = sizeBytes,
            width = null,
            height = null,
            dpiX = null,
            dpiY = null,
        )
    }
}

private fun detectFormat(bytes: ByteArray): FileFormat {
    if (isPng(bytes)) return FileFormat.PNG
    if (isJpeg(bytes)) return FileFormat.JPEG
    if (isWebp(bytes)) return FileFormat.WEBP
    return FileFormat.UNKNOWN
}

private fun isPng(bytes: ByteArray): Boolean {
    if (bytes.size < 8) return false
    val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    return bytes.copyOf(8).contentEquals(signature)
}

private fun isJpeg(bytes: ByteArray): Boolean {
    if (bytes.size < 2) return false
    return (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8
}

private fun isWebp(bytes: ByteArray): Boolean {
    if (bytes.size < 12) return false
    val riff = String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF"
    val webp = String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
    return riff && webp
}

/** Lightweight PNG header scan: IHDR + pHYs only, no text decompression. */
private fun parsePngHeaders(bytes: ByteArray): Pair<IhdrData?, PhysData?> {
    var ihdr: IhdrData? = null
    var phys: PhysData? = null
    try {
        if (!isPng(bytes)) return null to null
        var pos = 8
        while (pos + 8 <= bytes.size) {
            val length = ((bytes[pos].toInt() and 0xFF) shl 24) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            if (length < 0 || length > 32 * 1024 * 1024) break
            if (pos + 8 + length + 4 > bytes.size) break
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val dataStart = pos + 8
            when (type) {
                "IHDR" -> if (ihdr == null && length >= 13) {
                    ihdr = parseIhdr(bytes.copyOfRange(dataStart, dataStart + length))
                }
                "pHYs" -> if (phys == null && length >= 9) {
                    phys = parsePhys(bytes.copyOfRange(dataStart, dataStart + length))
                }
                "IEND" -> break
            }
            pos = dataStart + length + 4
            if (ihdr != null && phys != null) break
        }
    } catch (_: Exception) {
        // Return whatever was parsed before the failure.
    }
    return ihdr to phys
}

/**
 * Scans JPEG markers until SOF0/1/2 (FF C0/C1/C2). Markers without length
 * (SOI, RSTn, TEM) are skipped, EOI/SOS stop the scan. Returns width to height.
 */
private fun parseJpegDimensions(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 4) return null
    if (!isJpeg(bytes)) return null
    var pos = 2
    try {
        while (pos + 1 < bytes.size) {
            if ((bytes[pos].toInt() and 0xFF) != 0xFF) {
                pos++
                continue
            }
            var markerPos = pos + 1
            while (markerPos < bytes.size && (bytes[markerPos].toInt() and 0xFF) == 0xFF) {
                markerPos++
            }
            if (markerPos >= bytes.size) break
            val marker = bytes[markerPos].toInt() and 0xFF
            pos = markerPos + 1
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) {
                continue
            }
            if (marker == 0xD9 || marker == 0x00) {
                if (marker == 0xD9) break else continue
            }
            if (pos + 1 >= bytes.size) break
            val len = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
            if (len < 2) break
            if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {
                if (len < 8) break
                if (pos + 7 >= bytes.size) break
                val h = ((bytes[pos + 3].toInt() and 0xFF) shl 8) or (bytes[pos + 4].toInt() and 0xFF)
                val w = ((bytes[pos + 5].toInt() and 0xFF) shl 8) or (bytes[pos + 6].toInt() and 0xFF)
                if (w == 0 || h == 0) return null
                return w to h
            }
            if (marker == 0xDA) break
            pos += len
        }
    } catch (_: Exception) {
        return null
    }
    return null
}

private fun parseKeywordText(data: ByteArray): Pair<String, String>? {
    val sep = data.indexOf(0.toByte())
    if (sep <= 0) return null
    val keyword = data.copyOf(sep).toString(Charsets.ISO_8859_1)
    val text = data.copyOfRange(sep + 1, data.size).toString(Charsets.ISO_8859_1)
    return keyword to text
}

private fun parseCompressedText(data: ByteArray): Pair<String, String>? {
    val sep = data.indexOf(0.toByte())
    if (sep <= 0 || sep + 2 > data.size) return null
    val keyword = data.copyOf(sep).toString(Charsets.ISO_8859_1)
    // data[sep+1] is the compression method (0 = deflate).
    val compressed = data.copyOfRange(sep + 2, data.size)
    return try {
        val inflater = Inflater()
        inflater.setInput(compressed)
        val out = ByteArray(compressed.size * 8 + 64)
        val len = inflater.inflate(out)
        inflater.end()
        keyword to out.copyOf(len).toString(Charsets.ISO_8859_1)
    } catch (_: Exception) {
        null
    }
}

private fun parseInternationalText(data: ByteArray): Pair<String, String>? {
    // keyword \0 compFlag compMethod lang\0 translatedKeyword\0 text
    val keyEnd = data.indexOf(0.toByte())
    if (keyEnd <= 0 || keyEnd + 3 > data.size) return null
    val keyword = data.copyOf(keyEnd).toString(Charsets.UTF_8)
    val compressed = data[keyEnd + 1] == 1.toByte()
    var pos = keyEnd + 3
    val langEnd = data.indexOfFrom(0.toByte(), pos)
    if (langEnd < 0) return null
    pos = langEnd + 1
    val tkeyEnd = data.indexOfFrom(0.toByte(), pos)
    if (tkeyEnd < 0) return null
    pos = tkeyEnd + 1
    val raw = data.copyOfRange(pos, data.size)
    val text = if (compressed) {
        try {
            val inflater = Inflater()
            inflater.setInput(raw)
            val out = ByteArray(raw.size * 8 + 64)
            val len = inflater.inflate(out)
            inflater.end()
            out.copyOf(len).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
    } else {
        raw.toString(Charsets.UTF_8)
    }
    return keyword to text
}

private fun ByteArray.indexOf(target: Byte, from: Int = 0): Int {
    for (i in from until size) if (this[i] == target) return i
    return -1
}

private fun ByteArray.indexOfFrom(target: Byte, from: Int): Int = indexOf(target, from)

/**
 * Splits an A1111 `parameters` block into prompt / negative prompt.
 * Format: "<prompt>\nNegative prompt: <neg>\nSteps: ...".
 */
fun parseA1111Parameters(raw: String): Pair<String, String> {
    val negMarker = "\nNegative prompt:"
    val negIndex = raw.indexOf(negMarker)
    return if (negIndex < 0) {
        // No negative section: cut generation settings tail if present.
        val cut = cutSettingsTail(raw.trim())
        cut to ""
    } else {
        val prompt = raw.substring(0, negIndex).trim()
        val rest = raw.substring(negIndex + negMarker.length)
        val neg = cutSettingsTail(rest).trim()
        prompt to neg
    }
}

private fun cutSettingsTail(text: String): String {
    // Settings tail starts at a line like "Steps: 20, ...".
    val lines = text.trim().lines()
    val cutAt = lines.indexOfFirst { it.startsWith("Steps:") }
    return if (cutAt < 0) text.trim() else lines.subList(0, cutAt).joinToString("\n").trim()
}

data class A1111Settings(
    val steps: Int? = null,
    val sampler: String? = null,
    val scheduler: String? = null,
    val cfg: Double? = null,
    val seed: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val model: String? = null,
    val hrEnable: Boolean = false,
    val hrUpscaler: String? = null,
    val hrScale: Double? = null,
    val hrSteps: Int? = null,
    val hrDenoise: Double? = null,
)

fun parseA1111Settings(raw: String): A1111Settings {
    return try {
        val stepsIndex = raw.indexOf("Steps:")
        if (stepsIndex < 0) return A1111Settings()
        val tail = raw.substring(stepsIndex)

        fun group(pattern: String, options: Set<RegexOption> = emptySet()): String? =
            Regex(pattern, options).find(tail)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

        val steps = group("""Steps:\s*(\d+)""")?.toIntOrNull()
        val sampler = group("""Sampler:\s*([^,]+)""")
        val scheduler = group("""Schedule type:\s*([^,]+)""")
        val cfg = group("""CFG scale:\s*([\d.]+)""")?.toDoubleOrNull()
        val seed = group("""Seed:\s*(-?\d+)""")?.toLongOrNull()
        val sizeMatch = Regex("""Size:\s*(\d+)x(\d+)""").find(tail)
        val width = sizeMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        val height = sizeMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
        val model = group("""(?:^|,)\s*Model:\s*([^,]+)""", setOf(RegexOption.MULTILINE))

        val hrScaleRaw = group("""Hires upscale:\s*([\d.]+)""")
        val hrStepsRaw = group("""Hires steps:\s*(\d+)""")
        val hrUpscaler = group("""Hires upscaler:\s*([^,]+)""")
        val hrDenoiseRaw = group("""Denoising strength:\s*([\d.]+)""")
        val hrEnable = hrScaleRaw != null || hrStepsRaw != null || hrUpscaler != null || hrDenoiseRaw != null

        A1111Settings(
            steps = steps,
            sampler = sampler,
            scheduler = scheduler,
            cfg = cfg,
            seed = seed,
            width = width,
            height = height,
            model = model,
            hrEnable = hrEnable,
            hrUpscaler = hrUpscaler,
            hrScale = hrScaleRaw?.toDoubleOrNull(),
            hrSteps = hrStepsRaw?.toIntOrNull(),
            hrDenoise = hrDenoiseRaw?.toDoubleOrNull(),
        )
    } catch (_: Exception) {
        A1111Settings()
    }
}
