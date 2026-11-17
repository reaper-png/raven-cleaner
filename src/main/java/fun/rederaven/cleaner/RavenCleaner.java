package fun.rederaven.cleaner;

import fun.rederaven.cleaner.server.CleanerEngine;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/** Server-only, no client classes or custom registries. */
@Mod(RavenCleaner.MOD_ID)
public final class RavenCleaner {
    public static final String MOD_ID = "ravencleaner";

    public RavenCleaner() {
        NeoForge.EVENT_BUS.register(new CleanerEngine());
    }
}
