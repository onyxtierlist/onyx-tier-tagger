# ONYX Tier Tagger — Fabric 1.21.11

Client-side Fabric mod for ONYX. It reads the public ONYX player API and displays the highest tier next to a player's Minecraft name.

## Display

`[HT1 ◆] Steve`

The marker is configurable through the API response's `emoji` field. The bundled website backend supplies a Minecraft-font-safe symbol by default; you can change the mapping in `server.js`.

## API

The mod calls:

`GET https://onyx-website.onrender.com/api/onyx/player/<minecraft-name>`

The endpoint returns `highest_tier` and `emoji`.

## Config

After the first launch, edit:

`.minecraft/config/onyx_tagger.properties`

Set `api_url` to your Render backend.

## Build

Use Java 21. Run `gradlew.bat clean build` on Windows. The remapped JAR will be in `build/libs/`.

The source package includes the Gradle wrapper scripts and wrapper properties. If your checkout does not contain `gradle/wrapper/gradle-wrapper.jar`, regenerate/download the official wrapper once with a local Gradle installation or let the included GitHub Actions workflow build it.
