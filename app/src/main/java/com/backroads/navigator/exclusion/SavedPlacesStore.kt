package com.backroads.navigator.exclusion

import android.content.Context
import com.backroads.navigator.api.SavedPlace
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Persists named saved places (Home, Work, favorites) in SharedPreferences.
 */
class SavedPlacesStore(context: Context) {

    private val prefs = context.getSharedPreferences("saved_places", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<MutableList<SavedPlace>>() {}.type

    // ── Home ───────────────────────────────────────────────────────

    fun getHome(): SavedPlace? {
        val json = prefs.getString("home", null) ?: return null
        return try { gson.fromJson(json, SavedPlace::class.java) } catch (_: Exception) { null }
    }

    fun setHome(address: String, lat: Double, lon: Double) {
        val place = SavedPlace(
            id = "home",
            label = "Home",
            address = address,
            lat = lat,
            lon = lon,
            kind = "home"
        )
        prefs.edit().putString("home", gson.toJson(place)).apply()
    }

    fun clearHome() {
        prefs.edit().remove("home").apply()
    }

    // ── Work ───────────────────────────────────────────────────────

    fun getWork(): SavedPlace? {
        val json = prefs.getString("work", null) ?: return null
        return try { gson.fromJson(json, SavedPlace::class.java) } catch (_: Exception) { null }
    }

    fun setWork(address: String, lat: Double, lon: Double) {
        val place = SavedPlace(
            id = "work",
            label = "Work",
            address = address,
            lat = lat,
            lon = lon,
            kind = "work"
        )
        prefs.edit().putString("work", gson.toJson(place)).apply()
    }

    fun clearWork() {
        prefs.edit().remove("work").apply()
    }

    // ── Favorites ──────────────────────────────────────────────────

    fun getFavorites(): List<SavedPlace> {
        val json = prefs.getString("favorites", null) ?: return emptyList()
        return try { gson.fromJson(json, listType) } catch (_: Exception) { emptyList() }
    }

    fun addFavorite(label: String, address: String, lat: Double, lon: Double): SavedPlace {
        val favorites = getFavorites().toMutableList()
        val place = SavedPlace(
            id = UUID.randomUUID().toString(),
            label = label,
            address = address,
            lat = lat,
            lon = lon,
            kind = "favorite"
        )
        favorites.add(place)
        prefs.edit().putString("favorites", gson.toJson(favorites)).apply()
        return place
    }

    fun removeFavorite(id: String) {
        val favorites = getFavorites().toMutableList()
        favorites.removeAll { it.id == id }
        prefs.edit().putString("favorites", gson.toJson(favorites)).apply()
    }

    // ── All saved places ───────────────────────────────────────────

    /**
     * Returns all saved places: Home first, then Work, then favorites.
     */
    fun getAll(): List<SavedPlace> {
        val all = mutableListOf<SavedPlace>()
        getHome()?.let { all.add(it) }
        getWork()?.let { all.add(it) }
        all.addAll(getFavorites())
        return all
    }

    /**
     * Returns saved places whose label or address contains the query (case-insensitive).
     */
    fun search(query: String): List<SavedPlace> {
        if (query.isBlank()) return getAll()
        val q = query.lowercase()
        return getAll().filter {
            it.label.lowercase().contains(q) || it.address.lowercase().contains(q)
        }
    }
}
