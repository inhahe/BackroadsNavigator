package com.backroads.navigator

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.backroads.navigator.api.ApiKeyRejectedException
import com.backroads.navigator.api.GeocodingResult
import com.backroads.navigator.api.GeocodingService
import com.backroads.navigator.api.Route
import com.backroads.navigator.api.RouteStep
import com.backroads.navigator.api.RoutingOptions
import com.backroads.navigator.api.RoutingService
import com.backroads.navigator.exclusion.ExclusionStore
import com.backroads.navigator.exclusion.SavedPlacesStore
import com.backroads.navigator.navigation.NavigationEngine
import com.backroads.navigator.navigation.RouteRenderer
import com.backroads.navigator.ui.DirectionsAdapter
import com.backroads.navigator.ui.ExcludedStreetsAdapter
import com.backroads.navigator.ui.SearchResultsAdapter
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.events.MapEventsReceiver
import java.util.Locale

class MainActivity : AppCompatActivity(), NavigationEngine.Listener {

    // ── Services ───────────────────────────────────────────────────
    private lateinit var geocodingService: GeocodingService
    private lateinit var routingService: RoutingService
    private lateinit var exclusionStore: ExclusionStore
    private lateinit var savedPlacesStore: SavedPlacesStore
    private lateinit var navigationEngine: NavigationEngine
    private lateinit var routeRenderer: RouteRenderer
    private lateinit var locationManager: LocationManager
    private var tts: TextToSpeech? = null

    // ── Views ──────────────────────────────────────────────────────
    private lateinit var mapView: MapView
    private lateinit var editSearch: EditText
    private lateinit var btnClearSearch: ImageButton
    private lateinit var searchResultsCard: MaterialCardView
    private lateinit var recyclerSearchResults: RecyclerView
    private lateinit var navigationPanel: MaterialCardView
    private lateinit var routeSummaryPanel: MaterialCardView
    private lateinit var fabNavigate: FloatingActionButton
    private lateinit var fabMyLocation: FloatingActionButton
    private lateinit var fabBackroads: FloatingActionButton
    private lateinit var fabHome: FloatingActionButton
    private lateinit var fabExcludedStreets: FloatingActionButton
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<LinearLayout>
    private lateinit var directionsSheetBehavior: BottomSheetBehavior<LinearLayout>

    // Navigation panel views
    private lateinit var textInstruction: TextView
    private lateinit var textStepDistance: TextView
    private lateinit var textNavEta: TextView
    private lateinit var textNavRemaining: TextView
    private lateinit var btnStopNavigation: ImageButton
    private lateinit var btnToggleVoice: ImageButton
    private lateinit var btnToggleOrientation: ImageButton
    private lateinit var btnShowDirections: MaterialButton

    // Route summary views
    private lateinit var textRouteDistance: TextView
    private lateinit var textRouteDuration: TextView
    private lateinit var textRouteVia: TextView
    private lateinit var btnCancelRoute: MaterialButton
    private lateinit var btnStartNavigation: MaterialButton
    private lateinit var btnSaveHome: MaterialButton
    private lateinit var btnSaveWork: MaterialButton
    private lateinit var btnSaveFavorite: MaterialButton
    private lateinit var btnShowDirectionsSummary: MaterialButton

    // ── State ──────────────────────────────────────────────────────
    private var backroadsMode = false
    private var currentRoute: Route? = null
    private var destinationPoint: GeoPoint? = null
    private var destinationName: String = ""
    private var destinationAddress: String = ""
    private var userLocation: GeoPoint? = null
    private var searchJob: Job? = null
    private var isNavigating = false
    private var isRerouting = false
    private var voiceEnabled = true
    private var headingUpMode = false
    private var ttsReady = false

    // Voice approach thresholds — track which have been spoken per step
    private var spokenThresholds = mutableSetOf<Int>() // meters spoken for current step

    private val searchAdapter = SearchResultsAdapter { onSearchResultSelected(it) }
    private lateinit var excludedAdapter: ExcludedStreetsAdapter
    private lateinit var directionsAdapter: DirectionsAdapter

    private var pendingSaveKind: String? = null
    private var orsKeyDialogShowing = false

    /** When the last GPS fix arrived (elapsedRealtime); network fixes defer to a recent one. */
    private var lastGpsFixAt = 0L
    /** No position was known when the map wanted to centre on the user: do it at the first fix. */
    private var centerOnNextFix = false

