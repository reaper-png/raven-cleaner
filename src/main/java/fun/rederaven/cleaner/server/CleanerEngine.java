package fun.rederaven.cleaner.server;

import com.mojang.brigadier.context.CommandContext;
import fun.rederaven.cleaner.chat.ColoredMessage;
import fun.rederaven.cleaner.config.CleanerLanguage;
import fun.rederaven.cleaner.config.CleanerSettings;
import fun.rederaven.cleaner.ftb.FtbClaimGuard;
import fun.rederaven.cleaner.integration.CleanerTabSnapshot;
import fun.rederaven.cleaner.integration.TabIntegration;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-only cleanup engine. Never forces chunk loads, never removes protected
 * vanilla mobs or entities belonging to other mods. Removal is bounded per tick.
 */
public final class CleanerEngine {
    private static final Logger LOG = LoggerFactory.getLogger("RavenCleaner");
    /** An allow-list that cannot be expanded from a configuration file. */
    private static final Set<String> SAFE_HOSTILES = Set.of(
        "minecraft:zombie", "minecraft:husk", "minecraft:drowned", "minecraft:skeleton",
        "minecraft:stray", "minecraft:bogged", "minecraft:spider", "minecraft:cave_spider",
        "minecraft:creeper", "minecraft:slime", "minecraft:magma_cube", "minecraft:phantom",
        "minecraft:silverfish", "minecraft:endermite", "minecraft:witch"
    );

    private CleanerSettings settings = new CleanerSettings();
    private CleanerLanguage language;
    private MinecraftServer runningServer;
    private long nextAutoTick;
    private long lastEmergencyTick = Long.MIN_VALUE / 2;
    private long lastEmergencyCheckTick;
    private long lastOverloadTick = Long.MIN_VALUE / 2;
    private long lastOverloadScanTick;
    private boolean automaticPaused;
    private Job activeJob;
    private final Set<Integer> announcedSeconds = new HashSet<>();
    private final TabIntegration tabIntegration = new TabIntegration(LOG::info, LOG::warn);
    // Counts from the last COMPLETED cleanup. In-memory only, reset on server restart.
    private int lastDrops;
    private int lastUnits;
    private int lastMobs;
    private int lastVehicles;


    private enum Kind { ITEM, MOB, OVERLOAD_MOB, VEHICLE }
    private record Target(ServerLevel level, Entity entity, Kind kind) {}
    private static final class Job {
        final String mode;
        final boolean ignoreAge;
        final List<Target> targets;
        final long startedAt = System.nanoTime();
        int index;
        int drops;
        int units;
        int mobs;
        int vehicles;
        Job(String mode, boolean ignoreAge, List<Target> targets) {
            this.mode = mode;
            this.ignoreAge = ignoreAge;
            this.targets = targets;
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        runningServer = event.getServer();
        try {
            CleanerSettings fresh = CleanerSettings.load(runningServer);
            CleanerLanguage freshLanguage = CleanerLanguage.load(runningServer, fresh.language);
            settings = fresh;
            language = freshLanguage;
        } catch (IOException e) {
            LOG.error("Invalid Raven Cleaner configuration. Cleaning disabled until /cleaner reload.", e);
            settings = new CleanerSettings();
            settings.enabled = false;
        }
        activeJob = null;
        lastDrops = lastUnits = lastMobs = lastVehicles = 0;
        automaticPaused = false;
        lastEmergencyTick = Long.MIN_VALUE / 2;
        lastEmergencyCheckTick = runningServer.getTickCount();
        lastOverloadTick = Long.MIN_VALUE / 2;
        lastOverloadScanTick = runningServer.getTickCount();
        resetAutoTimer(runningServer);
        publishTabSnapshot();
        LOG.info("Raven Cleaner server config: {}", CleanerSettings.path(runningServer));
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        tabIntegration.shutdown();
        runningServer = null;
        activeJob = null;
        announcedSeconds.clear();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        // English commands only; language files translate text, not command identifiers.
        var dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("cleaner")
            .then(Commands.literal("status").requires(src -> src.hasPermission(settings.statusOpLevel)).executes(this::status))
            .then(Commands.literal("analyze").requires(src -> src.hasPermission(settings.adminOpLevel)).executes(this::analyze))
            .then(Commands.literal("clean").requires(src -> src.hasPermission(settings.adminOpLevel))
                .then(Commands.literal("items").executes(ctx -> beginManual(ctx, true, false, false)))
                .then(Commands.literal("mobs").executes(ctx -> beginManual(ctx, false, true, false)))
                .then(Commands.literal("vehicles").executes(ctx -> beginManual(ctx, false, false, true)))
                .then(Commands.literal("all").executes(ctx -> beginManual(ctx, true, true, true))))
            .then(Commands.literal("reload").requires(src -> src.hasPermission(settings.adminOpLevel)).executes(this::reload))
            .then(Commands.literal("pause").requires(src -> src.hasPermission(settings.adminOpLevel)).executes(this::pause))
            .then(Commands.literal("resume").requires(src -> src.hasPermission(settings.adminOpLevel)).executes(this::resume))
);
    }

