package com.claudeborne.camera.scan

/** A rectangle as fractions of the picture: 0,0 is the top left, 1,1 the bottom right. */
data class Frac(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val area get() = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
}

/** What a scanned QR code says and what the chip may do with it. Nothing is ever opened without a tap. */
object QrRules {
    private val OPENABLE = setOf("http", "https", "tel", "mailto", "sms", "smsto", "geo")

    private fun scheme(payload: String): String? {
        val colon = payload.indexOf(':')
        if (colon <= 0) return null
        val s = payload.substring(0, colon).lowercase()
        return if (s.all { it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '-' || it == '.' }) s else null
    }

    fun canOpen(payload: String): Boolean = scheme(payload.trim()) in OPENABLE

    /** A short name for the kind of code, shown as the chip's label. */
    fun kind(payload: String): String = when (scheme(payload.trim())) {
        "http", "https" -> "LINK"
        "tel" -> "PHONE"
        "mailto" -> "EMAIL"
        "sms", "smsto" -> "SMS"
        "geo" -> "PLACE"
        "wifi" -> "WI-FI"
        else -> "TEXT"
    }

    /** What the chip shows: a Wi-Fi code shows only the network name, long text is cut. */
    fun shown(payload: String, max: Int = 70): String {
        val p = payload.trim()
        if (scheme(p) == "wifi") {
            val name = Regex("S:((?:\\\\.|[^;])*);", RegexOption.IGNORE_CASE).find(p)?.groupValues?.get(1)?.replace(Regex("\\\\(.)"), "$1")
            return name?.let { "Network: $it" } ?: "Wi-Fi network"
        }
        return if (p.length <= max) p else p.take(max - 1) + "…"
    }
}

/** Decides from the text found in a picture whether to suggest Text mode, and where the live outline goes. */
object TextRules {
    const val MIN_CHARS = 20
    const val MIN_COVER = 0.08f // of the picture, by the lines of text

    /** Text fills enough of the frame to be worth scanning. */
    fun recommend(lines: List<Frac>, chars: Int): Boolean = chars >= MIN_CHARS && lines.sumOf { it.area.toDouble() } >= MIN_COVER

    /** One box around all the text, a little bigger, or null when there is too little text to outline. */
    fun outline(lines: List<Frac>, chars: Int): Frac? {
        if (lines.isEmpty() || chars < 6) return null
        val pad = 0.025f
        return Frac(
            (lines.minOf { it.left } - pad).coerceAtLeast(0f), (lines.minOf { it.top } - pad).coerceAtLeast(0f),
            (lines.maxOf { it.right } + pad).coerceAtMost(1f), (lines.maxOf { it.bottom } + pad).coerceAtMost(1f),
        )
    }

    fun hasCjk(s: String) = s.any { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }

    /** The Chinese recogniser also reads Latin, but the Latin one is better at it: use Chinese only when it really found Chinese. */
    fun pick(latin: String, chinese: String): String = if (hasCjk(chinese)) chinese else latin.ifBlank { chinese }
}
