# EinthusanTV — Project Handover

_Automatically refreshed by the Android TV APK build workflow._

## Repository
- GitHub: https://github.com/Vijaye82/EinthusanTV
- Main branch: `main`
- App: Kotlin / Jetpack Compose Android TV and Fire TV application

## Latest build checkpoint
- Workflow run: #211
- Build result: success
- Source commit: 2c534d90a35398f805aa2deda625b0cb1f20cbc1
- Workflow: https://github.com/Vijaye82/EinthusanTV/actions/runs/46121293120
- APK: workflow artifact `EinthusanTV-preview-apk` and prerelease.

## Current direction
Netflix-inspired TV presentation while retaining Einthusan branding, features and Android TV / Fire TV remote usability.

## Implemented areas
- Compose TV interface, catalogue browsing, language and genre filters, search and My List.
- Movie detail screen and Media3 / ExoPlayer playback screen.
- Einthusan account sign-in and direct playback resolution integration.
- Separate Einthusan source ID mapping from IMDb/catalogue IDs.
- Initial Netflix-inspired refresh: dark surfaces, red accent, featured hero banner, horizontal shelves and taller posters.

## Next steps
1. Verify this build; inspect Actions logs if it fails.
2. Refine D-pad focus states and remote navigation across the home screen and shelves.
3. Test catalogue loading, sign-in and authorised playback on a real Android TV / Fire TV device.
4. Capture real device screenshots.
5. Keep this handover current as implementation and priorities change.

## Testing notes
- CI success confirms compilation and packaging, not live Einthusan sign-in or stream playback.
- Direct playback still needs real account/session testing.
- Auto and HD use the standard watch route; UHD adds the UHD request option. Do not assume Auto and HD are distinct stream variants without verification.
- Stable signing secrets are needed for reliable in-place APK updates; otherwise CI uses a debug-signed APK.

## Working agreement
- GitHub source and latest Actions runs are the source of truth.
- Inspect current files, commits and build status before editing.
- Make changes directly in the repository, commit them and verify the resulting build.
- Update this file when priorities, implementation status or known issues change.
- Continue from the latest verified checkpoint; do not duplicate completed work.
