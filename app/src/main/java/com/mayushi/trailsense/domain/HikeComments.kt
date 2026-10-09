package com.mayushi.trailsense.domain

object HikeComments {
    fun merge(comment: String, routeNote: String): String {
        val a = comment.trim(); val b = routeNote.trim()
        return when {
            a.isEmpty() -> b
            b.isEmpty() || a == b || a.endsWith("\n\n" + b) -> a
            else -> a + "\n\n" + b
        }
    }
}
