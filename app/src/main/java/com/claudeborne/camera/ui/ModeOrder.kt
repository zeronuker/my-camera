package com.claudeborne.camera.ui

/**
 * What the strip and the More grid show: groups of modes. A group is one name on the strip; its [members] are the modes behind it,
 * picked with the variant bar above the strip (for example VIDEO holds normal video, slo-mo, time-lapse and Pro video).
 */
internal enum class Group(val label: String, val members: List<Mode>, val variants: List<String>) {
    MASTER("MASTER", listOf(Mode.MASTER), listOf("MASTER")),
    VIDEO("VIDEO", listOf(Mode.VIDEO, Mode.SLOMO, Mode.TIMELAPSE, Mode.PROVIDEO), listOf("NORMAL", "SLO-MO", "TIME-LAPSE", "PRO")),
    PHOTO("PHOTO", listOf(Mode.PHOTO, Mode.NIGHT), listOf("STANDARD", "NIGHT")),
    PORTRAIT("PORTRAIT", listOf(Mode.PORTRAIT), listOf("PORTRAIT")),
    SCAN("SCAN", listOf(Mode.TEXT, Mode.SCAN), listOf("TEXT", "DOCUMENT")),
    CREATIVE("CREATIVE", listOf(Mode.PANO, Mode.LONGEXP, Mode.TILTSHIFT, Mode.UNDERWATER), listOf("PANO", "LONG EXP", "TILT-SHIFT", "UNDERWATER")),
    DUAL("DUAL", listOf(Mode.DUALPHOTO, Mode.DUAL, Mode.QUAD), listOf("PHOTO", "VIDEO", "TRIPLE")),
    ;

    /** The neighbour in the strip (only among the [shown] groups), or null at its ends. */
    fun step(by: Int, shown: List<Group>): Group? = shown.getOrNull(shown.indexOf(this) + by)

    /** The variant name of [mode] (which must be one of [members]). */
    fun variantOf(mode: Mode): String = variants[members.indexOf(mode).coerceAtLeast(0)]
}

internal val Mode.group: Group get() = Group.entries.first { this in it.members }

/**
 * The order of the groups. The first [PINNED] of the groups the phone can do sit in the strip under the shutter, the rest are in the
 * More grid. The owner's order is saved as text (group names, comma separated).
 */
internal object ModeOrder {
    const val PINNED = 5

    private val DEFAULT = Group.entries.toList()

    /** The saved order; names that no longer exist are dropped, groups that are not in the text come last in their default order. */
    fun parse(saved: String): List<Group> {
        val named = saved.split(',').mapNotNull { n -> Group.entries.firstOrNull { it.name == n.trim() } }.distinct()
        return named + DEFAULT.filter { it !in named }
    }

    fun encode(order: List<Group>): String = order.joinToString(",") { it.name }

    /** [group] one place earlier ([by] = -1) or later (1) among the [shown] groups; groups the phone cannot do keep their places. */
    fun move(order: List<Group>, shown: List<Group>, group: Group, by: Int): List<Group> {
        val at = shown.indexOf(group)
        val other = shown.getOrNull(at + by) ?: return order
        if (at < 0) return order
        return order.toMutableList().also { java.util.Collections.swap(it, it.indexOf(group), it.indexOf(other)) }
    }

    /** The groups that sit in the strip. */
    fun strip(shown: List<Group>): List<Group> = shown.take(PINNED)

    /** The groups in the More grid; empty when everything fits in the strip. */
    fun more(shown: List<Group>): List<Group> = shown.drop(PINNED)
}
