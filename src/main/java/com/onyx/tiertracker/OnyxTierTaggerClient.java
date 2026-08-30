package com.onyx.tiertracker;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.Style;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class OnyxTierTaggerClient implements ClientModInitializer {
    public static final Map<UUID, TierInfo> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, TierInfo> NAME_CACHE = new ConcurrentHashMap<>();
    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static volatile long lastRefreshAt;

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build();
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Onyx-Tier-API");
        t.setDaemon(true);
        return t;
    });

    public static final String DEFAULT_API_URL = "https://onyx-website.onrender.com/api/onyx/players";
    public static String apiUrl = DEFAULT_API_URL;
    public static int refreshSeconds = 60;
    public static boolean showAboveHead = true;
    public static boolean showInTab = true;
    public static boolean showSelfName = true;
    public static String separator = " §8•§r ";

    public static final Identifier ICON_FONT = Identifier.of("onyx_tagger", "icons");

    @Override
    public void onInitializeClient() {
        loadConfig();
        // Do not hook the network/API into the render or tick path. A single
        // full-player-list request is scheduled in the background and then
        // applied on Minecraft's client thread. This avoids one HTTP request
        // per player and makes TAB rendering completely independent of the API.
        EXECUTOR.scheduleWithFixedDelay(() -> {
            try {
                MinecraftClient client = MinecraftClient.getInstance();
                client.execute(OnyxTierTaggerClient::refreshPlayerList);
            } catch (Throwable ignored) {}
        }, 0, Math.max(5, refreshSeconds), TimeUnit.SECONDS);
    }

    private static void refreshPlayerList() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        fetchFullPlayerList();
    }

    private static void fetchFullPlayerList() {
        String base = (apiUrl == null || apiUrl.isBlank()) ? DEFAULT_API_URL : apiUrl.trim();
        String url = base.replaceAll("/$", "");
        if (url.contains("/api/onyx/player/")) {
            // A user may have an old config pointing at the singular endpoint.
            // Leave it alone; individual fallback requests will handle it.
            return;
        }
        if (System.currentTimeMillis() - lastRefreshAt < Math.max(5, refreshSeconds) * 1000L) return;
        lastRefreshAt = System.currentTimeMillis();

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();

            HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    try {
                        if (response.statusCode() != 200) return;
                        TierInfo.cachePlayerList(response.body());
                    } catch (Throwable ignored) {}
                })
                .exceptionally(error -> null);
        } catch (Throwable ignored) {}
    }

    public static TierInfo get(PlayerEntity player) {
        if (player == null || player.getGameProfile() == null) return null;
        UUID id = player.getUuid();
        String name = player.getGameProfile().name();
        TierInfo cached = CACHE.get(id);
        return isFresh(cached) ? cached : null;
    }

    public static TierInfo get(PlayerListEntry entry) {
        if (entry == null || entry.getProfile() == null) return null;
        UUID id = entry.getProfile().id();
        String name = entry.getProfile().name();
        if (id == null || name == null || name.isBlank()) return null;

        TierInfo cached = CACHE.get(id);
        if (isFresh(cached)) return cached;

        TierInfo byName = NAME_CACHE.get(name.toLowerCase(Locale.ROOT));
        if (isFresh(byName)) {
            CACHE.put(id, byName);
            return byName;
        }

        // Never start network work from the TAB/render path. The background
        // scheduler refreshes the shared snapshot independently.
        return cached;
    }

    private static boolean isFresh(TierInfo info) {
        return info != null && System.currentTimeMillis() - info.fetchedAt() < refreshSeconds * 1000L;
    }

    public static void fetch(UUID uuid, String name) {
        if (uuid == null || name == null || name.isBlank()) return;
        TierInfo cached = CACHE.get(uuid);
        if (isFresh(cached)) return;

        TierInfo byName = NAME_CACHE.get(name.toLowerCase(Locale.ROOT));
        if (isFresh(byName)) {
            CACHE.put(uuid, byName);
            return;
        }

        // Prefer the full /players snapshot. If it has not populated the
        // requested player yet, make at most one per-player fallback request.
        String base = (apiUrl == null || apiUrl.isBlank()) ? DEFAULT_API_URL : apiUrl.trim();
        String url = base.replaceAll("/$", "");
        if (!url.contains("/api/onyx/player/")) {
            fetchFullPlayerList();
            return;
        }

        if (!IN_FLIGHT.add(uuid)) return;
        String encoded = encode(name);
        String playerUrl = url + "/" + encoded;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(playerUrl))
                .header("Accept", "application/json")
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();
            HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    try {
                        if (response.statusCode() != 200) return;
                        TierInfo info = TierInfo.parse(response.body());
                        if (info != null) {
                            CACHE.put(uuid, info);
                            NAME_CACHE.put(name.toLowerCase(Locale.ROOT), info);
                        }
                    } catch (Throwable ignored) {}
                })
                .exceptionally(error -> null)
                .whenComplete((ignored, error) -> IN_FLIGHT.remove(uuid));
        } catch (Throwable ignored) {
            IN_FLIGHT.remove(uuid);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
                    "separator= §8•§r \n");
                return;
            }

            for (String line : Files.readAllLines(path)) {
                if (line.startsWith("api_url=")) {
                    String value = line.substring(8).trim();
                    apiUrl = value.isBlank() ? DEFAULT_API_URL : value;
                } else if (line.startsWith("refresh_seconds=")) {
                    try { refreshSeconds = Math.max(5, Integer.parseInt(line.substring(16).trim())); }
                    catch (NumberFormatException ignored) { refreshSeconds = 60; }
                } else if (line.startsWith("show_above_head=")) {
                    showAboveHead = Boolean.parseBoolean(line.substring(16).trim());
                } else if (line.startsWith("show_in_tab=")) {
                    showInTab = Boolean.parseBoolean(line.substring(12).trim());
                } else if (line.startsWith("show_self_name=")) {
                    showSelfName = Boolean.parseBoolean(line.substring(15).trim());
                } else if (line.startsWith("separator=")) {
                    separator = line.substring(10);
                }
            }
        } catch (Exception ignored) {
            // Defaults are safe.
        }
    }

    public record TierInfo(String tier, String emoji, List<TierEntry> topTiers, long fetchedAt) {
        /**
         * Parses the same full player list used by the ONYX website.
         *
         * /api/onyx/player/<name> returns only highest_tier, so it cannot be
         * used to display all tested kits.  /api/onyx/players returns each
         * player with a rankings object such as:
         *
         *   "rankings": {
         *       "sword": {"rank":"HT1", ...},
         *       "uhc":   {"rank":"LT2", ...}
         *   }
         *
         * We find the requested player and recursively collect every ranking.
         */
        public static void cachePlayerList(String json) {
            try {
                Object root = new JsonParser(json).parse();
                if (!(root instanceof List<?> players)) return;
                long now = System.currentTimeMillis();
                for (Object value : players) {
                    if (!(value instanceof Map<?, ?> player)) continue;
                    String username = firstString(player, "name", "username");
                    if (username == null || username.isBlank()) continue;
                    TierInfo info = parsePlayerObject(player, now);
                    if (info == null) continue;
                    NAME_CACHE.put(username.toLowerCase(Locale.ROOT), info);
                    UUID uuid = parseUuid(firstString(player, "uuid", "id"));
                    if (uuid != null) CACHE.put(uuid, info);
                }
            } catch (Throwable ignored) {}
        }

        private static UUID parseUuid(String value) {
            if (value == null || value.isBlank()) return null;
            try { return UUID.fromString(value); } catch (IllegalArgumentException ignored) {}
            if (value.length() == 32) {
                try {
                    return UUID.fromString(value.substring(0,8)+"-"+value.substring(8,12)+"-"+value.substring(12,16)+"-"+value.substring(16,20)+"-"+value.substring(20));
                } catch (IllegalArgumentException ignored) {}
            }
            return null;
        }

        private static TierInfo parsePlayerObject(Map<?, ?> player, long fetchedAt) {
            String emoji = firstString(player, "emoji");
            if (emoji == null || emoji.isBlank()) emoji = "◆";
            List<TierEntry> all = new ArrayList<>();
            collectTierEntries(player.get("rankings"), null, all);
            if (all.isEmpty()) collectTierEntries(player.get("kitRanks"), null, all);
            if (all.isEmpty()) collectTierEntries(player.get("kits"), null, all);
            if (all.isEmpty()) collectTierEntries(player.get("tiers"), null, all);
            List<TierEntry> unique = uniqueTiers(all);
            if (unique.isEmpty()) return null;
            TierEntry best = unique.stream().max(java.util.Comparator.comparingLong(TierEntry::points)).orElse(unique.get(0));
            return new TierInfo(best.tier().toUpperCase(Locale.ROOT), emoji, List.copyOf(unique), fetchedAt);
        }

        public static TierInfo parsePlayerList(String json, String requestedName) {
            try {
                Object root = new JsonParser(json).parse();
                if (!(root instanceof List<?> players)) return null;
                for (Object value : players) {
                    if (!(value instanceof Map<?, ?> player)) continue;
                    String username = firstString(player, "name", "username");
                    if (username != null && username.equalsIgnoreCase(requestedName)) {
                        return parsePlayerObject(player, System.currentTimeMillis());
                    }
                }
            } catch (Throwable ignored) {}
            return null;
        }

        private static List<TierEntry> uniqueTiers(List<TierEntry> result) {
            List<TierEntry> unique = new ArrayList<>();
            Set<String> seen = new java.util.HashSet<>();
            for (TierEntry e : result) {
                if (e == null || e.tier() == null || e.tier().isBlank()) continue;
                String key = (e.gamemode() == null ? "unknown" : e.gamemode().toLowerCase(Locale.ROOT))
                    + "|" + e.tier().toUpperCase(Locale.ROOT);
                if (seen.add(key)) unique.add(e);
            }
            return unique;
        }

        public static TierInfo parse(String json) {
            try {
                String tier = value(json, "highest_tier_code");
                if (tier == null || tier.isBlank()) tier = value(json, "highest_tier");
                if (tier == null || tier.equalsIgnoreCase("none")) return null;

                String emoji = value(json, "emoji");
                if (emoji == null) emoji = "?";

                List<TierEntry> top = parseAllTiers(json);
                if (top.isEmpty()) {
                    String mode = value(json, "gamemode");
                    top = List.of(new TierEntry(mode == null ? "vanilla" : mode, tier.toUpperCase(Locale.ROOT), 0));
                }
                return new TierInfo(tier.toUpperCase(Locale.ROOT), emoji, List.copyOf(top), System.currentTimeMillis());
            } catch (Exception ignored) {
                return null;
            }
        }

        /**
         * Extract ALL tier records from the API response, not only the
         * "top_tiers" array. The Onyx API can contain lower/non-top tiers in
         * other arrays/objects, so we recursively walk the whole JSON value.
         *
         * Supported shapes include:
         *   {"gamemode":"sword","tier":"HT1"}
         *   {"gamemode":"sword","tier_code":"HT1"}
         *   {"sword":{"tier":"HT1"}}
         *   {"sword":{"tier_code":"HT1"}}
         */
        private static List<TierEntry> parseAllTiers(String json) {
            List<TierEntry> result = new ArrayList<>();
            try {
                Object root = new JsonParser(json).parse();
                collectTierEntries(root, null, result);
            } catch (Throwable ignored) {
                // Fall back to the old top_tiers parser if the server sends
                // malformed/unexpected JSON.
                result.addAll(parseTopTiersFallback(json));
            }

            return uniqueTiers(result);
        }

        @SuppressWarnings("unchecked")
        private static void collectTierEntries(Object value, String parentKey, List<TierEntry> out) {
            if (value instanceof Map<?, ?> map) {
                String mode = firstString(map, "gamemode", "game_mode", "mode", "gamemode_code");
                String tier = firstString(map, "tier", "tier_code", "tierCode", "rank", "rank_code");
                long points = firstNumber(map, "points", "score", "elo");

                // If the API uses a gamemode as an object key, use that key.
                if ((mode == null || mode.isBlank()) && looksLikeGamemode(parentKey)) {
                    mode = parentKey;
                }

                if (mode != null && tier != null && !tier.equalsIgnoreCase("none")) {
                    out.add(new TierEntry(mode, tier.toUpperCase(Locale.ROOT), points));
                }

                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String key = entry.getKey() == null ? null : String.valueOf(entry.getKey());
                    collectTierEntries(entry.getValue(), key, out);
                }
            } else if (value instanceof List<?> list) {
                for (Object item : list) collectTierEntries(item, parentKey, out);
            }
        }

        private static String firstString(Map<?, ?> map, String... keys) {
            for (String key : keys) {
                Object value = map.get(key);
                if (value instanceof String s && !s.isBlank()) return s;
                if (value instanceof Number n) return String.valueOf(n);
            }
            return null;
        }

        private static long firstNumber(Map<?, ?> map, String... keys) {
            for (String key : keys) {
                Object value = map.get(key);
                if (value instanceof Number n) return n.longValue();
                if (value instanceof String s) {
                    try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
                }
            }
            return 0;
        }

        private static boolean looksLikeGamemode(String key) {
            if (key == null || key.isBlank()) return false;
            return switch (key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "")) {
                case "sword", "uhc", "smp", "pot", "potion", "nethop", "netheriteop",
                     "netherite", "mace", "axe", "vanilla" -> true;
                default -> false;
            };
        }

        private static List<TierEntry> parseTopTiersFallback(String json) {
            List<TierEntry> result = new ArrayList<>();
            int start = json.indexOf("\"top_tiers\"");
            if (start < 0) return result;
            int arrayStart = json.indexOf('[', start);
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
                if (c == '{' && depth == 0) {
                    int end = findObjectEnd(json, i);
                    if (end < 0) break;
                    String object = json.substring(i, end + 1);
                    String mode = value(object, "gamemode");
                    String tier = value(object, "tier");
                    long points = numberValue(object, "points");
                    if (mode != null && tier != null && !tier.equalsIgnoreCase("none")) {
                        result.add(new TierEntry(mode, tier.toUpperCase(Locale.ROOT), points));
                    }
                    i = end;
                } else if (c == ']') break;
            }
            return result;
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

        /** Tiny dependency-free JSON parser used only for API responses. */
        private static final class JsonParser {
            private final String s;
            private int i;
            JsonParser(String s) { this.s = s; }

            Object parse() {
                skip();
                Object v = value();
                skip();
                return v;
            }

            private Object value() {
                skip();
                if (i >= s.length()) return null;
                char c = s.charAt(i);
                if (c == '{') return object();
                if (c == '[') return array();
                if (c == '"') return string();
                if (c == 't' && take("true")) return Boolean.TRUE;
                if (c == 'f' && take("false")) return Boolean.FALSE;
                if (c == 'n' && take("null")) return null;
                return number();
            }

            private Map<String, Object> object() {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                i++; skip();
                while (i < s.length() && s.charAt(i) != '}') {
                    String key = string(); skip();
                    if (i < s.length() && s.charAt(i) == ':') i++;
                    Object val = value(); m.put(key, val); skip();
                    if (i < s.length() && s.charAt(i) == ',') { i++; skip(); }
                    else break;
                }
                if (i < s.length() && s.charAt(i) == '}') i++;
                return m;
            }

            private List<Object> array() {
                List<Object> list = new ArrayList<>();
                i++; skip();
                while (i < s.length() && s.charAt(i) != ']') {
                    list.add(value()); skip();
                    if (i < s.length() && s.charAt(i) == ',') { i++; skip(); }
                    else break;
                }
                if (i < s.length() && s.charAt(i) == ']') i++;
                return list;
            }

            private String string() {
                if (i >= s.length() || s.charAt(i) != '"') return null;
                i++;
                StringBuilder b = new StringBuilder();
                while (i < s.length()) {
                    char c = s.charAt(i++);
                    if (c == '"') break;
                    if (c == '\\' && i < s.length()) {
                        char e = s.charAt(i++);
                        switch (e) {
                            case '"' -> b.append('"'); case '\\' -> b.append('\\');
                            case '/' -> b.append('/'); case 'b' -> b.append('\b');
                            case 'f' -> b.append('\f'); case 'n' -> b.append('\n');
                            case 'r' -> b.append('\r'); case 't' -> b.append('\t');
                            case 'u' -> {
                                if (i + 4 <= s.length()) {
                                    try { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); } catch (Exception ignored) {}
                                    i += 4;
                                }
                            }
                            default -> b.append(e);
                        }
                    } else b.append(c);
                }
                return b.toString();
            }

            private Number number() {
                int start = i;
                while (i < s.length() && "-+0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
                try {
                    String n = s.substring(start, i);
                    return (n.contains(".") || n.contains("e") || n.contains("E")) ? Double.parseDouble(n) : Long.parseLong(n);
                } catch (Exception e) { return 0; }
            }

            private boolean take(String token) {
                if (s.regionMatches(i, token, 0, token.length())) { i += token.length(); return true; }
                return false;
            }

            private void skip() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        }

        private static long numberValue(String json, String key) {
            try {
                String needle = "\"" + key + "\"";
                int i = json.indexOf(needle);
                if (i < 0) return 0;
                int colon = json.indexOf(':', i + needle.length());
                if (colon < 0) return 0;
                int start = colon + 1;
                while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
                int end = start;
                while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
                return Long.parseLong(json.substring(start, end));
            } catch (Exception ignored) { return 0; }
        }

        private static String value(String json, String key) {
            String needle = "\"" + key + "\"";
            int i = json.indexOf(needle);
            if (i < 0) return null;
            int colon = json.indexOf(':', i + needle.length());
            if (colon < 0) return null;
            int start = json.indexOf('"', colon + 1);
            if (start < 0) return null;
            int end = json.indexOf('"', start + 1);
            if (end <= start) return null;
            return json.substring(start + 1, end);
        }
    }

    public record TierEntry(String gamemode, String tier, long points) {}
}