    private int beginManual(CommandContext<CommandSourceStack> ctx, boolean requestedItems, boolean requestedMobs, boolean requestedVehicles) {
        boolean items = requestedItems && settings.manualItems && settings.itemsEnabled;
        boolean mobs = requestedMobs && settings.manualMobs && settings.mobsEnabled;
        boolean vehicles = requestedVehicles && settings.manualVehicles && settings.vehiclesEnabled;
        if (!settings.enabled || !settings.manualEnabled || (!items && !mobs && !vehicles)
            || !startJob(ctx.getSource().getServer(), "modeManual", items, mobs, vehicles,
                mobs && settings.overloadEnabled, items && settings.manualIgnoreItemAge)) {
            reply(ctx.getSource(), "alreadyRunning", Map.of());
            return 0;
        }
        return 1;
    }

    private int status(CommandContext<CommandSourceStack> ctx) {
        long remain = Math.max(0, (nextAutoTick - ctx.getSource().getServer().getTickCount() + 19) / 20);
        String mode = !settings.enabled ? "OFF" : settings.automaticEnabled && !automaticPaused ? languageValue("modeAutomatic") : languageValue("modeManual");
        reply(ctx.getSource(), "status", Map.of("next_seconds", remain, "time", timeLabel((int)Math.min(Integer.MAX_VALUE, remain)), "mode", mode));
        return 1;
    }

    private int analyze(CommandContext<CommandSourceStack> ctx) {
        List<Target> targets = collect(ctx.getSource().getServer(), true, true, true, settings.overloadEnabled, false);
        int items = 0, mobs = 0, vehicles = 0;
        for (Target t : targets) {
            switch (t.kind) {
                case ITEM -> items++;
                case MOB, OVERLOAD_MOB -> mobs++;
                case VEHICLE -> vehicles++;
            }
        }
        reply(ctx.getSource(), "analysis", Map.of("items", items, "mobs", mobs, "vehicles", vehicles));
        return items + mobs + vehicles;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        try {
            CleanerSettings fresh = CleanerSettings.load(ctx.getSource().getServer());
            CleanerLanguage freshLanguage = CleanerLanguage.load(ctx.getSource().getServer(), fresh.language);
            settings = fresh;
            language = freshLanguage;
            resetAutoTimer(ctx.getSource().getServer());
            publishTabSnapshot();
            tabIntegration.refresh(settings.tabEnabled, settings.tabRefreshIntervalMs,
                settings.tabEnableStatus, settings.tabEnableCountdown, settings.tabEnableStatistics);
            reply(ctx.getSource(), "reloaded", Map.of());
            return 1;
        } catch (IOException e) {
            reply(ctx.getSource(), "reloadError", Map.of("error", e.getMessage()));
            return 0;
        }
    }

