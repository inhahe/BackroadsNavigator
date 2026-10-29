package com.backroads.navigator.navigation

import com.backroads.navigator.api.Route
import com.backroads.navigator.api.RouteStep
import org.osmdroid.util.GeoPoint

/**
 * Turn-by-turn navigation engine.
 * Tracks user position along the route and determines current step,
 * distance to next maneuver, and off-route detection.
 */
class NavigationEngine {

    interface Listener {
        fun onStepChanged(stepIndex: Int, step: RouteStep)
        fun onDistanceToNextStep(meters: Double)
        fun onOffRoute()
        fun onArrived()
        fun onRouteProgress(coveredMeters: Double, remainingMeters: Double, etaSeconds: Double)
    }

    var listener: Listener? = null
    var route: Route? = null
        private set

    private var currentStepIndex = 0
    private var isNavigating = false

    // Off-route threshold: 50 meters from nearest route point
    private val offRouteThresholdMeters = 50.0
    // Step advance threshold: within 30 meters of step end point
    private val stepAdvanceThresholdMeters = 30.0
    // Arrival threshold: within 25 meters of destination
    private val arrivalThresholdMeters = 25.0

    val isActive: Boolean get() = isNavigating

    fun startNavigation(route: Route) {
        this.route = route
        this.currentStepIndex = 0
        this.isNavigating = true

        if (route.steps.isNotEmpty()) {
            listener?.onStepChanged(0, route.steps[0])
        }
    }

    fun stopNavigation() {
        isNavigating = false
        route = null
        currentStepIndex = 0
    }

    /**
     * Update with the user's current GPS position.
     * Call this on each location update.
     */
    fun updateLocation(userLocation: GeoPoint) {
        val route = this.route ?: return
        if (!isNavigating) return

        // Check arrival
        val destination = route.geometry.lastOrNull() ?: return
        val distToDestination = userLocation.distanceToAsDouble(destination)
        if (distToDestination < arrivalThresholdMeters) {
            isNavigating = false
            listener?.onArrived()
            return
        }

        // Find nearest point on route
        val (nearestIndex, nearestDist) = findNearestRoutePoint(userLocation, route.geometry)

        // Off-route detection
        if (nearestDist > offRouteThresholdMeters) {
            listener?.onOffRoute()
            return
        }

        // Check if we should advance to the next step
        if (currentStepIndex < route.steps.size - 1) {
            val currentStep = route.steps[currentStepIndex]
            val stepEndIndex = currentStep.wayPoints.last
                .coerceAtMost(route.geometry.size - 1)
            val stepEndPoint = route.geometry[stepEndIndex]
            val distToStepEnd = userLocation.distanceToAsDouble(stepEndPoint)

            if (distToStepEnd < stepAdvanceThresholdMeters) {
                currentStepIndex++
                val nextStep = route.steps[currentStepIndex]
                listener?.onStepChanged(currentStepIndex, nextStep)
            } else {
                listener?.onDistanceToNextStep(distToStepEnd)
            }
        }

        // Calculate remaining distance and ETA
        val coveredDist = calculateDistanceAlongRoute(route.geometry, 0, nearestIndex)
        val totalDist = route.distanceMeters
        val remainingDist = totalDist - coveredDist
        val avgSpeed = if (route.durationSeconds > 0)
            totalDist / route.durationSeconds else 10.0 // ~36 km/h fallback
        val etaSeconds = remainingDist / avgSpeed

        listener?.onRouteProgress(coveredDist, remainingDist, etaSeconds)
    }

    /**
     * Get the current navigation step.
     */
    fun getCurrentStep(): RouteStep? {
        val route = this.route ?: return null
        if (currentStepIndex >= route.steps.size) return null
        return route.steps[currentStepIndex]
    }

    /**
     * Get the next navigation step (preview).
     */
    fun getNextStep(): RouteStep? {
        val route = this.route ?: return null
        val nextIndex = currentStepIndex + 1
        if (nextIndex >= route.steps.size) return null
        return route.steps[nextIndex]
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun findNearestRoutePoint(
        location: GeoPoint,
        routePoints: List<GeoPoint>
    ): Pair<Int, Double> {
        var minDist = Double.MAX_VALUE
        var minIndex = 0
        for (i in routePoints.indices) {
            val dist = location.distanceToAsDouble(routePoints[i])
            if (dist < minDist) {
                minDist = dist
                minIndex = i
            }
        }
        return Pair(minIndex, minDist)
    }

    private fun calculateDistanceAlongRoute(
        points: List<GeoPoint>,
        fromIndex: Int,
        toIndex: Int
    ): Double {
        var dist = 0.0
        for (i in fromIndex until toIndex.coerceAtMost(points.size - 1)) {
            dist += points[i].distanceToAsDouble(points[i + 1])
        }
        return dist
    }
}
