package com.backroads.navigator.navigation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import com.backroads.navigator.api.ExcludedStreet
import com.backroads.navigator.api.Route
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

/**
 * Renders routes, markers, and exclusion zones on the osmdroid MapView.
 */
class RouteRenderer(private val mapView: MapView) {

    private var routeLine: Polyline? = null
    private var originMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var userMarker: Marker? = null
    private var accuracyCircle: Polygon? = null
    private val exclusionOverlays = mutableListOf<Polygon>()
    private val exclusionCenters = mutableListOf<Marker>()

    /** Current GPS bearing in degrees (0 = north). Set by caller. */
    var userBearing: Float = 0f

    // ── Route ──────────────────────────────────────────────────────

    fun drawRoute(route: Route, isBackroads: Boolean = false) {
        clearRoute()

        routeLine = Polyline(mapView).apply {
            setPoints(route.geometry)
            outlinePaint.apply {
                color = if (isBackroads) Color.parseColor("#FF8F00")
                else Color.parseColor("#1565C0")
                strokeWidth = 14f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                isAntiAlias = true
            }
        }
        mapView.overlays.add(0, routeLine)

        val borderLine = Polyline(mapView).apply {
            setPoints(route.geometry)
            outlinePaint.apply {
                color = if (isBackroads) Color.parseColor("#E65100")
                else Color.parseColor("#0D47A1")
                strokeWidth = 18f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                isAntiAlias = true
            }
        }
        mapView.overlays.add(0, borderLine)

        mapView.invalidate()
    }

    fun clearRoute() {
        routeLine?.let { mapView.overlays.remove(it) }
        routeLine = null
        mapView.overlays.removeAll { it is Polyline }
        mapView.invalidate()
    }

    // ── Markers ────────────────────────────────────────────────────

