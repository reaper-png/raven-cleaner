package fun.rederaven.cleaner.ftb;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Read-only FTB Chunks integration.
 * This uses reflection solely to avoid tying the build to a specific FTB Maven artifact.
 * The mod metadata REQUIRES FTB Chunks on the server; errors fail closed (protect all chunks).
 * Per-sweep cache avoids a reflective lookup for every ItemEntity.
 */
public final class FtbClaimGuard {
    private final Map<ServerLevel, Map<Long, Boolean>> cache = new HashMap<>();
    private final Object manager;
    private final Constructor<?> positionConstructor;
    private final Method getChunk;
    private Throwable error;

    public FtbClaimGuard() {
        Object resolvedManager = null;
        Constructor<?> resolvedConstructor = null;
        Method resolvedGetChunk = null;
        Throwable failure = null;
        try {
            Class<?> apiClass = Class.forName("dev.ftb.mods.ftbchunks.api.FTBChunksAPI");
            Object api = apiClass.getMethod("api").invoke(null);
            resolvedManager = api.getClass().getMethod("getManager").invoke(api);
            Class<?> chunkDimPos = Class.forName("dev.ftb.mods.ftblibrary.math.ChunkDimPos");
            for (Constructor<?> constructor : chunkDimPos.getConstructors()) {
                Class<?>[] types = constructor.getParameterTypes();
                if (types.length == 2 && types[0].isAssignableFrom(ServerLevel.class)
                    && types[1] == BlockPos.class) {
                    resolvedConstructor = constructor;
                    break;
                }
            }
            if (resolvedConstructor == null) {
                throw new NoSuchMethodException("ChunkDimPos(Level, BlockPos) not found");
            }
            resolvedGetChunk = resolvedManager.getClass().getMethod("getChunk", chunkDimPos);
        } catch (Throwable ex) {
            failure = ex;
        }
        this.manager = resolvedManager;
        this.positionConstructor = resolvedConstructor;
        this.getChunk = resolvedGetChunk;
        this.error = failure;
    }

    public boolean operational() { return error == null; }
    public String errorMessage() { return error == null ? "" : error.getClass().getSimpleName() + ": " + error.getMessage(); }

    /** true = protected chunk; an API failure always counts as protected. */
    public boolean isProtected(ServerLevel level, BlockPos blockPos) {
        ChunkPos cp = new ChunkPos(blockPos);
        long key = ChunkPos.asLong(cp.x, cp.z);
        return cache.computeIfAbsent(level, ignored -> new HashMap<>())
            .computeIfAbsent(key, ignored -> findClaim(level, cp));
    }

    private boolean findClaim(ServerLevel level, ChunkPos cp) {
        if (!operational()) return true;
        try {
            BlockPos pos = new BlockPos(cp.getMinBlockX(), 64, cp.getMinBlockZ());
            Object chunkPos = positionConstructor.newInstance(level, pos);
            return getChunk.invoke(manager, chunkPos) != null;
        } catch (Throwable exception) {
            // Fail CLOSED globally: future checks and the current cleanup are aborted.
            error = exception;
            return true;
        }
    }
}
