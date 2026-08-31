# Onyx Tier Tagger — final build notes

Target: Minecraft 1.21.11 / Fabric / Java 21

This source was corrected after the 2026-08-31 crash report.

Critical runtime fix:
- Mixin helper `buildAllTiersCentered` is PRIVATE. Mixin classes must not expose helper methods as public/protected methods because Mixin can try to apply them to the target class.

Compile fixes:
- Text builders use `MutableText` where `.append(...)` is called.
- Empty-tier return uses the compatible text copy path.

Packaging fix:
- Gradle JAR task uses `DuplicatesStrategy.EXCLUDE` so the generated license entry cannot fail packaging as a duplicate.

Features retained:
- `all_tiers` API data is used, with `top_tiers` fallback.
- Player name remains centered between tier groups.
- All available tiers are rendered.
- Gamemode icon mapping is retained.
- 32px icon resources/font height are retained.
- Tab list rendering is retained.
- Existing API/cache/config structure is retained.

Build:

    ./gradlew clean build --no-daemon

Use the generated JAR from `build/libs/`. Do not use any stale JAR bundled from an older build.
