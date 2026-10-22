package com.mayushi.trailsense.data

import androidx.room.withTransaction
import java.text.Normalizer
import java.util.Locale

data class CompanionExample(val id: Long, val title: String, val startedAt: Long?, val routeTitle: String?, val manualDateEpochDay: Long?)
data class CompanionUsage(val id: Long, val count: Int)
object CompanionIdentity {
    fun key(name: String) = Normalizer.normalize(name.trim(), Normalizer.Form.NFKC).replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    fun validate(name: String) { require(name.trim().length in 1..120) { "Укажи имя до 120 символов" } }
}

/** All collisions require an explicit identity choice; never merge historical people by name. */
class CompanionStore(private val db: TrailDatabase, private val refresh: suspend () -> Unit = {}) {
    private val dao get() = db.trailDao()
    suspend fun create(name: String): Long = db.withTransaction {
        CompanionIdentity.validate(name)
        require(dao.companionsOnce().none { CompanionIdentity.key(it.name)==CompanionIdentity.key(name) }) { "Имя уже есть в списке или истории. Выбери человека либо уточни имя." }
        dao.insertCompanion(CompanionEntity(name=name.trim()))
    }
    suspend fun rename(id: Long,name: String) {
        db.withTransaction {
            CompanionIdentity.validate(name)
            val old=requireNotNull(dao.companion(id))
            require(dao.companionsOnce().none { it.id!=id && CompanionIdentity.key(it.name)==CompanionIdentity.key(name) }) { "Имя уже есть в списке или истории. Уточни имя." }
            dao.updateCompanion(old.copy(name=name.trim()))
            dao.invalidateAllAiCache()
        }
        refresh()
    }
    suspend fun deleted(id: Long, value: Boolean) {
        db.withTransaction {
            val old=requireNotNull(dao.companion(id))
            if (!value) require(dao.companionsOnce().none { it.id!=id && !it.deleted && CompanionIdentity.key(it.name)==CompanionIdentity.key(old.name) }) { "В списке уже есть это имя. Сначала уточни имя одного из людей." }
            dao.updateCompanion(old.copy(deleted=value))
            // No links, names or historical IDs are removed. Historical payload names do not change.
        }
        refresh()
    }
}
