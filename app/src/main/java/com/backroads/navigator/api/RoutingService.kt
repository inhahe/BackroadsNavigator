package com.backroads.navigator.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import java.util.concurrent.TimeUnit

/**
 * Routing via OpenRouteService API (free tier: 2000 requests/day).
 * https://openrouteservice.org/dev/#/api-docs/v2/directions
 *
 * Sign up for a free API key at https://openrouteservice.org/dev/#/signup
 */
class RoutingService(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://api.openrouteservice.org/v2/directions"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Calculate a route between two points with optional backroads mode
     * and street exclusions.
     */
    suspend fun getRoute(
        origin: GeoPoint,
        destination: GeoPoint,
        options: RoutingOptions = RoutingOptions()
    ): Route? = withContext(Dispatchers.IO) {
        try {
            val body = buildRequestBody(origin, destination, options)
            val profile = "driving-car"
            val url = "$baseUrl/$profile/geojson"

            val request = Request.Builder()
                .url(url)
                .header("Authorization", apiKey)
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful || responseBody == null) {
                android.util.Log.e("RoutingService",
                    "ORS error ${response.code}: $responseBody")
                return@withContext null
            }

            parseRouteResponse(responseBody)
        } catch (e: Exception) {
            android.util.Log.e("RoutingService", "Route calculation failed", e)
            null
        }
    }

    private fun buildRequestBody(
        origin: GeoPoint,
        destination: GeoPoint,
        options: RoutingOptions
    ): JsonObject {
        val body = JsonObject()

        // Coordinates: [[lon, lat], [lon, lat]]
        val coords = JsonArray()
        val originCoord = JsonArray().apply {
            add(origin.longitude); add(origin.latitude)
        }
        val destCoord = JsonArray().apply {
            add(destination.longitude); add(destination.latitude)
        }
        coords.add(originCoord)
        coords.add(destCoord)
        body.add("coordinates", coords)

        // Instructions
        body.addProperty("instructions", true)
        body.addProperty("units", "m")
        body.addProperty("language", "en")

        // Backroads mode: avoid highways, prefer shorter (smaller) roads
        if (options.backroadsMode) {
            body.addProperty("preference", "shortest")
        } else {
            body.addProperty("preference", "recommended")
        }

        // Build options object for avoidances
        val avoidFeatures = JsonArray()
        if (options.backroadsMode) {
            avoidFeatures.add("highways")
            avoidFeatures.add("tollways")
        }

        val hasAvoidFeatures = avoidFeatures.size() > 0
        val hasAvoidPolygons = options.avoidPolygons.isNotEmpty()

        if (hasAvoidFeatures || hasAvoidPolygons) {
            val optionsObj = JsonObject()

            if (hasAvoidFeatures) {
                optionsObj.add("avoid_features", avoidFeatures)
            }

            if (hasAvoidPolygons) {
                optionsObj.add("avoid_polygons", buildAvoidPolygons(options.avoidPolygons))
            }

            body.add("options", optionsObj)
        }

        return body
    }

    /**
     * Build GeoJSON MultiPolygon from exclusion polygons.
     */
    private fun buildAvoidPolygons(polygons: List<List<GeoPoint>>): JsonObject {
        val multiPolygon = JsonObject()
        multiPolygon.addProperty("type", "MultiPolygon")

        val coordinates = JsonArray()
        for (polygon in polygons) {
            if (polygon.size < 3) continue

            val ring = JsonArray()
            // Add all points + close the ring
            for (point in polygon) {
                val coord = JsonArray()
                coord.add(point.longitude)
                coord.add(point.latitude)
                ring.add(coord)
            }
            // Close the ring
            val first = polygon[0]
            val closeCoord = JsonArray()
            closeCoord.add(first.longitude)
            closeCoord.add(first.latitude)
            ring.add(closeCoord)

            // Polygon = [[ring]]
            val polyRings = JsonArray()
            polyRings.add(ring)
            coordinates.add(polyRings)
        }

        multiPolygon.add("coordinates", coordinates)
        return multiPolygon
    }

    /**
     * Parse GeoJSON route response from ORS.
     */
    private fun parseRouteResponse(json: String): Route? {
        val root = JsonParser.parseString(json).asJsonObject
        val features = root.getAsJsonArray("features")
        if (features == null || features.size() == 0) return null

        val feature = features[0].asJsonObject
        val geometry = feature.getAsJsonObject("geometry")
        val properties = feature.getAsJsonObject("properties")
        val summary = properties.getAsJsonObject("summary")

        // Parse GeoJSON LineString coordinates → GeoPoints
        val coordsArray = geometry.getAsJsonArray("coordinates")
        val geoPoints = coordsArray.map { c ->
            val arr = c.asJsonArray
            GeoPoint(arr[1].asDouble, arr[0].asDouble)
        }

        // Parse steps
        val segments = properties.getAsJsonArray("segments")
        val steps = mutableListOf<RouteStep>()
        if (segments != null && segments.size() > 0) {
            val segment = segments[0].asJsonObject
            val stepsArray = segment.getAsJsonArray("steps")
            if (stepsArray != null) {
                for (stepElem in stepsArray) {
                    val step = stepElem.asJsonObject
                    val wpArray = step.getAsJsonArray("way_points")
                    steps.add(
                        RouteStep(
                            instruction = step.get("instruction")?.asString ?: "",
                            distanceMeters = step.get("distance")?.asDouble ?: 0.0,
                            durationSeconds = step.get("duration")?.asDouble ?: 0.0,
                            type = step.get("type")?.asInt ?: 0,
                            name = step.get("name")?.asString ?: "",
                            wayPoints = if (wpArray != null && wpArray.size() >= 2)
                                wpArray[0].asInt..wpArray[1].asInt
                            else 0..0
                        )
                    )
                }
            }
        }

        // Bounding box
        val bbox = root.getAsJsonArray("bbox")
        val boundingBox = if (bbox != null && bbox.size() >= 4) {
            BoundingBox(
                bbox[3].asDouble, // north
                bbox[2].asDouble, // east
                bbox[1].asDouble, // south
                bbox[0].asDouble  // west
            )
        } else {
            BoundingBox.fromGeoPoints(geoPoints)
        }

        return Route(
            geometry = geoPoints,
            distanceMeters = summary?.get("distance")?.asDouble ?: 0.0,
            durationSeconds = summary?.get("duration")?.asDouble ?: 0.0,
            steps = steps,
            boundingBox = boundingBox
        )
    }
}