    // Every method is implemented: before Android 11 the three status callbacks have no
    // default, and the framework calls them.
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private fun onLocation(loc: Location) {
        // GPS while it is delivering; a network fix only while GPS has had nothing recent, so
        // the two do not alternate and make the position jump during navigation.
        val now = SystemClock.elapsedRealtime()
        if (loc.provider == LocationManager.GPS_PROVIDER) {
            lastGpsFixAt = now
        } else if (lastGpsFixAt != 0L && now - lastGpsFixAt < GPS_PREFERRED_MS) {
            return
        }
        userLocation = GeoPoint(loc.latitude, loc.longitude)
        routeRenderer.updateUserLocation(
            point = userLocation!!,
            bearingDeg = if (loc.hasBearing()) loc.bearing else routeRenderer.userBearing,
            accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else 0f
        )
        if (centerOnNextFix) {
            centerOnNextFix = false
            mapView.controller.animateTo(userLocation)
            mapView.controller.setZoom(DEFAULT_ZOOM)
        }
        if (isNavigating) {
            navigationEngine.updateLocation(userLocation!!)
            mapView.controller.animateTo(userLocation)
            // Heading-up: rotate map to match travel bearing
            if (headingUpMode && loc.hasBearing()) {
                mapView.mapOrientation = -loc.bearing
            }
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val DEFAULT_ZOOM = 15.0
        private const val LOCATION_INTERVAL_MS = 1000L
        /** How long after a GPS fix network fixes are ignored. */
        private const val GPS_PREFERRED_MS = 10_000L
        private val LOCATION_PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        private const val PREFS_SETTINGS = "settings"
        private const val PREF_ORS_KEY = "ors_api_key"
        private const val ORS_SIGNUP_URL = "https://openrouteservice.org/dev/#/signup"
        private val DEFAULT_CENTER = GeoPoint(40.7128, -74.0060)
    }

    // ── Lifecycle ──────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this,
            getSharedPreferences("osmdroid", MODE_PRIVATE))

        setContentView(R.layout.activity_main)

