package fun.rederaven.cleaner.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Server authoritative, strict and atomic configuration loading. */
public final class CleanerSettings {
    public boolean enabled = true;
    public String language = "en_us";
    public boolean messagesEnabled = true;

    public boolean automaticEnabled = true;
    public int automaticIntervalMinutes = 10;
    public List<Integer> warningMinutes = List.of(5, 3, 2, 1);
    public List<Integer> warningSeconds = List.of(30, 10, 5, 4, 3, 2, 1);
    public boolean autoCleanItems = true;
    public boolean autoCleanMobs = true;
    public boolean autoCleanVehicles = true;

    public boolean manualEnabled = true;
    public boolean manualItems = true;
    public boolean manualMobs = true;
    public boolean manualVehicles = true;
    public boolean manualIgnoreItemAge = true;

    public boolean itemsEnabled = true;
    public int minimumItemAgeSeconds = 30;
    public List<String> protectedItemIds = List.of("minecraft:nether_star");

    public boolean mobsEnabled = true;
    public int minimumDistanceChunks = 6;
    public List<String> removableMobIds = List.of(
        "minecraft:zombie", "minecraft:husk", "minecraft:drowned",
        "minecraft:skeleton", "minecraft:stray", "minecraft:bogged",
        "minecraft:spider", "minecraft:cave_spider", "minecraft:creeper",
        "minecraft:slime", "minecraft:magma_cube", "minecraft:phantom",
        "minecraft:silverfish", "minecraft:endermite", "minecraft:witch"
    );

    public boolean overloadEnabled = true;
    public int overloadMinimumTotalMobs = 50;
    public int overloadMaxPerType = 20;
    public int overloadScanIntervalSeconds = 10;
    public int overloadCooldownSeconds = 30;
    // These can be disabled by admins, but immutable per-entity protections cannot.
    public boolean overloadIncludePlayerChunks = true;
    public boolean overloadIncludeClaimedChunks = true;

    public boolean vehiclesEnabled = true;
    public boolean removeBoats = true;
    public boolean removeMinecarts = true;
    public int vehicleMinimumDistanceChunks = 8;
    public List<String> protectedVehicleIds = List.of("minecraft:chest_minecart", "minecraft:hopper_minecart",
        "minecraft:chest_boat", "minecraft:chest_raft");

    public boolean emergencyEnabled = true;
    public int emergencyGlobalItemThreshold = 2000;
    public int emergencyChunkItemThreshold = 350;
    public int emergencyScanIntervalSeconds = 10;
    public int emergencyCooldownSeconds = 60;
    public boolean emergencyIgnoreItemAge = true;
    public int maximumRemovalsPerTick = 400;

    /** Optional server-side TAB bridge; no compile-time TAB dependency. */
    public boolean tabEnabled = true;
    public int tabRefreshIntervalMs = 1000;
    public boolean tabEnableStatus = true;
    public boolean tabEnableCountdown = true;
    public boolean tabEnableStatistics = true;

    public int adminOpLevel = 2;
    public int statusOpLevel = 0;

    public static Path path(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("serverconfig").resolve("ravencleaner.toml");
    }

