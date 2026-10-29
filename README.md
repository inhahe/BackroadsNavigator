# Backroads Navigator

Turn-by-turn car navigation for Android that can keep you off the highways. Maps are
OpenStreetMap, place search is OpenStreetMap's Nominatim, and routes come from
OpenRouteService.

> **Work in progress.** Backroads Navigator is unfinished and has not been tested yet: it
> builds, but it has not been tried on a phone. Expect things not to work. (What still needs
> trying is listed in `known-issues.md`.)

## Features

- **Backroads mode** (the road button): routes avoid highways and toll roads and prefer the
  shortest way over the fastest, so you see the smaller roads.
- **Exclude streets**: long-press a street on the map to keep routes off it. The excluded-streets
  button lists them, to remove one or clear them all.
- **Search** for an address or place, and tap a result to route there.
- **Turn-by-turn navigation** with spoken directions (voice can be turned off), the remaining
  distance and arrival time, and a list of all the directions.
- **Rerouting**: leave the route and a new one is worked out from where you are.
- **North-up or heading-up** map while navigating.
- **Saved places**: Home (tap the Home button to go home; long-press it to set it), Work, and
  favourites.
- No ads, no tracking, no Google Play Services in the app: your position comes from
  Android's own location service (GPS, plus network location where the phone has it).

## The OpenRouteService key

Routes come from OpenRouteService, which needs a free API key:

1. Sign up at <https://account.heigit.org> and create a key for openrouteservice.
2. The first time you ask for a route, the app asks for the key. Paste it in and it is kept on
   the phone. If OpenRouteService ever rejects the key, the app asks again.

The free plan allows 2,000 routes a day.

For your own builds you can build a key in: put `ORS_API_KEY=<your key>` in `local.properties`
(next to `gradle.properties`). That file is never committed, so the key stays on your machine.

## Building

Needs a JDK from 17 to 21 (Gradle 8.5 runs on no newer one) and the Android SDK.

    ./gradlew assembleDebug        (gradlew.bat assembleDebug on Windows)

## License

MIT. See `LICENSE`.