    fun setOriginMarker(point: GeoPoint) {
        originMarker?.let { mapView.overlays.remove(it) }
        originMarker = Marker(mapView).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Start"
            icon = createCircleDrawable(Color.parseColor("#43A047"), 24)
        }
        mapView.overlays.add(originMarker)
        mapView.invalidate()
    }

    fun setDestinationMarker(point: GeoPoint) {
        destinationMarker?.let { mapView.overlays.remove(it) }
        destinationMarker = Marker(mapView).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Destination"
            icon = createCircleDrawable(Color.parseColor("#E53935"), 28)
        }
        mapView.overlays.add(destinationMarker)
        mapView.invalidate()
    }

    /**
     * Update the user-location indicator on the map.
     *
     * Draws:
     *  - A translucent blue accuracy circle (if [accuracyMeters] > 0)
     *  - A blue dot with white border
     *  - A bearing arrow pointing in the direction of travel
     *
     * The marker is always added last so it renders on top of routes.
     */
    fun updateUserLocation(
        point: GeoPoint,
        bearingDeg: Float = userBearing,
        accuracyMeters: Float = 0f
    ) {
        userBearing = bearingDeg

        // ── Accuracy circle ────────────────────────────────────────
        if (accuracyMeters > 10f) {
            if (accuracyCircle == null) {
                accuracyCircle = Polygon(mapView).apply {
                    fillPaint.color = Color.parseColor("#1A2196F3") // 10 % blue
                    outlinePaint.apply {
                        color = Color.parseColor("#442196F3")       // 27 % blue
                        strokeWidth = 2f
                        style = Paint.Style.STROKE
                    }
                }
                mapView.overlays.add(accuracyCircle)
            }
            accuracyCircle?.points = Polygon.pointsAsCircle(point, accuracyMeters.toDouble())
        } else {
            accuracyCircle?.let { mapView.overlays.remove(it) }
            accuracyCircle = null
        }

        // ── User dot + bearing arrow ───────────────────────────────
        val hasBearing = bearingDeg != 0f || userBearing != 0f
        if (userMarker == null) {
            userMarker = Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "You are here"
                setInfoWindow(null) // no tap bubble
            }
            mapView.overlays.add(userMarker)
        }
        userMarker?.icon = createUserDotDrawable(hasBearing, bearingDeg)
        userMarker?.position = point

        // Keep user marker on top of everything
        userMarker?.let {
            mapView.overlays.remove(it)
            mapView.overlays.add(it)
        }

        mapView.invalidate()
    }

    fun clearMarkers() {
        originMarker?.let { mapView.overlays.remove(it) }
        destinationMarker?.let { mapView.overlays.remove(it) }
        originMarker = null
        destinationMarker = null
        mapView.invalidate()
    }

    // ── Exclusion Zones ────────────────────────────────────────────

    fun drawExclusionZones(streets: List<ExcludedStreet>) {
        clearExclusionZones()

        for (street in streets) {
            if (street.polygon.size < 3) continue

            val polygon = Polygon(mapView).apply {
                points = street.polygon + street.polygon.first()
                fillPaint.color = Color.parseColor("#33E53935")
                outlinePaint.apply {
                    color = Color.parseColor("#E53935")
                    strokeWidth = 3f
                    style = Paint.Style.STROKE
                }
                title = "Excluded: ${street.name}"
            }
            exclusionOverlays.add(polygon)
            mapView.overlays.add(polygon)

            val center = Marker(mapView).apply {
                position = GeoPoint(street.centerLat, street.centerLon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "\u2717 ${street.name}"
                icon = createCircleDrawable(Color.parseColor("#E53935"), 14)
            }
            exclusionCenters.add(center)
            mapView.overlays.add(center)
        }

        mapView.invalidate()
    }

    fun clearExclusionZones() {
        for (overlay in exclusionOverlays) mapView.overlays.remove(overlay)
        for (marker in exclusionCenters)   mapView.overlays.remove(marker)
        exclusionOverlays.clear()
        exclusionCenters.clear()
        mapView.invalidate()
    }

    // ── Drawable helpers ───────────────────────────────────────────

    /**
     * Google-Maps-style blue dot with white ring + optional bearing cone.
     */
    private fun createUserDotDrawable(
        showBearing: Boolean,
        bearingDeg: Float
    ): BitmapDrawable {
        val density = mapView.context.resources.displayMetrics.density
        val sizePx = (48 * density).toInt()        // canvas size
        val dotRadius = 9 * density                 // inner blue circle
        val borderWidth = 3.5f * density            // white ring
        val coneLength = 18 * density               // bearing wedge length
        val coneHalfAngle = 28f                     // half-width of cone in degrees
        val cx = sizePx / 2f
        val cy = sizePx / 2f

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // ── Bearing cone ───────────────────────────────────────────
        if (showBearing) {
            val conePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#552196F3")  // 33 % blue
                style = Paint.Style.FILL
            }
            val path = Path()
            val leftRad  = Math.toRadians((bearingDeg - coneHalfAngle).toDouble())
            val rightRad = Math.toRadians((bearingDeg + coneHalfAngle).toDouble())

            path.moveTo(cx, cy)
            path.lineTo(
                cx + (coneLength * Math.sin(leftRad)).toFloat(),
                cy - (coneLength * Math.cos(leftRad)).toFloat()
            )
            path.lineTo(
                cx + (coneLength * Math.sin(rightRad)).toFloat(),
                cy - (coneLength * Math.cos(rightRad)).toFloat()
            )
            path.close()
            canvas.drawPath(path, conePaint)
        }

        // ── White border ring ──────────────────────────────────────
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            setShadowLayer(4 * density, 0f, 1 * density, Color.parseColor("#40000000"))
        }
        canvas.drawCircle(cx, cy, dotRadius + borderWidth, borderPaint)

        // ── Blue fill ──────────────────────────────────────────────
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2196F3")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, dotRadius, fillPaint)

        // ── Inner highlight (subtle 3-D look) ──────────────────────
        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6642A5F5")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx - 2 * density, cy - 2 * density, dotRadius * 0.45f, highlightPaint)

        return BitmapDrawable(mapView.context.resources, bitmap)
    }

    private fun createCircleDrawable(
        color: Int,
        sizeDp: Int,
        borderColor: Int = Color.WHITE,
        borderWidth: Float = 3f
    ): BitmapDrawable {
        val density = mapView.context.resources.displayMetrics.density
        val sizePx = (sizeDp * density).toInt()

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = sizePx / 2f

        val bPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = borderColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(radius, radius, radius, bPaint)

        val fPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawCircle(radius, radius, radius - borderWidth * density, fPaint)

        return BitmapDrawable(mapView.context.resources, bitmap)
    }

    fun clearAll() {
        clearRoute()
        clearMarkers()
        clearExclusionZones()
        accuracyCircle?.let { mapView.overlays.remove(it) }
        accuracyCircle = null
        userMarker?.let { mapView.overlays.remove(it) }
        userMarker = null
    }
}
