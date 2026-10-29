# Backroads Navigator: design

Car navigation that can avoid highways and chosen streets. Kotlin, views (not Compose),
min SDK 26, target SDK 35. All map, search and routing data comes from free online services;
nothing in the app needs Google Play Services.

## Layout

| Path | What it is |
|---|---|
| `MainActivity.kt` | The single screen: map, search box, buttons, route summary, navigation panel and bottom sheets, plus all app state (route, destination, mode, navigation). |
| `BackroadsApp.kt` | Application: sets osmdroid's user agent, which OpenStreetMap's tile servers require. |
| `api/GeocodingService.kt` | Place search and reverse geocoding with OpenStreetMap's Nominatim (no key). Search is biased to about 50 km around the user. |
| `api/RoutingService.kt` | Routes from OpenRouteService's directions API (`driving-car`) at `api.heigit.org/openrouteservice`, HeiGIT's address for it (the old `api.openrouteservice.org` shuts down in November 2026). Needs an API key, from `account.heigit.org`. |
| `api/Models.kt` | Route, steps, geocoding results, routing options. |
| `navigation/NavigationEngine.kt` | Follows the user along a route: current step, distance to the next turn, progress and arrival time, off-route detection, arrival. |
| `navigation/RouteRenderer.kt` | Draws the route, markers, exclusion zones and the user's position (with accuracy circle and bearing) on the osmdroid map. |
| `exclusion/ExclusionStore.kt` | Excluded streets, kept in SharedPreferences, turned into avoid-polygons for routing (a buffer around each street). |
| `exclusion/SavedPlacesStore.kt` | Home, Work and favourites, in SharedPreferences. |

## Key flows

**Routing.** `calculateRoute` sends origin, destination and `RoutingOptions` to
`RoutingService`. Backroads mode asks OpenRouteService for the shortest route and to avoid
highways and toll roads; excluded streets go along as avoid-polygons.

**Navigation.** Each location fix goes to `NavigationEngine`, whose listener callbacks drive
the navigation panel and spoken instructions (Android's TextToSpeech). Going off route
triggers a reroute from the current position, which restarts navigation on the new route.

**Location.** Android's own `LocationManager`, not Google's fused location provider, so the
app works without Google Play Services (a requirement for F-Droid). With precise location it
takes GPS fixes every second and network fixes every 10 seconds; with only approximate
location (Android 12+ lets the user grant just that) the network alone. GPS fixes are used
whenever one arrived in the last 10 seconds; network fixes only fill in when GPS has nothing
recent, so the two do not alternate and make the position jump. During navigation, fixes less
accurate than 50 m (the engine's off-route distance) move the map but are kept from the
engine, or a vague network fix in a tunnel would set off a reroute from the wrong place. At
start the map centres on the newest position Android already knows; one more than two
minutes old shows the area but is not used as the user's position (routes do not start from
it). With none, or a stale one, the first live fix centres the map, unless the user has
touched the map or chosen a destination since.

**Route requests and their outcomes.** `RoutingService.getRoute` returns a route, or null
when the request failed (no network, a server error, an unreadable response), or throws a
`RoutingRefusedException` when OpenRouteService refuses the request itself, which a retry will
not fix: `ApiKeyRejectedException` (401, or 403 with requests left), `QuotaExceededException`
(403 with the `x-ratelimit-remaining` header at 0: OpenRouteService gives the same 403 text
for a bad key and a used-up daily quota) or `RateLimitedException` (429, the per-minute limit).
`calculateRoute` numbers each request (`routeRequest`); a response to any but the latest is
ignored, and stopping navigation or cancelling a route bumps the number, so a late answer
cannot bring back a route the user dropped. A failure that is not about the key goes through
`routingFailed`: it says why, and if the request was a reroute it also ends navigation,
because the engine stopped following the old route when the user left it.

**The OpenRouteService key.** No key is built into published builds: each user gets their
own (free, from account.heigit.org) and enters it. `RoutingService.apiKey` is the key in use:
the one saved in the `settings` SharedPreferences (`ors_api_key`), else
`BuildConfig.ORS_API_KEY`, which is `ORS_API_KEY` from `local.properties` (never committed, so
only a developer's own builds carry one), else blank. `calculateRoute` asks for a key
(`askForOrsKey`) when it is blank, and again on `ApiKeyRejectedException`. The dialog's Save
and "Get a key" buttons are handled after `show()` so they do not close it: the user can open
the sign-up page and come back to paste the key, and Save with an empty field says so.
Saving retries the route. A reroute stays a reroute while the dialog is open (`isRerouting`
stays set, so navigation restarts on the new route, and further off-route fixes do not open
more dialogs); closing it without a key is a failed reroute. There is no other place to
change the key: a rejected key is the time it needs changing.

## Publishing

Prepared for F-Droid: `fastlane/metadata/android/en-US/` holds the store listing,
`metadata/com.backroads.navigator.yml` the draft build recipe for F-Droid's fdroiddata
repository, and `app/build.gradle.kts` turns off the Google-encrypted dependency block F-Droid
rejects (`dependenciesInfo`). When the version is bumped, add
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
`fastlane/metadata/android/en-US/images/icon.png` is the launcher icon rendered at 512 px
(F-Droid cannot read a vector-only icon from the APK); when the icon changes, re-render it:
`python "D:\visual studio projects\F-Droid tools\render_store_icon.py" .`.
