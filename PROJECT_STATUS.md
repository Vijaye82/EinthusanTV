# EinthusanTV — Project Handover

_Last updated: 2026-11-27. This file is also refreshed automatically by the Android TV APK build workflow._

## Repository
- GitHub: https://github.com/Vijaye82/EinthusanTV
- Main branch: `main`
- App: Kotlin / Jetpack Compose Android TV and Fire TV application

## Current direction
Refresh the existing EinthusanTV interface with a Netflix-inspired TV presentation while retaining Einthusan branding, existing features and Android TV / Fire TV remote usability.

## Implemented areas
- Compose-based TV interface, catalogue browsing, language and genre filters, search and My List.
- Movie detail screen and Media3 / ExoPlayer playback screen.
- Einthusan account sign-in flow and direct playback resolution integration.
- Separate Einthusan source ID mapping in catalogue models, rather than relying on IMDb IDs for playback.
- Initial Netflix-inspired visual refresh: black/dark surfaces, red accent, featured hero banner, horizontal content shelves and taller poster cards.

## Current work / next steps
1. Verify the latest Android build succeeds and inspect Actions logs if it fails.
2. Refine D-pad / remote focus states and navigation across the new home screen and shelves.
3. Test on a real Android TV / Fire TV device, including catalogue loading, sign-in and authorised playback.
4. Capture real device screenshots once running on a TV device.
5. Keep this handover current as implementation and priorities change.

## Testing notes / known limitations
- A successful CI build confirms compilation and packaging, not live Einthusan sign-in or stream playback.
- Direct playback still requires real account/session testing.
- Auto and HD currently use the standard watch route; UHD adds the UHD request option. Do not assume Auto and HD are distinct stream variants without verification.
- Confirm stable signing configuration before relying on seamless in-place APK updates. CI falls back to a debug-signed APK if release signing secrets are unavailable.

## Working agreement
- Treat the GitHub repository and latest Actions runs as the source of truth.
- Before editing, inspect current files, latest commits and build status.
- Make changes directly in the repository, commit them and verify the resulting build.
- Update this file when priorities, implementation status or known issues change.
- Continue from the latest verified checkpoint; do not duplicate completed work.
