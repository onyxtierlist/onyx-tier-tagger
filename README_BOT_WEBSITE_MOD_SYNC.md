# Onyx Tier Tagger — Bot → Website → Mod sync

Uses the live complete-player endpoint:
https://onyx-website.onrender.com/api/onyx/players

The mod reads each player's `rankings` object when available, with `all_tiers` / `top_tiers` / highest-tier fallbacks for compatibility. It caches the complete player list and refreshes it once per configured refresh interval, so it does not request the full list every render tick.

All ranked gamemodes are sorted by points and rendered around the player name. The source icon PNGs remain 32×32 for clarity, while the font provider renders them at a normal 16px in-game glyph size.
