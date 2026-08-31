# Onyx Tier Tagger - Fabric 1.21.11

Client-side Fabric mod for Minecraft 1.21.11.

Features:
- Shows the player's highest Onyx tier above their name.
- Shows the tier in the TAB/player list.
- Shows your own nameplate in third person (F5), with your Onyx tier.
- Reads tier data from the configured Onyx Railway API.
- Caches results and refreshes them periodically.

## Build

Requirements:
- Java 21
- Gradle 9.2.0 available as `gradle` in your terminal

From this folder:

    gradle clean build

or:

    .\gradlew.bat clean build

The built JAR is created in `build/libs/`.

## Config

After launching Minecraft once, edit:

    .minecraft/config/onyx_tagger.properties

Example:

    api_url=https://YOUR-RAILWAY-DOMAIN/api/player
    refresh_seconds=60
    show_above_head=true
    show_in_tab=true
    show_self_name=true
    separator= • 

## Notes

Minecraft 1.21.11 is an obfuscated release. This project uses Fabric Loom's remapping plugin and Yarn 1.21.11 mappings. Fabric's documentation recommends the remapping Loom plugin for 1.21.11 and earlier.
