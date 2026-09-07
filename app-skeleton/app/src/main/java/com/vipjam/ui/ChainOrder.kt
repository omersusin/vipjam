package com.vipjam.ui

import com.vipjam.effect.VipJamEffects

object ChainOrder {
    const val LIMITER_GROUP = VipJamEffects.MASTER_LIMITER

    val DEFAULT_DISPLAY_ORDER: List<String> =
        (HYBRID_GROUPS - LIMITER_GROUP) + LIMITER_GROUP

    fun encode(order: List<String>): String = order.joinToString(";")

    fun sanitize(stored: String?): List<String> {
        val known = HYBRID_GROUPS.toSet()
        val seen = LinkedHashSet<String>()
        stored?.split(";")?.forEach {
            val g = it.trim()
            if (g in known) seen.add(g)
        }
        DEFAULT_DISPLAY_ORDER.forEach { seen.add(it) }
        seen.remove(LIMITER_GROUP)
        seen.add(LIMITER_GROUP)
        return seen.toList()
    }

    fun move(order: List<String>, group: String, delta: Int): List<String> {
        if (group == LIMITER_GROUP || delta == 0) return order
        val idx = order.indexOf(group)
        if (idx < 0) return order
        val last = order.size - 1
        val target = (idx + delta).coerceIn(0, last - 1)
        if (target == idx) return order
        val next = order.toMutableList()
        next.removeAt(idx)
        next.add(target, group)
        return next
    }

    fun sortForDisplay(groups: List<String>, order: List<String>): List<String> {
        val rank = order.withIndex().associate { it.value to it.index }
        return groups.sortedWith(
            compareBy(
                { if (it == LIMITER_GROUP) 1 else 0 },
                { rank[it] ?: Int.MAX_VALUE },
            ),
        )
    }
}
