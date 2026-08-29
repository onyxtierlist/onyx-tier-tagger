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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class OnyxTierTaggerClient implements ClientModInitializer {
    public static final Map<UUID, TierInfo> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, TierInfo> NAME_CACHE = new ConcurrentHashMap<>();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Onyx-Tier-API");
        t.setDaemon(true);
        return t;
    });

    public static String apiUrl = "http://localhost:8080/api/player";
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
            if (client.player.age % (20 * Math.max(5, refreshSeconds)) == 0) {
                refreshNearbyPlayers(client);
            }
        });
    }

    private static void refreshNearbyPlayers(MinecraftClient client) {
        for (PlayerEntity player : client.world.getPlayers()) {
            fetch(player.getUuid(), player.getGameProfile().name());
        }
    }

    public static TierInfo get(PlayerEntity player) {
        return CACHE.get(player.getUuid());
    }

    public static TierInfo get(PlayerListEntry entry) {
        UUID uuid = entry.getUuid();
        String username = entry.getProfile().name();
    
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
        if (NAME_CACHE.containsKey(username.toLowerCase()) && System.currentTimeMillis() - NAME_CACHE.get(username.toLowerCase()).fetchedAt() < refreshSeconds * 1000L) return;

        String url = apiUrl.replaceAll("/$", "") + "/" + encode(username);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().timeout(java.time.Duration.ofSeconds(5)).build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
            if (response.statusCode() != 200) return;
            TierInfo info = TierInfo.parse(response.body());
            if (info != null) {
                CACHE.put(uuid, info);
                NAME_CACHE.put(username.toLowerCase(), info);
            }
        }).exceptionally(ex -> null);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void loadConfig() {
        try {
            Path path = MinecraftClient.getInstance().runDirectory.toPath().resolve("config/onyx_tagger.properties");
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, "api_url=http://localhost:8080/api/player\nrefresh_seconds=60\nshow_above_head=true\nshow_in_tab=true\nshow_self_name=true\nseparator= §8• §r\n");
                return;
            }
            for (String line : Files.readAllLines(path)) {
                if (line.startsWith("api_url=")) apiUrl = line.substring(8).trim();
                else if (line.startsWith("refresh_seconds=")) refreshSeconds = Integer.parseInt(line.substring(16).trim());
                else if (line.startsWith("show_above_head=")) showAboveHead = Boolean.parseBoolean(line.substring(16).trim());
                else if (line.startsWith("show_in_tab=")) showInTab = Boolean.parseBoolean(line.substring(12).trim());
                else if (line.startsWith("show_self_name=")) showSelfName = Boolean.parseBoolean(line.substring(15).trim());
                else if (line.startsWith("separator=")) separator = line.substring(10);
            }
        } catch (Exception ignored) { }
    }

    public record TierInfo(String tier, String emoji, long fetchedAt) {
        public static TierInfo parse(String json) {
            try {
                String tier = value(json, "highest_tier");
                if (tier == null || tier.equalsIgnoreCase("none")) return null;
                String emoji = value(json, "emoji");
                if (emoji == null) emoji = "◆";
                return new TierInfo(tier.toUpperCase(), emoji, System.currentTimeMillis());
            } catch (Exception e) { return null; }
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
}
