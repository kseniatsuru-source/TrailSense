package com.mayushi.trailsense.data

internal inline fun <T> TrailDatabase.useRoom(block: (TrailDatabase) -> T): T = try { block(this) } finally { close() }
