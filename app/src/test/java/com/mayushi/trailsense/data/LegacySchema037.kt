package com.mayushi.trailsense.data

import android.database.sqlite.SQLiteDatabase

/** Reconstruct an actual pre-037 schema, rather than merely lowering user_version. */
internal fun SQLiteDatabase.remove037Schema() {
    execSQL("PRAGMA foreign_keys=OFF")
    fun rebuild(table: String, removed: Set<String>) {
        val schema = rawQuery("SELECT sql FROM sqlite_master WHERE name=?", arrayOf(table)).use { it.moveToFirst(); it.getString(0) }
        val fields = schema.substringAfter('(').substringBeforeLast(')').split(", ").filterNot { field -> removed.any { field.startsWith("`$it` ") } }
        execSQL("CREATE TABLE ${table}_pre037 (" + fields.joinToString(", ") + ")")
        val names = rawQuery("PRAGMA table_info(${table}_pre037)", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }.joinToString(",") { "`$it`" }
        execSQL("INSERT INTO ${table}_pre037 ($names) SELECT $names FROM $table")
        execSQL("DROP TABLE $table")
        execSQL("ALTER TABLE ${table}_pre037 RENAME TO $table")
    }
    rebuild("pill_plans", setOf("subBlockDays", "subBlockCount"))
    remove041AnalysisSchema()
    rebuild("activities", setOf("sleepOcrMinutes", "sleepOcrStatus", "questionnaireHistory"))
    execSQL("CREATE INDEX index_activities_routeId ON activities(routeId)")
    rebuild("calendar_context", setOf("ongoing"))
    execSQL("CREATE INDEX index_calendar_context_startEpochDay ON calendar_context(startEpochDay)")
    execSQL("CREATE INDEX index_calendar_context_endEpochDay ON calendar_context(endEpochDay)")
    execSQL("DROP TABLE pill_plan_versions")
}

/** Strip only additive040 pill fields when constructing a real older test schema. */
internal fun SQLiteDatabase.remove040PillSchema() {
    for(table in listOf("pill_plans","pill_plan_versions")) {
        val schema=rawQuery("SELECT sql FROM sqlite_master WHERE name=?",arrayOf(table)).use {it.moveToFirst();it.getString(0)}
        val fields=schema.substringAfter('(').substringBeforeLast(')').split(", ").filterNot {it.startsWith("`subBlockDays` ") || it.startsWith("`subBlockCount` ")}
        execSQL("CREATE TABLE ${table}_pre040 ("+fields.joinToString(", ")+")")
        val names=rawQuery("PRAGMA table_info(${table}_pre040)",null).use {c->buildList {while(c.moveToNext()) add(c.getString(1))}}.joinToString(",") {"`$it`"}
        execSQL("INSERT INTO ${table}_pre040 ($names) SELECT $names FROM $table")
        execSQL("DROP TABLE $table");execSQL("ALTER TABLE ${table}_pre040 RENAME TO $table")
    }
    execSQL("CREATE INDEX index_pill_plan_versions_effectiveEpochDay ON pill_plan_versions(effectiveEpochDay)")
}

/** Reconstruct genuine v10 jobs before exercising the additive v11 migration. */
internal fun SQLiteDatabase.remove041AnalysisSchema() {
    remove041Stage4Schema()
    val schema = rawQuery("SELECT sql FROM sqlite_master WHERE name='ai_jobs'", null).use { it.moveToFirst(); it.getString(0) }
    val fields = schema.substringAfter('(').substringBeforeLast(')').split(", ").filterNot {
        it.startsWith("`scopeKey` ") || it.startsWith("`inputRevision` ")
    }
    execSQL("CREATE TABLE ai_jobs_pre041 (" + fields.joinToString(", ") + ")")
    val names = rawQuery("PRAGMA table_info(ai_jobs_pre041)", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }.joinToString(",") { "`$it`" }
    execSQL("INSERT INTO ai_jobs_pre041 ($names) SELECT $names FROM ai_jobs")
    execSQL("DROP TABLE ai_jobs")
    execSQL("ALTER TABLE ai_jobs_pre041 RENAME TO ai_jobs")
    execSQL("CREATE INDEX index_ai_jobs_status ON ai_jobs(status)")
    execSQL("DROP TABLE analysis_results")
}

internal fun SQLiteDatabase.remove041Stage4Schema() {
    execSQL("PRAGMA foreign_keys=OFF")
    for((table,column) in listOf("activities" to "huaweiFactsJson","companions" to "deleted")) {
        val schema=rawQuery("SELECT sql FROM sqlite_master WHERE name=?",arrayOf(table)).use {it.moveToFirst();it.getString(0)}
        val fields=schema.substringAfter('(').substringBeforeLast(')').split(", ").filterNot {it.startsWith("`$column` ")}
        execSQL("CREATE TABLE ${table}_pre041s4 ("+fields.joinToString(", ")+")")
        val names=rawQuery("PRAGMA table_info(${table}_pre041s4)",null).use {c->buildList {while(c.moveToNext()) add(c.getString(1))}}.joinToString(",") {"`$it`"}
        execSQL("INSERT INTO ${table}_pre041s4 ($names) SELECT $names FROM $table")
        execSQL("DROP TABLE $table");execSQL("ALTER TABLE ${table}_pre041s4 RENAME TO $table")
    }
    execSQL("CREATE INDEX index_activities_routeId ON activities(routeId)")
    execSQL("DROP TABLE huawei_sources")
}
