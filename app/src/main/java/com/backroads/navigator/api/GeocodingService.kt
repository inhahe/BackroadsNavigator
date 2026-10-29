package com.backroads.navigator.api

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.util.BoundingBox
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Geocoding via OpenStreetMap Nominatim (free, no API key needed).
 * https://nominatim.org/release-docs/develop/api/Overview/
 */
class GeocodingService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://nominatim.openstreetmap.org"

    /**
     * Forward geocode: search string → list of matching places.
     *
     * When [nearLat]/[nearLon] are provided the results are biased toward
     * that location (≈50 km radius viewbox) so nearby matches rank first.
     */
    suspend fun search(
        query: String,
        limit: Int = 8,
        nearLat: Double? = null,
        nearLon: Double? = null
    ): List<GeocodingResult> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            var url = "$baseUrl/search?q=$encoded&format=json&addressdetails=1" +
                    "&limit=$limit&accept-language=en"

            // Proximity bias: create a ~50 km viewbox around the user
            if (nearLat != null && nearLon != null) {
                val delta = 0.45  // ~50 km at mid-latitudes
                val west  = nearLon - delta
                val south = nearLat - delta
                val east  = nearLon + delta
                val north = nearLat + delta
                url += "&viewbox=$west,$north,$east,$south&bounded=0"
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "BackroadsNavigator/1.0")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()
            val array = JsonParser.parseString(body).asJsonArray

            array.map { elem ->
                val obj = elem.asJsonObject
                val bb = obj.getAsJsonArray("boundingbox")
                GeocodingResult(
                    displayName = obj.get("display_name").asString,
                    lat = obj.get("lat").asString.toDouble(),
                    lon = obj.get("lon").asString.toDouble(),
                    type = obj.get("type")?.asString ?: "",
                    importance = obj.get("importance")?.asDouble ?: 0.0,
                    boundingBox = if (bb != null && bb.size() == 4) {
                        BoundingBox(
                            bb[1].asDouble, // north
                            bb[3].asDouble, // east
                            bb[0].asDouble, // south
                            bb[2].asDouble  // west
                        )
                    } else null
                )
            }
        }

    /**
     * Reverse geocode: lat/lon → street name and info.
     */
    suspend fun reverseGeocode(lat: Double, lon: Double): ReverseGeocodingResult? =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/reverse?lat=$lat&lon=$lon&format=json" +
                    "&addressdetails=1&zoom=17&accept-language=en"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "BackroadsNavigator/1.0")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            val obj = JsonParser.parseString(body).asJsonObject

            if (obj.has("error")) return@withContext null

            val address = obj.getAsJsonObject("address")
            val streetName = address?.get("road")?.asString
                ?: address?.get("pedestrian")?.asString
                ?: address?.get("footway")?.asString

            val bb = obj.getAsJsonArray("boundingbox")

            ReverseGeocodingResult(
                streetName = streetName,
                displayName = obj.get("display_name")?.asString ?: "",
                lat = obj.get("lat")?.asString?.toDouble() ?: lat,
                lon = obj.get("lon")?.asString?.toDouble() ?: lon,
                osmId = obj.get("osm_id")?.asLong,
                osmType = obj.get("osm_type")?.asString,
                boundingBox = if (bb != null && bb.size() == 4) {
                    BoundingBox(
                        bb[1].asDouble,
                        bb[3].asDouble,
                        bb[0].asDouble,
                        bb[2].asDouble
                    )
                } else null
            )
        }

    /**
     * Look up the full geometry of an OSM way to build an accurate exclusion polygon.
     */
    suspend fun lookupWayGeometry(osmType: String, osmId: Long): List<org.osmdroid.util.GeoPoint>? =
        withContext(Dispatchers.IO) {
            // Nominatim lookup with polygon geometry
            val typePrefix = when (osmType) {
                "way" -> "W"
                "relation" -> "R"
                "node" -> "N"
                else -> return@withContext null
            }
            val url = "$baseUrl/lookup?osm_ids=$typePrefix$osmId&format=json" +
                    "&polygon_geojson=1"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "BackroadsNavigator/1.0")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            val array = JsonParser.parseString(body).asJsonArray
            if (array.size() == 0) return@withContext null

            val obj = array[0].asJsonObject
            val geojson = obj.getAsJsonObject("geojson") ?: return@withContext null
            val geoType = geojson.get("type")?.asString

            when (geoType) {
                "LineString" -> {
                    val coords = geojson.getAsJsonArray("coordinates")
                    coords.map { c ->
                        val arr = c.asJsonArray
                        org.osmdroid.util.GeoPoint(arr[1].asDouble, arr[0].asDouble)
                    }
                }
                "MultiLineString" -> {
                    val coords = geojson.getAsJsonArray("coordinates")
                    coords.flatMap { line ->
                        line.asJsonArray.map { c ->
                            val arr = c.asJsonArray
                            org.osmdroid.util.GeoPoint(arr[1].asDouble, arr[0].asDouble)
                        }
                    }
                }
                else -> null
            }
        }
}
