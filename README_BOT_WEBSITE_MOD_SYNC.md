# Onyx Tier Tagger — Bot → Website → Mod sync

Configured for the live Onyx website API:
https://onyx-website.onrender.com/api/onyx/player

Minecraft displays the actual tier code returned by the API (`HT1`, `LT1`, etc.).
The website may group these into `Tier 1`, `Tier 2`, etc.; the mod intentionally uses `highest_tier_code` first so Minecraft keeps the HT/LT code.

The mod fetches player data by Minecraft username and caches it. It also fetches immediately when a player label/tab entry is first requested, with an in-flight guard to avoid duplicate requests.

GitHub Actions already includes the Gradle wrapper executable permission step.
