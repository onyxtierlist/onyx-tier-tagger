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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Comparator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentHashMap.KeySetView;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class OnyxTierTaggerClient implements ClientModInitializer {
    public static final Map<UUID, TierInfo> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, TierInfo> NAME_CACHE = new ConcurrentHashMap<>();
    private static final KeySetView<UUID, Boolean> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .build();

    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Onyx-Tier-API");
        t.setDaemon(true);
        return t;
    });

    private static final String ALL_PLAYERS_URL = "https://onyx-website.onrender.com/api/onyx/players";
    private static volatile long allPlayersFetchedAt = 0L;
    private static volatile boolean allPlayersInFlight = false;


    public static final String DEFAULT_API_URL = "https://onyx-website.onrender.com/api/onyx/player";
    public static String apiUrl = DEFAULT_API_URL;
    public static int refreshSeconds = 60;
    public static boolean showAboveHead = true;
    public static boolean showInTab = true;
    public static boolean showSelfName = true;
    public static String separator = " §8• §r";

    @Override
    public void onInitializeClient() {
        loadConfig();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;
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
        if (info == null) fetch(player.getUuid(), player.getGameProfile().name());
        return info;
    }

    public static TierInfo get(PlayerListEntry entry) {
        var profile = entry.getProfile();
        UUID uuid = profile.id();
        String username = profile.name();
        TierInfo info = CACHE.get(uuid);
        if (info == null) fetch(uuid, username);
        return info;
    }

    public static void fetch(UUID uuid, String username) {
        if (username == null || username.isBlank()) return;

        TierInfo cached = CACHE.get(uuid);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.fetchedAt() < refreshSeconds * 1000L) return;

        String key = username.toLowerCase(Locale.ROOT);
        TierInfo named = NAME_CACHE.get(key);
        if (named != null && now - named.fetchedAt() < refreshSeconds * 1000L) {
            CACHE.put(uuid, named);
            return;
        }

        // The /players endpoint contains each player's complete rankings object.
        // Fetch that list once per refresh window instead of requesting one player
        // at a time and accidentally receiving only highest_tier.
        refreshAllPlayersIfNeeded();

        named = NAME_CACHE.get(key);
        if (named != null) CACHE.put(uuid, named);
    }

    private static void refreshAllPlayersIfNeeded() {
        long now = System.currentTimeMillis();
        if (allPlayersInFlight || (allPlayersFetchedAt != 0L && now - allPlayersFetchedAt < refreshSeconds * 1000L)) return;
        synchronized (OnyxTierTaggerClient.class) {
            now = System.currentTimeMillis();
            if (allPlayersInFlight || (allPlayersFetchedAt != 0L && now - allPlayersFetchedAt < refreshSeconds * 1000L)) return;
            allPlayersInFlight = true;
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(ALL_PLAYERS_URL))
                    .header("Accept", "application/json")
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(30))
                    .build();
        } catch (Exception ex) {
            allPlayersInFlight = false;
            return;
        }

        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
            try {
                if (response.statusCode() != 200) {
                    System.out.println("[OnyxTierTagger] /players -> " + response.statusCode());
                    return;
                }
                JsonElement root = JsonParser.parseString(response.body());
                int loaded = loadAllPlayers(root);
                allPlayersFetchedAt = System.currentTimeMillis();
                System.out.println("[OnyxTierTagger] Loaded complete rankings for " + loaded + " players");
            } catch (Exception ex) {
                System.out.println("[OnyxTierTagger] Failed to parse /players: " + ex);
            } finally {
                allPlayersInFlight = false;
            }
        }).exceptionally(ex -> {
            allPlayersInFlight = false;
            System.out.println("[OnyxTierTagger] /players request failed: " + ex);
            return null;
        });
    }

    private static int loadAllPlayers(JsonElement root) {
        int loaded = 0;
        if (root == null || root.isJsonNull()) return 0;

        JsonArray players = null;
        if (root.isJsonArray()) {
            players = root.getAsJsonArray();
        } else if (root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            for (String key : new String[]{"players", "data", "results"}) {
                JsonElement candidate = obj.get(key);
                if (candidate != null && candidate.isJsonArray()) {
                    players = candidate.getAsJsonArray();
                    break;
                }
            }
            // Also accept a username-keyed object: {"Fire101MC": {"rankings": ...}}
            if (players == null) {
                for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    JsonObject player = entry.getValue().getAsJsonObject();
                    if (player.has("rankings") || player.has("all_tiers") || player.has("top_tiers") || player.has("highest_tier")) {
                        JsonObject copy = player.deepCopy();
                        if (!copy.has("username")) copy.addProperty("username", entry.getKey());
                        if (players == null) players = new JsonArray();
                        players.add(copy);
                    }
                }
            }
        }
        if (players == null) return 0;

        for (JsonElement element : players) {
            if (!element.isJsonObject()) continue;
            JsonObject player = element.getAsJsonObject();
            String username = firstString(player, "username", "name", "ign", "player");
            if (username == null || username.isBlank()) continue;

            List<TierEntry> tiers = parseRankingsObject(player.get("rankings"));
            if (tiers.isEmpty()) tiers = parseTierArray(player.get("all_tiers"));
            if (tiers.isEmpty()) tiers = parseTierArray(player.get("top_tiers"));
            if (tiers.isEmpty()) {
                String highest = firstString(player, "highest_tier_code", "highest_tier");
                if (highest != null && !highest.isBlank() && !highest.equalsIgnoreCase("none")) {
                    tiers = List.of(new TierEntry("vanilla", highest.toUpperCase(Locale.ROOT), 0));
                }
            }
            if (tiers.isEmpty()) continue;

            tiers = new ArrayList<>(tiers);
            tiers.sort(Comparator.comparingDouble(TierEntry::points).reversed());
            String highest = tiers.get(0).tier();
            String apiHighest = firstString(player, "highest_tier_code", "highest_tier");
            if (apiHighest != null && !apiHighest.isBlank() && !apiHighest.equalsIgnoreCase("none")) highest = apiHighest.toUpperCase(Locale.ROOT);
            String emoji = firstString(player, "emoji");
            if (emoji == null || emoji.isBlank()) emoji = "◆";
            TierInfo info = new TierInfo(highest, emoji, tiers, System.currentTimeMillis());
            NAME_CACHE.put(username.toLowerCase(Locale.ROOT), info);
            loaded++;
        }
        return loaded;
    }

    private static List<TierEntry> parseRankingsObject(JsonElement rankingsElement) {
        List<TierEntry> tiers = new ArrayList<>();
        if (rankingsElement == null || !rankingsElement.isJsonObject()) return tiers;
        for (Map.Entry<String, JsonElement> entry : rankingsElement.getAsJsonObject().entrySet()) {
            String gamemode = entry.getKey();
            JsonElement value = entry.getValue();
            if (!value.isJsonObject()) continue;
            JsonObject rank = value.getAsJsonObject();
            String tier = firstString(rank, "tier", "tier_code", "rank");
            if (tier == null || tier.isBlank() || tier.equalsIgnoreCase("none")) continue;
            double points = number(rank, "points", 0);
            tiers.add(new TierEntry(gamemode, tier.toUpperCase(Locale.ROOT), points));
        }
        return tiers;
    }

    private static List<TierEntry> parseTierArray(JsonElement arrayElement) {
        List<TierEntry> tiers = new ArrayList<>();
        if (arrayElement == null || !arrayElement.isJsonArray()) return tiers;
        for (JsonElement element : arrayElement.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject obj = element.getAsJsonObject();
            String gamemode = firstString(obj, "gamemode", "mode", "kit");
            String tier = firstString(obj, "tier", "tier_code", "rank");
            if (gamemode == null || gamemode.isBlank() || tier == null || tier.isBlank()) continue;
            tiers.add(new TierEntry(gamemode, tier.toUpperCase(Locale.ROOT), number(obj, "points", 0)));
        }
        return tiers;
    }

    private static String firstString(JsonObject obj, String... keys) {
        for (String key : keys) {
            JsonElement e = obj.get(key);
            if (e != null && !e.isJsonNull() && e.isJsonPrimitive()) {
                try { return e.getAsString(); } catch (Exception ignored) { }
            }
        }
        return null;
    }

    private static double number(JsonObject obj, String key, double fallback) {
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull()) return fallback;
        try { return e.getAsDouble(); } catch (Exception ignored) { return fallback; }
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void loadConfig() {
        try {
            Path path = MinecraftClient.getInstance().runDirectory.toPath().resolve("config/onyx_tagger.properties");
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path,
                        "api_url=" + DEFAULT_API_URL + "\n" +
                        "refresh_seconds=60\n" +
                        "show_above_head=true\n" +
                        "show_in_tab=true\n" +
                        "show_self_name=true\n" +
                        "separator= §8• §r\n");
                return;
            }
            for (String line : Files.readAllLines(path)) {
                if (line.startsWith("api_url=")) {
                    String configured = line.substring(8).trim();
                    apiUrl = configured.isBlank() || configured.contains("localhost:8080") || configured.contains("/api/player")
                            ? DEFAULT_API_URL : configured;
                } else if (line.startsWith("refresh_seconds=")) {
                    try { refreshSeconds = Math.max(5, Integer.parseInt(line.substring(16).trim())); }
                    catch (NumberFormatException ignored) { refreshSeconds = 60; }
                } else if (line.startsWith("show_above_head=")) showAboveHead = Boolean.parseBoolean(line.substring(16).trim());
                else if (line.startsWith("show_in_tab=")) showInTab = Boolean.parseBoolean(line.substring(12).trim());
                else if (line.startsWith("show_self_name=")) showSelfName = Boolean.parseBoolean(line.substring(15).trim());
                else if (line.startsWith("separator=")) separator = line.substring(10);
            }
        } catch (Exception ignored) { }
    }

    public record TierEntry(String gamemode, String tier, double points) { }

    public record TierInfo(String tier, String emoji, List<TierEntry> allTiers, long fetchedAt) {
        public TierInfo {
            allTiers = List.copyOf(allTiers);
        }

        public static TierInfo parse(String json) {
            try {
                JsonElement root = JsonParser.parseString(json);
                if (!root.isJsonObject()) return null;
                JsonObject obj = root.getAsJsonObject();
                String highest = firstString(obj, "highest_tier_code", "highest_tier");
                String emoji = firstString(obj, "emoji");
                if (emoji == null || emoji.isBlank()) emoji = "◆";
                List<TierEntry> tiers = parseRankingsObject(obj.get("rankings"));
                if (tiers.isEmpty()) tiers = parseTierArray(obj.get("all_tiers"));
                if (tiers.isEmpty()) tiers = parseTierArray(obj.get("top_tiers"));
                if (tiers.isEmpty() && highest != null && !highest.isBlank() && !highest.equalsIgnoreCase("none")) {
                    tiers = List.of(new TierEntry("vanilla", highest.toUpperCase(Locale.ROOT), 0));
                }
                if (tiers.isEmpty()) return null;
                tiers = new ArrayList<>(tiers);
                tiers.sort(Comparator.comparingDouble(TierEntry::points).reversed());
                if (highest == null || highest.isBlank()) highest = tiers.get(0).tier();
                return new TierInfo(highest.toUpperCase(Locale.ROOT), emoji, tiers, System.currentTimeMillis());
            } catch (Exception e) {
                return null;
            }
        }
    }
}
