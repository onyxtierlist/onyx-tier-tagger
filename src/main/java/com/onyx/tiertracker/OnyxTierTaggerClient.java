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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentHashMap.KeySetView;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern OBJECT_PATTERN = Pattern.compile("\\{\\s*\\\"gamemode\\\"\\s*:\\s*\\\"([^\"]*)\\\"\\s*,\\s*\\\"tier\\\"\\s*:\\s*\\\"([^\"]*)\\\"\\s*,\\s*\\\"points\\\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\}");
    private static final Pattern FIELD_PATTERN = Pattern.compile("\\\"([^\"]+)\\\"\\s*:\\s*\\\"([^\"]*)\\\"");

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
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt() < refreshSeconds * 1000L) return;

        String key = username.toLowerCase(Locale.ROOT);
        TierInfo named = NAME_CACHE.get(key);
        if (named != null && System.currentTimeMillis() - named.fetchedAt() < refreshSeconds * 1000L) {
            CACHE.put(uuid, named);
            return;
        }

        if (!IN_FLIGHT.add(uuid)) return;

        String baseUrl = apiUrl == null || apiUrl.isBlank() ? DEFAULT_API_URL : apiUrl;
        String url = baseUrl.replaceAll("/$", "") + "/" + encode(username);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "application/json")
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(30))
                    .build();
        } catch (Exception ex) {
            IN_FLIGHT.remove(uuid);
            return;
        }

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
                NAME_CACHE.put(key, info);
                System.out.println("[OnyxTierTagger] Loaded " + username + " -> " + info.tier() + " (" + info.allTiers().size() + " tiers)");
            } else {
                System.out.println("[OnyxTierTagger] API returned no usable tier for " + username);
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
                String highestCode = value(json, "highest_tier_code");
                String highest = highestCode == null || highestCode.isBlank() ? value(json, "highest_tier") : highestCode;
                String emoji = value(json, "emoji");
                if (emoji == null || emoji.isBlank()) emoji = "◆";

                List<TierEntry> tiers = parseArray(json, "all_tiers");
                if (tiers.isEmpty()) tiers = parseArray(json, "top_tiers");

                // Old API fallback: preserve compatibility with the original mod.
                if (tiers.isEmpty() && highest != null && !highest.isBlank() && !highest.equalsIgnoreCase("none")) {
                    tiers = List.of(new TierEntry("vanilla", highest.toUpperCase(Locale.ROOT), 0));
                }
                if (tiers.isEmpty()) return null;

                tiers = new ArrayList<>(tiers);
                tiers.sort((a, b) -> Double.compare(b.points(), a.points()));
                String realHighest = tiers.get(0).tier();
                if (highest == null || highest.isBlank()) highest = realHighest;
                return new TierInfo(highest.toUpperCase(Locale.ROOT), emoji, tiers, System.currentTimeMillis());
            } catch (Exception e) {
                return null;
            }
        }

        private static List<TierEntry> parseArray(String json, String key) {
            List<TierEntry> result = new ArrayList<>();
            int keyPos = json.indexOf("\"" + key + "\"");
            if (keyPos < 0) return result;
            int open = json.indexOf('[', keyPos);
            if (open < 0) return result;
            int depth = 0;
            int close = -1;
            boolean inString = false;
            boolean escaped = false;
            for (int i = open; i < json.length(); i++) {
                char c = json.charAt(i);
                if (inString) {
                    if (escaped) escaped = false;
                    else if (c == '\\') escaped = true;
                    else if (c == '"') inString = false;
                    continue;
                }
                if (c == '"') { inString = true; continue; }
                if (c == '[') depth++;
                else if (c == ']') {
                    depth--;
                    if (depth == 0) { close = i; break; }
                }
            }
            if (close < 0) return result;

            String array = json.substring(open + 1, close);
            Matcher matcher = OBJECT_PATTERN.matcher(array);
            while (matcher.find()) {
                String gamemode = unescape(matcher.group(1));
                String tier = unescape(matcher.group(2));
                double points;
                try { points = Double.parseDouble(matcher.group(3)); }
                catch (NumberFormatException e) { points = 0; }
                if (!gamemode.isBlank() && !tier.isBlank()) result.add(new TierEntry(gamemode, tier.toUpperCase(Locale.ROOT), points));
            }
            return result;
        }

        private static String value(String json, String key) {
            Matcher matcher = FIELD_PATTERN.matcher(json);
            while (matcher.find()) if (matcher.group(1).equals(key)) return unescape(matcher.group(2));
            return null;
        }

        private static String unescape(String s) {
            return s.replace("\\\"", "\"").replace("\\\\", "\\");
        }
    }
}
