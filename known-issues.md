# Known issues

## Not yet run on a device since the 2026-10-29 changes

Location now comes from Android's LocationManager (not Google Play Services), the app asks
each user for an OpenRouteService key, routing failures are told apart (bad key, used-up
quota, per-minute limit), and routes go to api.heigit.org. All of it builds; none of it has
run on a phone yet. Before a release, try: routing with no key, a wrong key and a good one;
"Get a key" and coming back to the dialog; navigation, including leaving the route; and
granting only "Approximate" location.

## Low-contrast launcher icon

The icon is a dark grey road (`#424242`, `drawable/ic_backroads.xml`) on blue (`#1565C0`),
which is hard to see at launcher size and in the F-Droid listing. A white or light foreground
would read better (the same drawable is tinted white on the backroads button already).

## No license notices for bundled libraries

The APK carries none of the license notices of the libraries it bundles (AndroidX, OkHttp,
Gson and osmdroid are Apache-2.0, which asks for its NOTICE files to go along with binary copies),
and the app has no licenses screen. Every published APK, GitHub's and F-Droid's once there, is
such a copy. The fix: collect the Gradle dependencies' licenses with the AboutLibraries plugin
(free software, no Google libraries) and show them on an "Open-source licenses" screen. Found
by the code review of the F-Droid preparation, 2026-10-29.
