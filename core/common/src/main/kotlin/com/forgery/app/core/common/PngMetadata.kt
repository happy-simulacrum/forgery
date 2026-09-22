package com.forgery.app.core.common

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.zip.Inflater

/**
 * PNG metadata reader — ports `readPngMetadata` (utils.js): walks PNG chunks
 * and extracts `tEXt` / `zTXt` / `iTXt` text entries (A1111 stores generation
 * parameters in a `parameters` tEXt chunk).
 */
data class PngMetadata(
    val parameters: String?,
    val entries: Map<String, String>,
)

fun readPngMetadata(bytes: ByteArray): PngMetadata? {
    if (bytes.size < 8) return null
    val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    if (!bytes.copyOf(8).contentEquals(signature)) return null
    val entries = linkedMapOf<String, String>()
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
                "tEXt" -> parseKeywordText(data)?.let { entries[it.first] = it.second }
                "zTXt" -> parseCompressedText(data)?.let { entries[it.first] = it.second }
                "iTXt" -> parseInternationalText(data)?.let { entries[it.first] = it.second }
                "IEND" -> break
            }
        }
    } catch (_: Exception) {
        return null
    }
    if (entries.isEmpty()) return PngMetadata(parameters = null, entries = emptyMap())
    return PngMetadata(parameters = entries["parameters"], entries = entries)
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
