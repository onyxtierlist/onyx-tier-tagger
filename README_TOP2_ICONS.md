# Onyx Tier Tagger — Top 2 Tier + Gamemode Icons

This build is based directly on the F5FIX3 project and preserves its existing Fabric/Mixin architecture.

## Display

The player label is built as:

`[gamemode icon] TIER    PlayerName    TIER [gamemode icon]`

The API endpoint remains:

`https://onyx-website.onrender.com/api/onyx/player/<username>`

The mod reads the API's `top_tiers` array and uses the first two entries. It falls back to the existing `highest_tier_code` field if `top_tiers` is unavailable.

## Important

The source is intentionally changed only in the API data model, label construction, and bundled icon font assets. The existing mixin classes remain proper `@Mixin` classes; no bytecode/class-file patching is used.

## Build

Run `build_mod.bat` from Windows with Gradle available on PATH. The generated remapped JAR will be in `build/libs/`.