    private int pause(CommandContext<CommandSourceStack> ctx) {
        automaticPaused = true;
        publishTabSnapshot();
        reply(ctx.getSource(), "paused", Map.of());
        return 1;
    }
    private int resume(CommandContext<CommandSourceStack> ctx) {
        automaticPaused = false;
        resetAutoTimer(ctx.getSource().getServer());
        publishTabSnapshot();
        reply(ctx.getSource(), "resumed", Map.of());
        return 1;
    }
    private void resetAutoTimer(MinecraftServer server) {
        announcedSeconds.clear();
        nextAutoTick = server.getTickCount() + settings.automaticIntervalMinutes * 1200L;
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (runningServer != server) return;
        long tick = server.getTickCount();
        if (tick % 20L == 0) {
            publishTabSnapshot();
            tabIntegration.refresh(settings.tabEnabled, settings.tabRefreshIntervalMs,
                settings.tabEnableStatus, settings.tabEnableCountdown, settings.tabEnableStatistics);
        }
        if (!settings.enabled) return;
        if (activeJob != null) processJob();
        if (tick % 20L == 0 && settings.automaticEnabled && !automaticPaused) {
            int remaining = (int)Math.max(0, (nextAutoTick - tick + 19L) / 20L);
            if (WarningPolicy.shouldAnnounce(remaining, settings.warningMinutes, settings.warningSeconds)
                && announcedSeconds.add(remaining)) {
                broadcast(remaining <= 5 ? "countdownFinal" : "countdown",
                    Map.of("seconds", remaining, "time", timeLabel(remaining)));
            }
            if (tick >= nextAutoTick) {
                if (activeJob == null) {
                    startJob(server, "modeAutomatic", settings.autoCleanItems && settings.itemsEnabled,
                        settings.autoCleanMobs && settings.mobsEnabled,
                        settings.autoCleanVehicles && settings.vehiclesEnabled, false, false);
                }
                resetAutoTimer(server);
            }
        }
        if (activeJob != null) return;
        if (settings.emergencyEnabled && settings.itemsEnabled
            && tick - lastEmergencyCheckTick >= settings.emergencyScanIntervalSeconds * 20L
            && tick - lastEmergencyTick >= settings.emergencyCooldownSeconds * 20L) {
            lastEmergencyCheckTick = tick;
            if (detectItemEmergency(server) && startJob(server, "modeEmergency", true, false, false,
                false, settings.emergencyIgnoreItemAge)) {
                lastEmergencyTick = tick;
                resetAutoTimer(server);
                return;
            }
        }
        if (settings.mobsEnabled && settings.overloadEnabled
            && tick - lastOverloadScanTick >= settings.overloadScanIntervalSeconds * 20L
            && tick - lastOverloadTick >= settings.overloadCooldownSeconds * 20L) {
            lastOverloadScanTick = tick;
            List<Target> overload = collectOverload(server);
            if (!overload.isEmpty() && beginJob("modeOverload", false, overload)) {
                lastOverloadTick = tick;
            }
        }
    }

