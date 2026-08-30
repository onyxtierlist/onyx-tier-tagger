# Onyx Tier Tagger 1.0.1 — ALL TIERS

Fabric client mod for Minecraft 1.21.11.

## API

The mod fetches each player's profile from:

`https://onyx-website.onrender.com/api/onyx/player/<USERNAME>`

## What changed

This version does **not** use only `top_tiers`.

The API response is parsed recursively so tier records can be collected from the **entire player response**, including lower/non-top tiers and tier records stored in nested objects/arrays.

Supported common response shapes include:

- `{ "gamemode": "sword", "tier": "HT1" }`
- `{ "gamemode": "sword", "tier_code": "HT1" }`
- `{ "sword": { "tier": "HT1" } }`
- `{ "sword": { "tier_code": "HT1" } }`

Exact duplicate `gamemode + tier` pairs are removed while preserving API order.

### Display

If the API contains, for example, multiple records such as:

`Sword: HT1`, `Sword: LT2`, `UHC: HT2`, `Pot: LT1`

the mod can display all of them instead of only the highest/top entries.

The same complete tier list is used for TAB and above-head labels.

## Important

The source is updated, but this environment does not have the Fabric Loom/Gradle toolchain installed, so the source has not been rebuilt into a new JAR here.

Run `./gradlew build` in a normal Fabric development environment with internet access to build the JAR.
\n\n## All-tier website API integration\nThe Minecraft mod now reads `https://onyx-website.onrender.com/api/onyx/players`, the same full player dataset used by the ONYX website. The singular `/api/onyx/player/<username>` endpoint only exposes the highest tier, so it cannot be used for all tested gamemodes. The mod matches the player's username in the full list and displays every tier found in `rankings`, including lower/non-highest tiers.\n