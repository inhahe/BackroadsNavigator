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
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Routing via OpenRouteService API (free tier: 2000 requests/day).
 * https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/
 *
 * Sign up for a free API key at https://account.heigit.org
 *
 * [apiKey] is the user's OpenRouteService key; blank when none has been given yet.
 */
class RoutingService(var apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // HeiGIT's address since 2026; api.openrouteservice.org shuts down in November 2026.
    private val baseUrl = "https://api.heigit.org/openrouteservice/v2/directions"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * The route from [origin] to [destination] under [options] (backroads mode, avoided
     * areas), or null when there is none or the request failed: no network, a server error,
     * a response that cannot be read.
     *
     * @throws RoutingRefusedException when OpenRouteService refuses the request itself, which
     *   a retry will not fix: the key ([ApiKeyRejectedException]), the day's quota
     *   ([QuotaExceededException]) or the per-minute limit ([RateLimitedException]).
     */
    suspend fun getRoute(
        origin: GeoPoint,
        destination: GeoPoint,
        options: RoutingOptions = RoutingOptions()
    ): Route? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/driving-car/geojson")
            .header("Authorization", apiKey)
            .header("Content-Type", "application/json")
            .post(buildRequestBody(origin, destination, options).toString()
                .toRequestBody(jsonMediaType))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            android.util.Log.e("RoutingService", "Route request failed", e)
            return@withContext null
        }
        response.use {
            val text = try {
                it.body?.string()
            } catch (e: IOException) {
                android.util.Log.e("RoutingService", "Route response could not be read", e)
                return@withContext null
            }
            if (!it.isSuccessful || text == null) {
                android.util.Log.e("RoutingService", "ORS error ${it.code}: $text")
                refusal(it.code, it.header("x-ratelimit-remaining"))?.let { e -> throw e }
                return@withContext null
            }
            try {
                parseRouteResponse(text)
            } catch (e: RuntimeException) {
                // Malformed or unexpected JSON (Gson's exceptions, missing fields).
                android.util.Log.e("RoutingService", "Route response could not be parsed", e)
                null
            }
        }
    }

    /**
     * What HTTP status [code] says about the request, if it is a refusal. OpenRouteService
     * answers 403 with the same text ("Daily quota reached or API key unauthorized") both for
     * a bad key and for a used-up daily quota; only the quota header, [remaining] requests,
     * tells them apart. 429 is the per-minute limit.
     */
    private fun refusal(code: Int, remaining: String?): RoutingRefusedException? = when {
        code == 429 -> RateLimitedException()
        code == 403 && remaining?.trim() == "0" -> QuotaExceededException()
        code == 401 || code == 403 -> ApiKeyRejectedException(code)
        else -> null
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

/** OpenRouteService refused the request; retrying will not help until the user acts. */
sealed class RoutingRefusedException(message: String) : Exception(message)

/** The key was refused: HTTP [code] 401 (none, or malformed) or 403 (not authorized). */
class ApiKeyRejectedException(val code: Int) :
    RoutingRefusedException("OpenRouteService rejected the API key (HTTP $code)")

/** The key's daily quota is used up (403 with no requests remaining). */
class QuotaExceededException :
    RoutingRefusedException("OpenRouteService's daily quota for this key is used up")

/** Too many requests in the last minute (429). */
class RateLimitedException :
    RoutingRefusedException("OpenRouteService's per-minute limit was reached")
