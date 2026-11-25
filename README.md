# EinthusanTV

A native Android TV / Fire TV application starter built with Kotlin and Jetpack Compose.

## Current status

This repository contains the initial TV-first Android app scaffold and a remote-friendly language selection screen. The catalogue is placeholder UI only.

## Build

1. Install Android Studio with Android SDK 35 and JDK 17.
2. Open this repository as a Gradle project.
3. Let Gradle sync, then run the `app` configuration on an Android TV or Fire TV device.

## Integration boundary

Catalogue data, Einthusan account authentication, and video playback are intentionally not implemented yet. Connect these only using interfaces and documentation explicitly supplied or approved by Einthusan. Do not scrape protected streams, bypass access controls, or redistribute content.

## Targets

- Google TV / Android TV
- Amazon Fire TV (keep the app independent of Google Play Services)

Package: `tv.einthusan.app`
