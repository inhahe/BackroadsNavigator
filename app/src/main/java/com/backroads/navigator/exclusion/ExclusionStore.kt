package com.backroads.navigator.exclusion

import android.content.Context
import com.backroads.navigator.api.ExcludedStreet
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.osmdroid.util.GeoPoint
import java.util.UUID

/**
 * Persists excluded streets in SharedPreferences and generates
 * avoidance polygons for the routing engine.
 */
class ExclusionStore(context: Context) {

    private val prefs = context.getSharedPreferences("excluded_streets", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<MutableList<ExcludedStreetData>>() {}.type

    // Internal serializable data class (GeoPoint isn't directly serializable)
    private data class ExcludedStreetData(
        val id: String,
        val name: String,
        val displayAddress: String,
        val centerLat: Double,
        val centerLon: Double,
        val polygonLats: List<Double>,
        val polygonLons: List<Double>,
        val timestamp: Long
    )

    private val _streets = mutableListOf<ExcludedStreet>()
    val streets: List<ExcludedStreet> get() = _streets.toList()

    init {
        load()
    }

    /**
     * Add a street exclusion. Builds an avoidance polygon by buffering
     * around the street's bounding box.
     */
    fun addExclusion(
        name: String,
        displayAddress: String,
        centerLat: Double,
        centerLon: Double,
        streetGeometry: List<GeoPoint>? = null
    ): ExcludedStreet {
        val polygon = if (streetGeometry != null && streetGeometry.size >= 2) {
            bufferLineString(streetGeometry, bufferMeters = 50.0)
        } else {
            // Fallback: create a small polygon around the center point
            createPointBuffer(centerLat, centerLon, bufferMeters = 100.0)
        }

        val excluded = ExcludedStreet(
            id = UUID.randomUUID().toString(),
            name = name,
            displayAddress = displayAddress,
            centerLat = centerLat,
            centerLon = centerLon,
            polygon = polygon
        )

        _streets.add(excluded)
        save()
        return excluded
    }

    fun removeExclusion(id: String) {
        _streets.removeAll { it.id == id }
        save()
    }

    fun clearAll() {
        _streets.clear()
        save()
    }

    /**
     * Get all exclusion polygons for routing API.
     */
    fun getAvoidPolygons(): List<List<GeoPoint>> {
        return _streets.map { it.polygon }
    }

    // ── Polygon generation ─────────────────────────────────────────

    /**
     * Buffer a line string (street geometry) into a polygon by offsetting
     * each point perpendicular to the line direction.
     */
    private fun bufferLineString(points: List<GeoPoint>, bufferMeters: Double): List<GeoPoint> {
        if (points.size < 2) return createPointBuffer(
            points[0].latitude, points[0].longitude, bufferMeters
        )

        val left = mutableListOf<GeoPoint>()
        val right = mutableListOf<GeoPoint>()

        for (i in points.indices) {
            val current = points[i]
            val bearing = when {
                i == 0 -> current.bearingTo(points[1]).toDouble()
                i == points.lastIndex -> points[i - 1].bearingTo(current).toDouble()
                else -> points[i - 1].bearingTo(points[i + 1]).toDouble()
            }

            // Perpendicular offsets
            val leftBearing = (bearing - 90 + 360) % 360
            val rightBearing = (bearing + 90 + 360) % 360

            left.add(offsetPoint(current, leftBearing, bufferMeters))
            right.add(offsetPoint(current, rightBearing, bufferMeters))
        }

        // Build polygon: left side forward + right side backward
        val polygon = mutableListOf<GeoPoint>()
        polygon.addAll(left)
        polygon.addAll(right.reversed())

        return polygon
    }

    private fun createPointBuffer(lat: Double, lon: Double, bufferMeters: Double): List<GeoPoint> {
        val center = GeoPoint(lat, lon)
        val points = mutableListOf<GeoPoint>()
        // Create octagon around the point
        for (bearing in 0 until 360 step 45) {
            points.add(offsetPoint(center, bearing.toDouble(), bufferMeters))
        }
        return points
    }

    /**
     * Offset a point by distance in meters along a bearing.
     */
    private fun offsetPoint(origin: GeoPoint, bearingDeg: Double, distMeters: Double): GeoPoint {
        val R = 6371000.0 // Earth radius in meters
        val lat1 = Math.toRadians(origin.latitude)
        val lon1 = Math.toRadians(origin.longitude)
        val bearing = Math.toRadians(bearingDeg)
        val d = distMeters / R

        val lat2 = Math.asin(
            Math.sin(lat1) * Math.cos(d) +
                    Math.cos(lat1) * Math.sin(d) * Math.cos(bearing)
        )
        val lon2 = lon1 + Math.atan2(
            Math.sin(bearing) * Math.sin(d) * Math.cos(lat1),
            Math.cos(d) - Math.sin(lat1) * Math.sin(lat2)
        )

        return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    // ── Persistence ────────────────────────────────────────────────

    private fun save() {
        val dataList = _streets.map { s ->
            ExcludedStreetData(
                id = s.id,
                name = s.name,
                displayAddress = s.displayAddress,
                centerLat = s.centerLat,
                centerLon = s.centerLon,
                polygonLats = s.polygon.map { it.latitude },
                polygonLons = s.polygon.map { it.longitude },
                timestamp = s.timestamp
            )
        }
        prefs.edit().putString("data", gson.toJson(dataList)).apply()
    }

    private fun load() {
        val json = prefs.getString("data", null) ?: return
        try {
            val dataList: MutableList<ExcludedStreetData> = gson.fromJson(json, listType)
            _streets.clear()
            for (d in dataList) {
                val polygon = d.polygonLats.zip(d.polygonLons).map { (lat, lon) ->
                    GeoPoint(lat, lon)
                }
                _streets.add(
                    ExcludedStreet(
                        id = d.id,
                        name = d.name,
                        displayAddress = d.displayAddress,
                        centerLat = d.centerLat,
                        centerLon = d.centerLon,
                        polygon = polygon,
                        timestamp = d.timestamp
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("ExclusionStore", "Failed to load exclusions", e)
        }
    }
}
