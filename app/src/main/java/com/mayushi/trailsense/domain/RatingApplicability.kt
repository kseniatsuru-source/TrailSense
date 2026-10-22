package com.mayushi.trailsense.domain

/** Explicit N/A markers in existing audit storage, without rewriting legacy ratings. */
object RatingApplicability {
    fun states(history:String):Map<String,String> = history.lineSequence().filter {
        it.startsWith("rating-state|") || it.startsWith("q037|rating-state|") || it.startsWith("q038|rating-state|")
    }.mapNotNull {
        val parts=it.substringAfter("rating-state|").split('|')
        if(parts.size==2 && parts[1] in listOf("NA","ANSWERED","UNANSWERED")) parts[0] to parts[1] else null
    }.toMap()
    fun save(history:String,states:Map<String,String>):String {
        val previous=RatingApplicability.states(history)
        return states.entries.filter{previous[it.key]!=it.value}.fold(history) {h,e ->
            listOf(h.trimEnd(),"q038|rating-state|${e.key}|${e.value}").filter{it.isNotEmpty()}.joinToString("\n")
        }
    }
}
