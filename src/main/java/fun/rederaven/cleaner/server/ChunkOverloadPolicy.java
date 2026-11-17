package fun.rederaven.cleaner.server;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Pure chunk overload rule, independently testable without Minecraft. */
public final class ChunkOverloadPolicy {
    private ChunkOverloadPolicy() {}

    public static Set<String> excessiveTypes(int totalMobCount, Map<String, Integer> counts,
                                              int minimumTotalMobs, int maxPerType) {
        if (totalMobCount < minimumTotalMobs) return Set.of();
        Set<String> selected = new HashSet<>();
        counts.forEach((type, count) -> {
            if (count != null && count > maxPerType) selected.add(type);
        });
        return Set.copyOf(selected);
    }
}
