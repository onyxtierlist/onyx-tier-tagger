package com.onyx.tiertracker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class OnyxTierTaggerClient implements ClientModInitializer {
    public static final Map<UUID, TierInfo> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, TierInfo> NAME_CACHE = new ConcurrentHashMap<>();
    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            // Render/API hosts may redirect HTTP requests. Browsers follow these,
            // but Java's default HttpClient does not.
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .build();
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Onyx-Tier-API");
        t.setDaemon(true);
        return t;
    });

    public static final String DEFAULT_API_URL = "https://onyx-website.onrender.com/api/onyx/player";
    public static String apiUrl = DEFAULT_API_URL;
    public static int refreshSeconds = 60;
    public static boolean showAboveHead = true;
    public static boolean showInTab = true;
    public static boolean showSelfName = true;
    public static String separator = " §8• ";

    @Override
    public void onInitializeClient() {
        loadConfig();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;
            // fetch() itself prevents duplicate requests, so calling this every
            // tick is safe and makes the first fetch reliable after joining.
            refreshNearbyPlayers(client);
        });
    }

    private static void refreshNearbyPlayers(MinecraftClient client) {
        for (PlayerEntity player : client.world.getPlayers()) {
            fetch(player.getUuid(), player.getGameProfile().name());
        }
    }

    public static TierInfo get(PlayerEntity player) {
        TierInfo info = CACHE.get(player.getUuid());
        if (info == null) {
            fetch(player.getUuid(), player.getGameProfile().name());
        }
        return info;
    }

    public static TierInfo get(PlayerListEntry entry) {
    var profile = entry.getProfile();
    UUID uuid = profile.id();
    String username = profile.name();

    TierInfo info = CACHE.get(uuid);
    if (info == null) {
        fetch(uuid, username);
    }
    return info;
    }

    public static void fetch(UUID uuid, String username) {
        if (username == null || username.isBlank()) return;
        TierInfo cached = CACHE.get(uuid);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt() < refreshSeconds * 1000L) return;
        TierInfo named = NAME_CACHE.get(username.toLowerCase());
        if (named != null && System.currentTimeMillis() - named.fetchedAt() < refreshSeconds * 1000L) {
            CACHE.put(uuid, named);
            return;
        }
        if (!IN_FLIGHT.add(uuid)) return;

        String baseUrl = apiUrl == null || apiUrl.isBlank() ? DEFAULT_API_URL : apiUrl;
        String url = baseUrl.replaceAll("/$", "") + "/" + encode(username);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .timeout(java.time.Duration.ofSeconds(30))
                .build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
            String body = response.body();
            System.out.println("[OnyxTierTagger] GET " + url + " -> " + response.statusCode());
            if (response.statusCode() != 200) {
                System.out.println("[OnyxTierTagger] API response: " + body);
                return;
            }
            TierInfo info = TierInfo.parse(body);
            if (info != null) {
                CACHE.put(uuid, info);
                NAME_CACHE.put(username.toLowerCase(), info);
                System.out.println("[OnyxTierTagger] Loaded " + username + " -> " + info.tier());
            } else {
                System.out.println("[OnyxTierTagger] API returned no usable tier for " + username + ": " + body);
            }
        }).exceptionally(ex -> {
            System.out.println("[OnyxTierTagger] API request failed for " + username + ": " + ex);
            return null;
        }).whenComplete((ignored, ex) -> IN_FLIGHT.remove(uuid));
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void loadConfig() {
        try {
            Path path = MinecraftClient.getInstance().runDirectory.toPath().resolve("config/onyx_tagger.properties");
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, "api_url=" + DEFAULT_API_URL + "\nrefresh_seconds=60\nshow_above_head=true\nshow_in_tab=true\nshow_self_name=true\nseparator= §8• §r\n");
                return;
            }
            for (String line : Files.readAllLines(path)) {
                if (line.startsWith("api_url=")) {
                    String configured = line.substring(8).trim();
                    // Migrate configs created by earlier builds.
                    if (configured.isBlank() || configured.contains("localhost:8080") || configured.contains("/api/player")) {
                        apiUrl = DEFAULT_API_URL;
                    } else {
                        apiUrl = configured;
                    }
                }
                else if (line.startsWith("refresh_seconds=")) {
                    try {
                        refreshSeconds = Math.max(5, Integer.parseInt(line.substring(16).trim()));
                    } catch (NumberFormatException ignored) {
                        refreshSeconds = 60;
                    }
                }
                else if (line.startsWith("show_above_head=")) showAboveHead = Boolean.parseBoolean(line.substring(16).trim());
                else if (line.startsWith("show_in_tab=")) showInTab = Boolean.parseBoolean(line.substring(12).trim());
                else if (line.startsWith("show_self_name=")) showSelfName = Boolean.parseBoolean(line.substring(15).trim());
                else if (line.startsWith("separator=")) separator = line.substring(10);
            }
        } catch (Exception ignored) { }
    }

    public record TierInfo(String tier, String emoji, java.util.List<TierEntry> topTiers, long fetchedAt) {
        public static TierInfo parse(String json) {
            try {
                String tier = value(json, "highest_tier_code");
                if (tier == null || tier.isBlank()) tier = value(json, "highest_tier");
                if (tier == null || tier.equalsIgnoreCase("none")) return null;

                String emoji = value(json, "emoji");
                if (emoji == null) emoji = "◆";

                java.util.List<TierEntry> top = parseTopTiers(json);
                if (top.isEmpty()) {
                    // Backwards-compatible fallback for older API responses.
                    String mode = value(json, "gamemode");
                    top = java.util.List.of(new TierEntry(mode == null ? "vanilla" : mode, tier.toUpperCase(), 0));
                }
                return new TierInfo(tier.toUpperCase(), emoji, top, System.currentTimeMillis());
            } catch (Exception e) {
                return null;
            }
        }

        private static java.util.List<TierEntry> parseTopTiers(String json) {
            java.util.List<TierEntry> result = new java.util.ArrayList<>();
            int arrayStart = json.indexOf("\"top_tiers\"");
            if (arrayStart < 0) return result;
            arrayStart = json.indexOf('[', arrayStart);
            if (arrayStart < 0) return result;

            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int i = arrayStart + 1; i < json.length(); i++) {
                char c = json.charAt(i);
                if (inString) {
                    if (escaped) escaped = false;
                    else if (c == '\\') escaped = true;
                    else if (c == '"') inString = false;
                    continue;
                }
                if (c == '"') { inString = true; continue; }
                if (c == '{') {
                    if (depth == 0) {
                        int end = findObjectEnd(json, i);
                        if (end < 0) break;
                        String object = json.substring(i, end + 1);
                        String mode = value(object, "gamemode");
                        String tier = value(object, "tier");
                        long points = numberValue(object, "points");
                        if (mode != null && tier != null && !tier.equalsIgnoreCase("none")) {
                            result.add(new TierEntry(mode, tier.toUpperCase(), points));
                            if (result.size() >= 2) break;
                        }
                        i = end;
                    } else depth++;
                } else if (c == ']') break;
            }
            return java.util.List.copyOf(result);
        }

        private static int findObjectEnd(String json, int start) {
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (inString) {
                    if (escaped) escaped = false;
                    else if (c == '\\') escaped = true;
                    else if (c == '"') inString = false;
                    continue;
                }
                if (c == '"') inString = true;
                else if (c == '{') depth++;
                else if (c == '}' && --depth == 0) return i;
            }
            return -1;
        }

        private static long numberValue(String json, String key) {
            String needle = "\"" + key + "\"";
            int i = json.indexOf(needle);
            if (i < 0) return 0;
            int colon = json.indexOf(':', i + needle.length());
            if (colon < 0) return 0;
            int start = colon + 1;
            while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
            try { return Long.parseLong(json.substring(start, end)); }
            catch (Exception ignored) { return 0; }
        }

        private static String value(String json, String key) {
            String needle = "\"" + key + "\"";
            int i = json.indexOf(needle);
            if (i < 0) return null;
            int colon = json.indexOf(':', i + needle.length());
            if (colon < 0) return null;
            int start = json.indexOf('"', colon + 1);
            int end = json.indexOf('"', start + 1);
            return start >= 0 && end > start ? json.substring(start + 1, end) : null;
        }
    }

    public record TierEntry(String gamemode, String tier, long points) {}

}