    public static CleanerSettings load(MinecraftServer server) throws IOException {
        Path file = path(server);
        Files.createDirectories(file.getParent());
        if (Files.notExists(file)) {
            try (InputStream in = CleanerSettings.class.getResourceAsStream("/ravencleaner-default.toml")) {
                if (in == null) throw new IOException("Missing bundled configuration template");
                Files.copy(in, file);
            }
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            CommentedConfig c = new TomlParser().parse(reader);
            CleanerSettings s = new CleanerSettings();
            s.enabled = bool(c, "general.enabled", s.enabled);
            s.language = string(c, "language.code", s.language);
            s.messagesEnabled = bool(c, "language.messagesEnabled", bool(c, "messages.enabled", s.messagesEnabled));
            s.automaticEnabled = bool(c, "automatic.enabled", s.automaticEnabled);
            s.automaticIntervalMinutes = number(c, "automatic.intervalMinutes", s.automaticIntervalMinutes);
            s.warningMinutes = ints(c, "automatic.warningMinutes", s.warningMinutes);
            s.warningSeconds = ints(c, "automatic.warningSeconds", s.warningSeconds);
            s.autoCleanItems = bool(c, "automatic.cleanItems", s.autoCleanItems);
            s.autoCleanMobs = bool(c, "automatic.cleanDistantMobs", s.autoCleanMobs);
            s.autoCleanVehicles = bool(c, "automatic.cleanVehicles", s.autoCleanVehicles);
            s.manualEnabled = bool(c, "manual.enabled", s.manualEnabled);
            s.manualItems = bool(c, "manual.allowItems", s.manualItems);
            s.manualMobs = bool(c, "manual.allowMobs", s.manualMobs);
            s.manualVehicles = bool(c, "manual.allowVehicles", s.manualVehicles);
            s.manualIgnoreItemAge = bool(c, "manual.ignoreMinimumItemAge", s.manualIgnoreItemAge);
            s.itemsEnabled = bool(c, "items.enabled", s.itemsEnabled);
            s.minimumItemAgeSeconds = number(c, "items.minimumAgeSeconds", s.minimumItemAgeSeconds);
            s.protectedItemIds = strings(c, "items.protectedIds", s.protectedItemIds);
            s.mobsEnabled = bool(c, "mobs.enabled", s.mobsEnabled);
            s.minimumDistanceChunks = number(c, "mobs.minimumDistanceChunks", s.minimumDistanceChunks);
            s.removableMobIds = strings(c, "mobs.removableIds", s.removableMobIds);
            s.overloadEnabled = bool(c, "mobs.chunkOverload.enabled", s.overloadEnabled);
            s.overloadMinimumTotalMobs = number(c, "mobs.chunkOverload.minimumTotalMobs", s.overloadMinimumTotalMobs);
            s.overloadMaxPerType = number(c, "mobs.chunkOverload.maxPerType", s.overloadMaxPerType);
            s.overloadScanIntervalSeconds = number(c, "mobs.chunkOverload.scanIntervalSeconds", s.overloadScanIntervalSeconds);
            s.overloadCooldownSeconds = number(c, "mobs.chunkOverload.cooldownSeconds", s.overloadCooldownSeconds);
            s.overloadIncludePlayerChunks = bool(c, "mobs.chunkOverload.includePlayerChunks", s.overloadIncludePlayerChunks);
            s.overloadIncludeClaimedChunks = bool(c, "mobs.chunkOverload.includeClaimedChunks", s.overloadIncludeClaimedChunks);
            s.vehiclesEnabled = bool(c, "vehicles.enabled", s.vehiclesEnabled);
            s.removeBoats = bool(c, "vehicles.removeBoats", s.removeBoats);
            s.removeMinecarts = bool(c, "vehicles.removeMinecarts", s.removeMinecarts);
            s.vehicleMinimumDistanceChunks = number(c, "vehicles.minimumDistanceChunks", s.vehicleMinimumDistanceChunks);
            s.protectedVehicleIds = strings(c, "vehicles.protectedIds", s.protectedVehicleIds);
            s.emergencyEnabled = bool(c, "emergency.enabled", s.emergencyEnabled);
            s.emergencyGlobalItemThreshold = number(c, "emergency.globalItemThreshold", s.emergencyGlobalItemThreshold);
            s.emergencyChunkItemThreshold = number(c, "emergency.perChunkItemThreshold", s.emergencyChunkItemThreshold);
            s.emergencyScanIntervalSeconds = number(c, "emergency.scanIntervalSeconds", s.emergencyScanIntervalSeconds);
            s.emergencyCooldownSeconds = number(c, "emergency.cooldownSeconds", s.emergencyCooldownSeconds);
            s.emergencyIgnoreItemAge = bool(c, "emergency.ignoreMinimumItemAge", s.emergencyIgnoreItemAge);
            s.maximumRemovalsPerTick = number(c, "performance.maxRemovalsPerTick", s.maximumRemovalsPerTick);
            s.tabEnabled = bool(c, "integrations.tab.enabled", s.tabEnabled);
            s.tabRefreshIntervalMs = number(c, "integrations.tab.refreshIntervalMs", s.tabRefreshIntervalMs);
            s.tabEnableStatus = bool(c, "integrations.tab.enableStatus", s.tabEnableStatus);
            s.tabEnableCountdown = bool(c, "integrations.tab.enableCountdown", s.tabEnableCountdown);
            s.tabEnableStatistics = bool(c, "integrations.tab.enableStatistics", s.tabEnableStatistics);
            s.adminOpLevel = number(c, "permissions.adminOpLevel", s.adminOpLevel);
            s.statusOpLevel = number(c, "permissions.statusOpLevel", s.statusOpLevel);
            s.validate();
            return s;
        } catch (RuntimeException e) {
            throw new IOException("Invalid configuration: " + e.getMessage() + " (" + file + ")", e);
        }
    }