        initTts()
        initServices()
        initViews()
        initMap()
        initSearch()
        initFabs()
        initBottomSheet()
        initDirectionsSheet()
        requestLocationPermission()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        locationManager.removeUpdates(locationListener)
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    // ── Init ───────────────────────────────────────────────────────

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
            }
        }
    }

    private fun initServices() {
        geocodingService = GeocodingService()
        routingService = RoutingService(storedOrsKey())
        exclusionStore = ExclusionStore(this)
        savedPlacesStore = SavedPlacesStore(this)
        navigationEngine = NavigationEngine().apply { listener = this@MainActivity }
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
    }

    private fun initViews() {
        mapView = findViewById(R.id.map_view)
        editSearch = findViewById(R.id.edit_search)
        btnClearSearch = findViewById(R.id.btn_clear_search)
        searchResultsCard = findViewById(R.id.search_results_card)
        recyclerSearchResults = findViewById(R.id.recycler_search_results)
        navigationPanel = findViewById(R.id.navigation_panel)
        routeSummaryPanel = findViewById(R.id.route_summary_panel)
        fabNavigate = findViewById(R.id.fab_navigate)
        fabMyLocation = findViewById(R.id.fab_my_location)
        fabBackroads = findViewById(R.id.fab_backroads)
        fabHome = findViewById(R.id.fab_home)
        fabExcludedStreets = findViewById(R.id.fab_excluded_streets)

        // Navigation panel
        textInstruction = findViewById(R.id.text_instruction)
        textStepDistance = findViewById(R.id.text_step_distance)
        textNavEta = findViewById(R.id.text_nav_eta)
        textNavRemaining = findViewById(R.id.text_nav_remaining)
        btnStopNavigation = findViewById(R.id.btn_stop_navigation)
        btnToggleVoice = findViewById(R.id.btn_toggle_voice)
        btnToggleOrientation = findViewById(R.id.btn_toggle_orientation)
        btnShowDirections = findViewById(R.id.btn_show_directions)

        // Route summary
        textRouteDistance = findViewById(R.id.text_route_distance)
        textRouteDuration = findViewById(R.id.text_route_duration)
        textRouteVia = findViewById(R.id.text_route_via)
        btnCancelRoute = findViewById(R.id.btn_cancel_route)
        btnStartNavigation = findViewById(R.id.btn_start_navigation)
        btnSaveHome = findViewById(R.id.btn_save_home)
        btnSaveWork = findViewById(R.id.btn_save_work)
        btnSaveFavorite = findViewById(R.id.btn_save_favorite)
        btnShowDirectionsSummary = findViewById(R.id.btn_show_directions_summary)

        routeRenderer = RouteRenderer(mapView)
    }

    private fun initMap() {
        mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(DEFAULT_ZOOM)
            controller.setCenter(DEFAULT_CENTER)
            zoomController.setVisibility(
                org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
            )
        }

        val mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (searchResultsCard.visibility == View.VISIBLE) {
                    searchResultsCard.visibility = View.GONE
                    editSearch.clearFocus()
                    hideKeyboard()
                    return true
                }
                return false
            }
            override fun longPressHelper(p: GeoPoint): Boolean {
                onMapLongPress(p)
                return true
            }
        })
        mapView.overlays.add(0, mapEventsOverlay)
        routeRenderer.drawExclusionZones(exclusionStore.streets)
    }

    private fun initSearch() {
        recyclerSearchResults.layoutManager = LinearLayoutManager(this)
        recyclerSearchResults.adapter = searchAdapter

        editSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && editSearch.text.isNullOrBlank()) {
                showSavedPlacesSuggestions()
            }
        }

        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                if (query.isEmpty() && editSearch.hasFocus()) {
                    showSavedPlacesSuggestions()
                } else if (query.length >= 3) {
                    debounceSearch(query)
                } else {
                    searchResultsCard.visibility = View.GONE
                    searchAdapter.clear()
                }
            }
        })

        editSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = editSearch.text.toString().trim()
                if (query.isNotEmpty()) performSearch(query)
                true
            } else false
        }

        btnClearSearch.setOnClickListener {
            editSearch.text.clear()
            searchResultsCard.visibility = View.GONE
            searchAdapter.clear()
            hideKeyboard()
        }
    }

    private fun initFabs() {
        fabNavigate.setOnClickListener {
            destinationPoint?.let { calculateRoute(it) }
                ?: Snackbar.make(mapView, "Search for a destination first",
                    Snackbar.LENGTH_SHORT).show()
        }

        fabMyLocation.setOnClickListener {
            userLocation?.let { loc ->
                mapView.controller.animateTo(loc)
                mapView.controller.setZoom(DEFAULT_ZOOM)
            } ?: Toast.makeText(this, "Waiting for GPS fix...", Toast.LENGTH_SHORT).show()
        }

        fabBackroads.setOnClickListener { toggleBackroadsMode() }

        fabHome.setOnClickListener { navigateHome() }
        fabHome.setOnLongClickListener { showSetHomeDialog(); true }

        fabExcludedStreets.setOnClickListener { showExcludedStreetsSheet() }

        btnStopNavigation.setOnClickListener { stopNavigation() }
        btnCancelRoute.setOnClickListener { cancelRoute() }
        btnStartNavigation.setOnClickListener { currentRoute?.let { startNavigation(it) } }

        // Voice toggle
        btnToggleVoice.setOnClickListener { toggleVoice() }

        // Map orientation toggle
        btnToggleOrientation.setOnClickListener { toggleMapOrientation() }

        // Directions list buttons
        btnShowDirections.setOnClickListener { showDirectionsSheet() }
        btnShowDirectionsSummary.setOnClickListener { showDirectionsSheet() }

        // Save-as buttons
        btnSaveHome.setOnClickListener {
            val dest = destinationPoint ?: return@setOnClickListener
            savedPlacesStore.setHome(destinationAddress, dest.latitude, dest.longitude)
            Snackbar.make(mapView, R.string.saved_as_home, Snackbar.LENGTH_SHORT).show()
        }
        btnSaveWork.setOnClickListener {
            val dest = destinationPoint ?: return@setOnClickListener
            savedPlacesStore.setWork(destinationAddress, dest.latitude, dest.longitude)
            Snackbar.make(mapView, R.string.saved_as_work, Snackbar.LENGTH_SHORT).show()
        }
        btnSaveFavorite.setOnClickListener {
            destinationPoint?.let { showSaveFavoriteDialog(it) }
        }
    }

    private fun initBottomSheet() {
        val bottomSheet = findViewById<LinearLayout>(R.id.bottom_sheet_excluded)
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN

        excludedAdapter = ExcludedStreetsAdapter { street ->
            exclusionStore.removeExclusion(street.id)
            refreshExcludedStreetsList()
            routeRenderer.drawExclusionZones(exclusionStore.streets)
            Snackbar.make(mapView, getString(R.string.street_unexcluded, street.name),
                Snackbar.LENGTH_SHORT).show()
        }
        findViewById<RecyclerView>(R.id.recycler_excluded_streets).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = excludedAdapter
        }
        findViewById<MaterialButton>(R.id.btn_clear_all_excluded).setOnClickListener {
            exclusionStore.clearAll()
            refreshExcludedStreetsList()
            routeRenderer.clearExclusionZones()
            Snackbar.make(mapView, "All exclusions cleared", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun initDirectionsSheet() {
        val sheet = findViewById<LinearLayout>(R.id.bottom_sheet_directions)
        directionsSheetBehavior = BottomSheetBehavior.from(sheet)
        directionsSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN

        directionsAdapter = DirectionsAdapter { _, step ->
            // Tap a step → zoom to that segment on the map
            val route = currentRoute ?: return@DirectionsAdapter
            val wpEnd = step.wayPoints.last.coerceAtMost(route.geometry.size - 1)
            val point = route.geometry[wpEnd]
            mapView.controller.animateTo(point)
            mapView.controller.setZoom(17.0)
            directionsSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
        findViewById<RecyclerView>(R.id.recycler_directions).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = directionsAdapter
        }
        findViewById<ImageButton>(R.id.btn_close_directions).setOnClickListener {
            directionsSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
    }

    // ── Voice ──────────────────────────────────────────────────────

    private fun toggleVoice() {
        voiceEnabled = !voiceEnabled
        btnToggleVoice.setImageResource(
            if (voiceEnabled) R.drawable.ic_volume_up else R.drawable.ic_volume_off
        )
        Snackbar.make(mapView,
            if (voiceEnabled) R.string.voice_on else R.string.voice_off,
            Snackbar.LENGTH_SHORT
        ).show()
        if (!voiceEnabled) tts?.stop()
    }

    private fun speak(text: String) {
        if (!voiceEnabled || !ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav_${System.currentTimeMillis()}")
    }

    // ── Map Orientation ────────────────────────────────────────────

    private fun toggleMapOrientation() {
        headingUpMode = !headingUpMode
        if (headingUpMode) {
            btnToggleOrientation.imageTintList =
                ContextCompat.getColorStateList(this, R.color.primary)
            Snackbar.make(mapView, R.string.heading_up, Snackbar.LENGTH_SHORT).show()
        } else {
            // Reset to north-up
            mapView.mapOrientation = 0f
            btnToggleOrientation.imageTintList =
                ContextCompat.getColorStateList(this, R.color.gray_600)
            Snackbar.make(mapView, R.string.north_up, Snackbar.LENGTH_SHORT).show()
        }
    }

    // ── Directions Sheet ───────────────────────────────────────────

    private fun showDirectionsSheet() {
        val route = currentRoute ?: return
        directionsAdapter.setSteps(route.steps)
        directionsSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    // ── Search (location-biased) ───────────────────────────────────

    private fun showSavedPlacesSuggestions() {
        val saved = savedPlacesStore.getAll()
        if (saved.isEmpty()) { searchResultsCard.visibility = View.GONE; return }
        searchAdapter.setResults(saved.map { it.toGeocodingResult() })
        searchResultsCard.visibility = View.VISIBLE
    }

    private fun debounceSearch(query: String) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            delay(400)
            performSearch(query)
        }
    }

    private fun performSearch(query: String) {
        lifecycleScope.launch {
            try {
                val loc = userLocation
                val nominatimResults = geocodingService.search(
                    query = query, nearLat = loc?.latitude, nearLon = loc?.longitude
                )
                val matchingSaved = savedPlacesStore.search(query)
                    .map { it.toGeocodingResult() }
                val combined = mutableListOf<GeocodingResult>()
                combined.addAll(matchingSaved)
                for (result in nominatimResults) {
                    val isDup = matchingSaved.any { s ->
                        Math.abs(s.lat - result.lat) < 0.0005 &&
                        Math.abs(s.lon - result.lon) < 0.0005
                    }
                    if (!isDup) combined.add(result)
                }
                if (combined.isNotEmpty()) {
                    searchAdapter.setResults(combined)
                    searchResultsCard.visibility = View.VISIBLE
                } else searchResultsCard.visibility = View.GONE
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity,
                    "Search failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onSearchResultSelected(result: GeocodingResult) {
        searchResultsCard.visibility = View.GONE
        hideKeyboard()

        val shortName = when {
            result.type.startsWith("saved_") -> {
                val idx = result.displayName.indexOf(" — ")
                if (idx > 0) result.displayName.substring(0, idx)
                else result.displayName.split(",").first().trim()
            }
            else -> result.displayName.split(",").first().trim()
        }
        editSearch.setText(shortName)
        editSearch.clearFocus()

        destinationName = shortName
        destinationAddress = result.displayName
        destinationPoint = GeoPoint(result.lat, result.lon)
        routeRenderer.setDestinationMarker(destinationPoint!!)

        if (result.boundingBox != null) {
            mapView.zoomToBoundingBox(result.boundingBox, true, 100)
        } else {
            mapView.controller.animateTo(destinationPoint)
            mapView.controller.setZoom(DEFAULT_ZOOM)
        }
        calculateRoute(destinationPoint!!)
    }

    // ── Home Navigation ────────────────────────────────────────────

    private fun navigateHome() {
        val home = savedPlacesStore.getHome()
        if (home == null) {
            Snackbar.make(mapView, R.string.home_not_set, Snackbar.LENGTH_LONG)
                .setAction("Set") { showSetHomeDialog() }.show()
            return
        }
        destinationPoint = GeoPoint(home.lat, home.lon)
        destinationName = "Home"
        destinationAddress = home.address
        editSearch.setText("Home"); editSearch.clearFocus()
        routeRenderer.setDestinationMarker(destinationPoint!!)
        Snackbar.make(mapView, R.string.navigating_home, Snackbar.LENGTH_SHORT).show()
        calculateRoute(destinationPoint!!)
    }

    private fun showSetHomeDialog() {
        val currentHome = savedPlacesStore.getHome()
        val loc = userLocation
        val items = mutableListOf<String>()
        if (loc != null) items.add("Use current location")
        items.add("Search for an address")
        if (currentHome != null) items.add("Clear home")

        AlertDialog.Builder(this)
            .setTitle(R.string.set_home_title)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> if (loc != null) setHomeFromCurrentLocation() else searchAndSetHome()
                    1 -> if (loc != null) searchAndSetHome() else {
                        if (currentHome != null) {
                            savedPlacesStore.clearHome()
                            Snackbar.make(mapView, "Home cleared", Snackbar.LENGTH_SHORT).show()
                        }
                    }
                    2 -> {
                        savedPlacesStore.clearHome()
                        Snackbar.make(mapView, "Home cleared", Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun setHomeFromCurrentLocation() {
        val loc = userLocation ?: return
        lifecycleScope.launch {
            val result = geocodingService.reverseGeocode(loc.latitude, loc.longitude)
            val address = result?.displayName ?: "${loc.latitude}, ${loc.longitude}"
            savedPlacesStore.setHome(address, loc.latitude, loc.longitude)
            Snackbar.make(mapView, R.string.home_saved, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun searchAndSetHome() {
        pendingSaveKind = "home"
        editSearch.text.clear()
        editSearch.requestFocus()
        editSearch.hint = "Search home address…"
        showKeyboard()
        Snackbar.make(mapView, "Search and tap a result to set as Home",
            Snackbar.LENGTH_LONG).show()
    }

    private fun showSaveFavoriteDialog(dest: GeoPoint) {
        val input = EditText(this).apply {
            hint = getString(R.string.enter_favorite_name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(destinationName); selectAll()
            setPadding(64, 32, 64, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("Save Favorite").setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim().ifBlank { destinationName }
                savedPlacesStore.addFavorite(name, destinationAddress, dest.latitude, dest.longitude)
                Snackbar.make(mapView, getString(R.string.favorite_saved, name),
                    Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    // ── Routing ────────────────────────────────────────────────────

    private fun calculateRoute(destination: GeoPoint) {
        val origin = userLocation
        if (origin == null) {
            Snackbar.make(mapView, "Waiting for GPS location...",
                Snackbar.LENGTH_SHORT).show()
            return
        }

        if (routingService.apiKey.isBlank()) {
            askForOrsKey(rejected = false, destination)
            return
        }

        lifecycleScope.launch {
            Snackbar.make(mapView,
                if (isRerouting) R.string.rerouting else R.string.route_calculating,
                Snackbar.LENGTH_SHORT).show()

            val options = RoutingOptions(
                backroadsMode = backroadsMode,
                avoidPolygons = exclusionStore.getAvoidPolygons()
            )

            try {
                val route = routingService.getRoute(origin, destination, options)
                if (route != null) {
                    currentRoute = route
                    if (isRerouting) {
                        // Auto-restart navigation after reroute
                        isRerouting = false
                        showRouteOnMap(route)
                        startNavigation(route)
                        speak("Route recalculated")
                    } else {
                        showRoute(route)
                    }
                } else {
                    isRerouting = false
                    Snackbar.make(mapView, R.string.route_failed, Snackbar.LENGTH_LONG).show()
                }
            } catch (e: ApiKeyRejectedException) {
                askForOrsKey(rejected = true, destination)
            } catch (e: Exception) {
                isRerouting = false
                Snackbar.make(mapView,
                    "Route error: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    /** The user's OpenRouteService key, else the one built in from local.properties, else "". */
    private fun storedOrsKey(): String =
        getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE).getString(PREF_ORS_KEY, null)
            ?.takeIf { it.isNotBlank() } ?: BuildConfig.ORS_API_KEY

    /**
     * Ask for the user's OpenRouteService key, keep it, and calculate the route to
     * [destination] with it. [rejected]: the service refused the key in use.
     *
     * A reroute stays one until the dialog closes (isRerouting stays set, so navigation
     * restarts on the new route), which also keeps further off-route fixes from asking
     * again; closing the dialog without a key ends it. Only one such dialog at a time.
     */
    private fun askForOrsKey(rejected: Boolean, destination: GeoPoint) {
        if (orsKeyDialogShowing) return
        orsKeyDialogShowing = true
        var saved = false
        val input = EditText(this).apply {
            hint = getString(R.string.ors_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
            setText(routingService.apiKey); selectAll()
            setPadding(64, 32, 64, 16)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.ors_key_title)
            .setMessage(if (rejected) R.string.ors_key_rejected else R.string.ors_key_message)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val key = input.text.toString().trim()
                if (key.isEmpty()) return@setPositiveButton
                saved = true
                getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE).edit()
                    .putString(PREF_ORS_KEY, key).apply()
                routingService.apiKey = key
                calculateRoute(destination)
            }
            .setNeutralButton(R.string.ors_key_get) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ORS_SIGNUP_URL)))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, ORS_SIGNUP_URL, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener {
                orsKeyDialogShowing = false
                if (!saved) isRerouting = false
            }
            .show()
    }

    /** Draw route on map without showing the summary panel. */
    private fun showRouteOnMap(route: Route) {
        routeRenderer.clearRoute()
        routeRenderer.drawRoute(route, backroadsMode)
        routeRenderer.setOriginMarker(route.geometry.first())
        routeRenderer.setDestinationMarker(route.geometry.last())
    }

    private fun showRoute(route: Route) {
        showRouteOnMap(route)
        mapView.zoomToBoundingBox(route.boundingBox, true, 100)

        textRouteDistance.text = formatDistance(route.distanceMeters)
        textRouteDuration.text = formatDuration(route.durationSeconds)

        val viaStreets = route.steps
            .filter { it.name.isNotBlank() }
            .map { it.name }.distinct().take(3)
            .joinToString(", ")
        textRouteVia.text = if (viaStreets.isNotBlank()) "via $viaStreets" else ""

        routeSummaryPanel.visibility = View.VISIBLE
        navigationPanel.visibility = View.GONE
    }

    private fun startNavigation(route: Route) {
        isNavigating = true
        spokenThresholds.clear()
        routeSummaryPanel.visibility = View.GONE
        navigationPanel.visibility = View.VISIBLE

        navigationEngine.startNavigation(route)

        // Speak the first instruction
        if (route.steps.isNotEmpty()) {
            speak(route.steps[0].instruction)
        }

        userLocation?.let { loc ->
            mapView.controller.animateTo(loc)
            mapView.controller.setZoom(17.0)
        }
    }

    private fun stopNavigation() {
        isNavigating = false
        isRerouting = false
        navigationEngine.stopNavigation()
        navigationPanel.visibility = View.GONE
        routeSummaryPanel.visibility = View.GONE
        routeRenderer.clearAll()
        routeRenderer.drawExclusionZones(exclusionStore.streets)
        currentRoute = null
        destinationPoint = null
        destinationName = ""
        destinationAddress = ""
        tts?.stop()

        // Reset map orientation to north-up
        if (headingUpMode) {
            mapView.mapOrientation = 0f
        }
        mapView.controller.setZoom(DEFAULT_ZOOM)
    }

    private fun cancelRoute() {
        routeSummaryPanel.visibility = View.GONE
        routeRenderer.clearRoute()
        routeRenderer.clearMarkers()
        currentRoute = null
    }

    // ── Backroads Mode ─────────────────────────────────────────────

    private fun toggleBackroadsMode() {
        backroadsMode = !backroadsMode
        if (backroadsMode) {
            fabBackroads.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.amber)
            fabBackroads.imageTintList =
                ContextCompat.getColorStateList(this, android.R.color.white)
            Snackbar.make(mapView, R.string.backroads_on, Snackbar.LENGTH_SHORT).show()
        } else {
            fabBackroads.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.gray_200)
            fabBackroads.imageTintList =
                ContextCompat.getColorStateList(this, R.color.gray_800)
            Snackbar.make(mapView, R.string.backroads_off, Snackbar.LENGTH_SHORT).show()
        }
        destinationPoint?.let { if (currentRoute != null) calculateRoute(it) }
    }

    // ── Street Exclusion (Long Press) ──────────────────────────────

    private fun onMapLongPress(point: GeoPoint) {
        lifecycleScope.launch {
            try {
                val result = geocodingService.reverseGeocode(point.latitude, point.longitude)
                if (result?.streetName == null) {
                    Toast.makeText(this@MainActivity,
                        R.string.no_street_found, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                if (pendingSaveKind != null) {
                    val kind = pendingSaveKind!!
                    pendingSaveKind = null
                    editSearch.hint = getString(R.string.search_hint)
                    if (kind == "home") {
                        savedPlacesStore.setHome(
                            result.displayName, point.latitude, point.longitude)
                        Snackbar.make(mapView, R.string.home_saved,
                            Snackbar.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.exclude_street_title)
                    .setMessage(getString(R.string.exclude_street_message, result.streetName))
                    .setPositiveButton(R.string.exclude) { _, _ ->
                        excludeStreet(result.streetName, result)
                    }
                    .setNegativeButton(R.string.cancel, null).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity,
                    "Could not identify street: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun excludeStreet(
        streetName: String,
        reverseResult: com.backroads.navigator.api.ReverseGeocodingResult
    ) {
        lifecycleScope.launch {
            var streetGeometry: List<GeoPoint>? = null
            val osmType = reverseResult.osmType
            val osmId = reverseResult.osmId
            if (osmType != null && osmId != null) {
                try { streetGeometry = geocodingService.lookupWayGeometry(osmType, osmId) }
                catch (_: Exception) { }
            }
            exclusionStore.addExclusion(
                name = streetName, displayAddress = reverseResult.displayName,
                centerLat = reverseResult.lat, centerLon = reverseResult.lon,
                streetGeometry = streetGeometry
            )
            routeRenderer.drawExclusionZones(exclusionStore.streets)
            Snackbar.make(mapView, getString(R.string.street_excluded, streetName),
                Snackbar.LENGTH_LONG)
                .setAction("Undo") {
                    exclusionStore.streets.lastOrNull()?.let {
                        exclusionStore.removeExclusion(it.id)
                        routeRenderer.drawExclusionZones(exclusionStore.streets)
                    }
                }.show()
            destinationPoint?.let { if (currentRoute != null) calculateRoute(it) }
        }
    }

    // ── Excluded Streets Sheet ─────────────────────────────────────

    private fun showExcludedStreetsSheet() {
        refreshExcludedStreetsList()
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun refreshExcludedStreetsList() {
        val streets = exclusionStore.streets
        excludedAdapter.setStreets(streets)
        findViewById<TextView>(R.id.text_no_excluded).visibility =
            if (streets.isEmpty()) View.VISIBLE else View.GONE
        findViewById<RecyclerView>(R.id.recycler_excluded_streets).visibility =
            if (streets.isEmpty()) View.GONE else View.VISIBLE
    }

    // ── NavigationEngine.Listener ──────────────────────────────────

    override fun onStepChanged(stepIndex: Int, step: RouteStep) {
        runOnUiThread {
            textInstruction.text = step.instruction
            textStepDistance.text = "for ${formatDistance(step.distanceMeters)}"
            spokenThresholds.clear() // reset thresholds for new step
            speak(step.instruction)
        }
    }

    override fun onDistanceToNextStep(meters: Double) {
        runOnUiThread {
            textStepDistance.text = "in ${formatDistance(meters)}"

            // Voice approach warnings at thresholds (200m, 100m)
            val nextStep = navigationEngine.getNextStep()
            if (nextStep != null) {
                val thresholdMeters = when {
                    meters <= 110 && meters > 60 && !spokenThresholds.contains(100) -> 100
                    meters <= 220 && meters > 150 && !spokenThresholds.contains(200) -> 200
                    else -> null
                }
                if (thresholdMeters != null) {
                    spokenThresholds.add(thresholdMeters)
                    val dist = if (thresholdMeters >= 200) "In 200 meters" else "In 100 meters"
                    speak("$dist, ${nextStep.instruction}")
                }
            }
        }
    }

    override fun onOffRoute() {
        runOnUiThread {
            if (isRerouting) return@runOnUiThread // debounce: already rerouting
            isRerouting = true
            speak("Off route. Recalculating.")
            Snackbar.make(mapView, R.string.rerouting, Snackbar.LENGTH_SHORT).show()
            navigationEngine.stopNavigation()
            destinationPoint?.let { calculateRoute(it) }
        }
    }

    override fun onArrived() {
        runOnUiThread {
            isNavigating = false
            navigationPanel.visibility = View.GONE
            speak("You have arrived at your destination.")
            Snackbar.make(mapView, R.string.arrived, Snackbar.LENGTH_LONG).show()
            routeRenderer.clearRoute()
            if (headingUpMode) mapView.mapOrientation = 0f
        }
    }

    override fun onRouteProgress(
        coveredMeters: Double, remainingMeters: Double, etaSeconds: Double
    ) {
        runOnUiThread {
            textNavRemaining.text = formatDistance(remainingMeters)
            textNavEta.text = formatDuration(etaSeconds)
        }
    }

    // ── Location ───────────────────────────────────────────────────

    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST)
        } else {
            startLocationUpdates()
            moveToCurrentLocation()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startLocationUpdates()
            moveToCurrentLocation()
        } else if (requestCode == LOCATION_PERMISSION_REQUEST) {
            Toast.makeText(this, R.string.location_permission_required,
                Toast.LENGTH_LONG).show()
        }
    }

    /** Location updates from GPS and the network, from Android itself (no Google services). */
    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        // onResume and the permission grant both start updates: register the listener once.
        locationManager.removeUpdates(locationListener)
        for (provider in LOCATION_PROVIDERS) {
            if (provider in locationManager.allProviders) {
                locationManager.requestLocationUpdates(
                    provider, LOCATION_INTERVAL_MS, 0f, locationListener, Looper.getMainLooper())
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun moveToCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        val last = LOCATION_PROVIDERS
            .filter { it in locationManager.allProviders }
            .mapNotNull { locationManager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
        if (last == null) {
            centerOnNextFix = true
            return
        }
        userLocation = GeoPoint(last.latitude, last.longitude)
        mapView.controller.animateTo(userLocation)
        mapView.controller.setZoom(DEFAULT_ZOOM)
        routeRenderer.updateUserLocation(userLocation!!)
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun formatDistance(meters: Double): String = when {
        meters >= 1000 -> String.format("%.1f km", meters / 1000)
        else -> String.format("%.0f m", meters)
    }

    private fun formatDuration(seconds: Double): String {
        val totalMinutes = (seconds / 60).toInt()
        return when {
            totalMinutes >= 60 -> "${totalMinutes / 60}h ${totalMinutes % 60}min"
            totalMinutes > 0 -> "$totalMinutes min"
            else -> "< 1 min"
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editSearch.windowToken, 0)
    }

    private fun showKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(editSearch, InputMethodManager.SHOW_IMPLICIT)
    }
}
