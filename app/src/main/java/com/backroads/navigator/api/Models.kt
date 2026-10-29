package com.backroads.navigator.api

import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint

// ── Geocoding (Nominatim) ──────────────────────────────────────────

data class GeocodingResult(
    val displayName: String,
    val lat: Double,
    val lon: Double,
    val type: String,        // "road", "city", "house", etc.
    val importance: Double,
    val boundingBox: BoundingBox?
)

data class ReverseGeocodingResult(
    val streetName: String?,
    val displayName: String,
    val lat: Double,
    val lon: Double,
    val osmId: Long?,
    val osmType: String?,       // "way", "node", "relation"
    val boundingBox: BoundingBox?
)

// ── Routing (OpenRouteService) ─────────────────────────────────────

data class Route(
    val geometry: List<GeoPoint>,     // decoded polyline
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<RouteStep>,
    val boundingBox: BoundingBox
)

data class RouteStep(
    val instruction: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val type: Int,                    // ORS maneuver type code
    val name: String,                 // road name
    val wayPoints: IntRange           // index range into Route.geometry
)

// ── Street Exclusion ───────────────────────────────────────────────

data class ExcludedStreet(
    val id: String,                   // UUID
    val name: String,
    val displayAddress: String,
    val centerLat: Double,
    val centerLon: Double,
    val polygon: List<GeoPoint>,      // avoidance polygon (buffered bbox)
    val timestamp: Long = System.currentTimeMillis()
)

// ── Saved Places ───────────────────────────────────────────────────

data class SavedPlace(
    val id: String,           // "home", "work", or UUID for favorites
    val label: String,        // "Home", "Work", or custom name
    val address: String,      // full display address
    val lat: Double,
    val lon: Double,
    val kind: String          // "home", "work", "favorite"
) {
    /** Convert to a GeocodingResult so the search adapter can display it uniformly. */
    fun toGeocodingResult() = GeocodingResult(
        displayName = "$label — $address",
        lat = lat,
        lon = lon,
        type = "saved_$kind",   // "saved_home", "saved_work", "saved_favorite"
        importance = 2.0,       // rank above normal results
        boundingBox = null
    )
}

// ── Routing Request Options ────────────────────────────────────────

data class RoutingOptions(
    val backroadsMode: Boolean = false,
    val avoidPolygons: List<List<GeoPoint>> = emptyList()
)
