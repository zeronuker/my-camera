package com.claudeborne.camera.ui

/**
 * The order of the shooting modes. The first [PINNED] of the modes the phone can do sit in the strip under the shutter,
 * the rest are in the More grid. The owner's order is saved as text (mode names, comma separated).
 */
internal object ModeOrder {
    const val PINNED = 5

    /** The stock order first (MASTER, VIDEO, PHOTO, PORTRAIT, TEXT stay in the strip), then everything else as listed in [Mode]. */
    private val DEFAULT = Mode.entries.toList()

    /** The saved order; names that no longer exist are dropped, modes that are not in the text come last in their default order. */
    fun parse(saved: String): List<Mode> {
        val named = saved.split(',').mapNotNull { n -> Mode.entries.firstOrNull { it.name == n.trim() } }.distinct()
        return named + DEFAULT.filter { it !in named }
    }

    fun encode(order: List<Mode>): String = order.joinToString(",") { it.name }

    /** [mode] one place earlier ([by] = -1) or later (1) among the [shown] modes; modes the phone cannot do keep their places. */
    fun move(order: List<Mode>, shown: List<Mode>, mode: Mode, by: Int): List<Mode> {
        val at = shown.indexOf(mode)
        val other = shown.getOrNull(at + by) ?: return order
        if (at < 0) return order
        return order.toMutableList().also { java.util.Collections.swap(it, it.indexOf(mode), it.indexOf(other)) }
    }

    /** The modes that sit in the strip. */
    fun strip(shown: List<Mode>): List<Mode> = shown.take(PINNED)

    /** The modes in the More grid; empty when everything fits in the strip. */
    fun more(shown: List<Mode>): List<Mode> = shown.drop(PINNED)
}