    /** Item emergencies count loaded item ENTITIES, including claimed and player chunks. */
    private boolean detectItemEmergency(MinecraftServer server) {
        int total = 0;
        Map<ServerLevel, Map<Long, Integer>> counts = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                if (!(entity instanceof ItemEntity item) || !eligibleItem(item, settings.emergencyIgnoreItemAge)) continue;
                total++;
                ChunkPos pos = item.chunkPosition();
                int perChunk = counts.computeIfAbsent(level, ignored -> new HashMap<>())
                    .merge(ChunkPos.asLong(pos.x, pos.z), 1, Integer::sum);
                if (total >= settings.emergencyGlobalItemThreshold || perChunk >= settings.emergencyChunkItemThreshold) return true;
            }
        }
        return false;
    }

    private boolean startJob(MinecraftServer server, String mode, boolean items, boolean mobs,
                             boolean vehicles, boolean overload, boolean ignoreAge) {
        if (activeJob != null || !settings.enabled || (!items && !mobs && !vehicles && !overload)) return false;
        List<Target> targets = collect(server, items, mobs, vehicles, overload, ignoreAge);
        return beginJob(mode, ignoreAge, targets);
    }

    private boolean beginJob(String mode, boolean ignoreAge, List<Target> targets) {
        if (activeJob != null || !settings.enabled) return false;
        activeJob = new Job(mode, ignoreAge, targets);
        String started = switch (mode) {
            case "modeManual" -> "startedManual";
            case "modeEmergency" -> "startedEmergency";
            case "modeOverload" -> "startedOverload";
            default -> "startedAutomatic";
        };
        broadcast(started, Map.of("mode", languageValue(mode)));
        publishTabSnapshot();
        LOG.info("Raven Cleaner {}: {} selected entities", mode, targets.size());
        return true;
    }

    /** FTB failure skips protected normal mobs/vehicles. Drops still work independently. */
    private List<Target> collect(MinecraftServer server, boolean items, boolean mobs,
                                  boolean vehicles, boolean overload, boolean ignoreAge) {
        FtbClaimGuard guard = (mobs || vehicles || (overload && !settings.overloadIncludeClaimedChunks))
            ? new FtbClaimGuard() : null;
        if (guard != null && !guard.operational()) {
            // Fail closed for normal mobs and vehicles; still allow unprotected
            // item cleanup and independently-safe overload cleanup.
            LOG.warn("FTB Chunks lookup unavailable: skipping distant mobs/vehicles, not drops");
        }
        Map<Entity, Target> selected = new LinkedHashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                if (entity.isRemoved()) continue;
                if (items && entity instanceof ItemEntity item && eligibleItem(item, ignoreAge)) {
                    selected.put(entity, new Target(level, entity, Kind.ITEM));
                } else if (mobs && guard != null && guard.operational()
                    && entity instanceof Mob mob && eligibleDistantMob(mob, guard)) {
                    selected.put(entity, new Target(level, entity, Kind.MOB));
                } else if (vehicles && guard != null && guard.operational()
                    && eligibleVehicle(entity, guard)) {
                    selected.put(entity, new Target(level, entity, Kind.VEHICLE));
                }
            }
        }
        if (overload) {
            for (Target target : collectOverload(server)) {
                // Emergency overload selection is more specific and overrides ordinary mob selection.
                selected.put(target.entity, target);
            }
        }
        return new ArrayList<>(selected.values());
    }

    private boolean eligibleItem(ItemEntity item, boolean ignoreAge) {
        if (!settings.itemsEnabled || item.isRemoved() || item.getItem().isEmpty()) return false;
        if (settings.protectedItemIds.contains(BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString())) return false;
        // Intentionally NO FTB claim or player-chunk check for drops.
        return ignoreAge || item.getAge() >= settings.minimumItemAgeSeconds * 20L;
    }

    private boolean eligibleMobBase(Mob mob) {
        if (!settings.mobsEnabled || mob.isRemoved()) return false;
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
        if (!SAFE_HOSTILES.contains(id) || !settings.removableMobIds.contains(id)
            || mob.getType().getCategory() != MobCategory.MONSTER) return false;
        // All of these are IMMUTABLE protections, including during emergency overloads.
        return mob.getCustomName() == null && !mob.isPersistenceRequired() && !mob.isLeashed()
            && !mob.isNoAi() && !mob.isPassenger() && !mob.isVehicle()
            && !mob.getPersistentData().getBoolean("RavenCleanerProtected")
            && !(mob instanceof TamableAnimal) && !(mob instanceof AbstractVillager);
    }

    private boolean eligibleDistantMob(Mob mob, FtbClaimGuard guard) {
        if (!eligibleMobBase(mob) || guard == null) return false;
        ServerLevel level = (ServerLevel)mob.level();
        return !guard.isProtected(level, mob.blockPosition()) && farFromAllPlayers(level, mob.chunkPosition(), settings.minimumDistanceChunks);
    }

    /** Regular vehicle cleaning NEVER touches claims, storage vehicles, occupied or named vehicles. */
    private boolean eligibleVehicle(Entity entity, FtbClaimGuard guard) {
        if (!settings.vehiclesEnabled || entity.isRemoved() || guard == null) return false;
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        boolean boat = entity instanceof Boat && settings.removeBoats
            && id.startsWith("minecraft:") && !id.contains("chest")
            && (id.endsWith("_boat") || id.endsWith("_raft"));
        boolean minecart = entity instanceof AbstractMinecart && settings.removeMinecarts
            && id.equals("minecraft:minecart");
        if (!boat && !minecart) return false;
        if (settings.protectedVehicleIds.contains(id) || entity.getCustomName() != null
            || entity.isVehicle() || entity.isPassenger()
            || entity.getPersistentData().getBoolean("RavenCleanerProtected")) return false;
        ServerLevel level = (ServerLevel)entity.level();
        return !guard.isProtected(level, entity.blockPosition()) && farFromAllPlayers(level, entity.chunkPosition(), settings.vehicleMinimumDistanceChunks);
    }

    private boolean farFromAllPlayers(ServerLevel level, ChunkPos position, int minDistance) {
        boolean foundPlayer = false;
        for (ServerPlayer player : level.players()) {
            foundPlayer = true;
            ChunkPos other = player.chunkPosition();
            if (Math.max(Math.abs(other.x - position.x), Math.abs(other.z - position.z)) < minDistance)
                return false;
        }
        // If no players are in a dimension, be conservative and do not delete persistent vehicles/mobs.
        return foundPlayer;
    }

    /**
     * Per-chunk rule: total Mob count >= 50 AND a single exact entity type count > 20.
     * Remove ALL ELIGIBLE members of that type, not only the ones above 20.
     * Count all mobs for the threshold (including villagers, etc), but NEVER remove protected mobs.
     */
    private List<Target> collectOverload(MinecraftServer server) {
        if (!settings.overloadEnabled || !settings.mobsEnabled) return List.of();
        Map<ServerLevel, Map<Long, List<Mob>>> chunks = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            Map<Long, List<Mob>> inLevel = chunks.computeIfAbsent(level, x -> new HashMap<>());
            for (Entity entity : level.getEntities().getAll()) {
                if (entity instanceof Mob mob && !mob.isRemoved()) {
                    ChunkPos p = mob.chunkPosition();
                    inLevel.computeIfAbsent(ChunkPos.asLong(p.x, p.z), x -> new ArrayList<>()).add(mob);
                }
            }
        }
        List<Target> result = new ArrayList<>();
        FtbClaimGuard guard = settings.overloadIncludeClaimedChunks ? null : new FtbClaimGuard();
        if (guard != null && !guard.operational()) return List.of();
        for (var levelEntry : chunks.entrySet()) {
            ServerLevel level = levelEntry.getKey();
            for (var entry : levelEntry.getValue().entrySet()) {
                List<Mob> all = entry.getValue();
                if (all.size() < settings.overloadMinimumTotalMobs) continue;
                ChunkPos cp = new ChunkPos(ChunkPos.getX(entry.getKey()), ChunkPos.getZ(entry.getKey()));
                if (!settings.overloadIncludePlayerChunks && !farFromAllPlayers(level, cp, 1)) continue;
                if (guard != null && guard.isProtected(level, all.get(0).blockPosition())) continue;
                Map<String, List<Mob>> byType = new HashMap<>();
                for (Mob mob : all) {
                    String type = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
                    byType.computeIfAbsent(type, x -> new ArrayList<>()).add(mob);
                }
                Map<String, Integer> counts = new HashMap<>();
                byType.forEach((id, group) -> counts.put(id, group.size()));
                Set<String> excessiveTypes = ChunkOverloadPolicy.excessiveTypes(
                    all.size(), counts, settings.overloadMinimumTotalMobs, settings.overloadMaxPerType);
                for (String id : excessiveTypes) {
                    for (Mob mob : byType.get(id)) {
                        if (eligibleMobBase(mob)) result.add(new Target(level, mob, Kind.OVERLOAD_MOB));
                    }
                }
            }
        }
        if (guard != null && !guard.operational()) return List.of();
        return result;
    }

    private void processJob() {
        Job job = activeJob;
        if (job == null) return;
        // Check claims again at removal time to prevent stale validation between ticks.
        FtbClaimGuard guard = null;
        int removed = 0;
        int inspected = 0;
        while (job.index < job.targets.size() && removed < settings.maximumRemovalsPerTick
            && inspected < settings.maximumRemovalsPerTick * 2) {
            Target target = job.targets.get(job.index++);
            inspected++;
            Entity entity = target.entity;
            if (entity.isRemoved() || entity.level() != target.level) continue;
            if (target.kind == Kind.ITEM && entity instanceof ItemEntity item && eligibleItem(item, job.ignoreAge)) {
                job.drops++;
                job.units += item.getItem().getCount();
                entity.discard();
                removed++;
            } else if (target.kind == Kind.OVERLOAD_MOB && entity instanceof Mob mob
                && settings.overloadEnabled && eligibleMobBase(mob)) {
                // Chunk/group threshold was established at the start of this job.
                // All immutable per-entity safety checks are repeated here.
                if (settings.overloadIncludeClaimedChunks) {
                    entity.discard();
                    job.mobs++;
                    removed++;
                } else {
                    if (guard == null) guard = new FtbClaimGuard();
                    if (!guard.operational()) { failCurrentJob(guard.errorMessage()); return; }
                    if (!guard.isProtected(target.level, entity.blockPosition())) {
                        entity.discard(); job.mobs++; removed++;
                    }
                }
            } else if (target.kind == Kind.MOB && entity instanceof Mob mob) {
                if (guard == null) guard = new FtbClaimGuard();
                if (!guard.operational()) { failCurrentJob(guard.errorMessage()); return; }
                if (eligibleDistantMob(mob, guard)) { entity.discard(); job.mobs++; removed++; }
            } else if (target.kind == Kind.VEHICLE) {
                if (guard == null) guard = new FtbClaimGuard();
                if (!guard.operational()) { failCurrentJob(guard.errorMessage()); return; }
                if (eligibleVehicle(entity, guard)) { entity.discard(); job.vehicles++; removed++; }
            }
            if (guard != null && !guard.operational()) { failCurrentJob(guard.errorMessage()); return; }
        }
        if (job.index >= job.targets.size()) {
            long elapsed = (System.nanoTime() - job.startedAt) / 1_000_000L;
            broadcast("finished", Map.of("mode", languageValue(job.mode), "removed_items", job.drops,
                "removed_units", job.units, "removed_mobs", job.mobs, "removed_vehicles", job.vehicles,
                "removed_total", job.drops + job.mobs + job.vehicles, "elapsed_ms", elapsed));
            lastDrops = job.drops;
            lastUnits = job.units;
            lastMobs = job.mobs;
            lastVehicles = job.vehicles;
            LOG.info("Raven Cleaner {} finished: {} drops, {} units, {} mobs, {} vehicles in {}ms",
                job.mode, job.drops, job.units, job.mobs, job.vehicles, elapsed);
            activeJob = null;
            publishTabSnapshot();
        }
    }

    private void failCurrentJob(String error) {
        LOG.error("Raven Cleaner aborted because claim verification failed: {}", error);
        broadcast("failed", Map.of("error", error));
        activeJob = null;
        publishTabSnapshot();
    }

    /** All world reads run on the server tick thread; TAB reads an immutable volatile snapshot. */
    private void publishTabSnapshot() {
        if (runningServer == null) return;
        boolean automaticRunning = settings.enabled && settings.automaticEnabled && !automaticPaused;
        long remaining = automaticRunning
            ? Math.max(0L, (nextAutoTick - runningServer.getTickCount() + 19L) / 20L)
            : -1L;
        tabIntegration.publish(CleanerTabSnapshot.build(
            settings.enabled, settings.automaticEnabled, automaticPaused, activeJob != null,
            remaining, lastDrops, lastUnits, lastMobs, lastVehicles,
            languageValue("tabStatusActive"), languageValue("tabStatusPaused"),
            languageValue("tabStatusRunning"), languageValue("tabStatusDisabled")));
    }

    private String languageValue(String key) { return language == null ? key : language.get(key); }
    private String timeLabel(int seconds) { return language == null ? seconds + " seconds" : language.duration(seconds); }

    private Map<String, Object> vars(Map<String, ?> data) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("prefix", languageValue("prefix"));
        vars.putAll(data);
        return vars;
    }
    private void reply(CommandSourceStack source, String messageKey, Map<String, ?> values) {
        source.sendSuccess(() -> ColoredMessage.render(languageValue(messageKey), vars(values)), false);
    }
    private void broadcast(String key, Map<String, ?> values) {
        if (runningServer != null && settings.messagesEnabled)
            runningServer.getPlayerList().broadcastSystemMessage(
                ColoredMessage.render(languageValue(key), vars(values)), false);
    }
}
