package com.mayushi.trailsense.domain

object HikeAnnotations {
    data class Rating(val name: String, val value: Int, val maximum: Int)
    data class Poi(val name: String, val latitude: Double, val longitude: Double)
    fun ratings(text: String): List<Rating> = text.lineSequence().filter { it.isNotBlank() }.map { line ->
        val p = line.split('|'); require(p.size == 3) { "Критерий: название | оценка | максимум" }
        val name = p[0].trim(); val value = p[1].trim().toInt(); val max = p[2].trim().toInt()
        require(name.isNotBlank() && name.length <= 100 && max in 1..100 && value in 0..max) { "Проверь название и шкалу критерия (0–100)" }
        Rating(name,value,max)
    }.toList().also { require(it.size <= 50) }
    fun pois(text: String): List<Poi> = text.lineSequence().filter { it.isNotBlank() }.map { line ->
        val p = line.split('|'); require(p.size == 3) { "POI: название | широта | долгота" }
        val name=p[0].trim(); val lat=p[1].trim().toDouble(); val lon=p[2].trim().toDouble()
        require(name.isNotBlank() && name.length <= 100 && lat in -90.0..90.0 && lon in -180.0..180.0) { "Проверь координаты POI" }
        Poi(name,lat,lon)
    }.toList().also { require(it.size <= 50) }
    fun validate(ratings: String, pois: String) { require(ratings.length <= 10000 && pois.length <= 10000); ratings(ratings); pois(pois) }
}