    private static Object raw(CommentedConfig c, String key) { return c.get(key); }
    private static boolean bool(CommentedConfig c, String key, boolean fallback) {
        Object v = raw(c, key);
        if (v == null) return fallback;
        if (v instanceof Boolean b) return b;
        throw new IllegalArgumentException(key + " must be true or false");
    }
    private static int number(CommentedConfig c, String key, int fallback) {
        Object v = raw(c, key);
        if (v == null) return fallback;
        if (v instanceof Number n && n.doubleValue() == n.intValue()) return n.intValue();
        throw new IllegalArgumentException(key + " must be an integer");
    }
    private static String string(CommentedConfig c, String key, String fallback) {
        Object v = raw(c, key);
        if (v == null) return fallback;
        if (v instanceof String s) return s;
        throw new IllegalArgumentException(key + " must be a string");
    }
    private static List<String> strings(CommentedConfig c, String key, List<String> fallback) {
        Object v = raw(c, key);
        if (v == null) return fallback;
        if (!(v instanceof List<?> list)) throw new IllegalArgumentException(key + " must be an array");
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String s)) throw new IllegalArgumentException(key + " contains non-string entry");
            result.add(s);
        }
        return List.copyOf(result);
    }
    private static List<Integer> ints(CommentedConfig c, String key, List<Integer> fallback) {
        Object v = raw(c, key);
        if (v == null) return fallback;
        if (!(v instanceof List<?> list)) throw new IllegalArgumentException(key + " must be an array");
        List<Integer> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Number n) || n.doubleValue() != n.intValue())
                throw new IllegalArgumentException(key + " contains a non-integer entry");
            result.add(n.intValue());
        }
        return List.copyOf(result);
    }
    private void validate() {
        check(Set.of("en_us", "pt_br").contains(language), "language.code must be en_us or pt_br");
        check(automaticIntervalMinutes >= 1 && automaticIntervalMinutes <= 1440, "automatic.intervalMinutes must be 1..1440");
        check(minimumItemAgeSeconds >= 0 && minimumItemAgeSeconds <= 86400, "items.minimumAgeSeconds out of range");
        check(minimumDistanceChunks >= 1 && minimumDistanceChunks <= 128, "mobs.minimumDistanceChunks must be 1..128");
        check(vehicleMinimumDistanceChunks >= 1 && vehicleMinimumDistanceChunks <= 128, "vehicles.minimumDistanceChunks must be 1..128");
        check(overloadMinimumTotalMobs >= 2 && overloadMinimumTotalMobs <= 1000000, "mobs.chunkOverload.minimumTotalMobs invalid");
        check(overloadMaxPerType >= 1 && overloadMaxPerType < overloadMinimumTotalMobs, "mobs.chunkOverload.maxPerType invalid");
        check(overloadScanIntervalSeconds >= 1 && overloadCooldownSeconds >= 1, "mobs.chunkOverload timers invalid");
        check(emergencyGlobalItemThreshold > 0 && emergencyChunkItemThreshold > 0, "emergency item thresholds invalid");
        check(emergencyScanIntervalSeconds >= 1 && emergencyCooldownSeconds >= 1, "emergency timers invalid");
        check(tabRefreshIntervalMs >= 250 && tabRefreshIntervalMs <= 60000,
            "integrations.tab.refreshIntervalMs must be 250..60000");
        check(maximumRemovalsPerTick >= 1 && maximumRemovalsPerTick <= 10000, "performance.maxRemovalsPerTick must be 1..10000");
        check(adminOpLevel >= 0 && adminOpLevel <= 4 && statusOpLevel >= 0 && statusOpLevel <= 4, "permission level must be 0..4");
        check(warningMinutes.stream().allMatch(x -> x >= 1 && x <= 1440), "automatic.warningMinutes invalid");
        check(warningSeconds.stream().allMatch(x -> x >= 1 && x <= 3599), "automatic.warningSeconds invalid");
        for (String id : protectedItemIds) check(id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"), "invalid item id: " + id);
        for (String id : removableMobIds) check(id.matches("minecraft:[a-z0-9_./-]+"), "only vanilla mobs allowed: " + id);
        for (String id : protectedVehicleIds) check(id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"), "invalid vehicle id: " + id);
    }
    private static void check(boolean ok, String explanation) {
        if (!ok) throw new IllegalArgumentException(explanation);
    }
}
