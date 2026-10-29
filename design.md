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
| `api/RoutingService.kt` | Routes from OpenRouteService's directions API (`driving-car`), which needs an API key. |
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
app works without Google Play Services (a requirement for F-Droid). Updates come from GPS and
the network provider every second. GPS fixes are used whenever one arrived in the last
10 seconds; network fixes only fill in when GPS has nothing recent, so the two do not
alternate and make the position jump. If no position is known when the map wants to centre
on the user, it centres on the first fix to arrive.

**The OpenRouteService key.** No key is built into published builds: each user gets their
own (free) and enters it. `RoutingService.apiKey` is the key in use: the one saved in the
`settings` SharedPreferences (`ors_api_key`), else `BuildConfig.ORS_API_KEY`, which is
`ORS_API_KEY` from `local.properties` (never committed, so only a developer's own builds carry
one), else blank. `calculateRoute` asks for a key (`askForOrsKey`) when it is blank, and again
when OpenRouteService answers 401 or 403, which `RoutingService` reports as
`ApiKeyRejectedException` (other failures still return null). Saving the key retries the
route. A reroute stays a reroute while the dialog is open (`isRerouting` stays set, so
navigation restarts on the new route, and further off-route fixes do not open more dialogs);
closing it without a key ends the reroute. There is no other place to change the key: a
rejected key is the time it needs changing.

## Publishing

Prepared for F-Droid: `fastlane/metadata/android/en-US/` holds the store listing,
`metadata/com.backroads.navigator.yml` the draft build recipe for F-Droid's fdroiddata
repository, and `app/build.gradle.kts` turns off the Google-encrypted dependency block F-Droid
rejects (`dependenciesInfo`). When the version is bumped, add
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
