package fun.rederaven.cleaner.integration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable data exported to TAB. No Minecraft objects ever enter the Supplier;
 * values are prepared on the Minecraft server thread and safely published.
 */
public final class CleanerTabSnapshot {
    private CleanerTabSnapshot() {}

    public static Map<String, String> build(
            boolean enabled, boolean automaticEnabled, boolean paused, boolean cleaning,
            long secondsUntilCleanup, int lastDrops, int lastUnits, int lastMobs,
            int lastVehicles, String activeText, String pausedText,
            String runningText, String disabledText) {
        String status = !enabled ? disabledText : cleaning ? runningText
            : automaticEnabled && paused ? pausedText : activeText;
        boolean scheduled = enabled && automaticEnabled && !paused && secondsUntilCleanup >= 0;
        String remaining = scheduled ? duration(secondsUntilCleanup) : "--:--";
        Map<String, String> values = new LinkedHashMap<>();
        values.put("%ravencleaner_status%", status);
        values.put("%ravencleaner_running%", Boolean.toString(cleaning));
        values.put("%ravencleaner_next_cleanup%", remaining);
        values.put("%ravencleaner_next_seconds%", scheduled ? Long.toString(secondsUntilCleanup) : "-1");
        values.put("%ravencleaner_last_drops%", Integer.toString(lastDrops));
        values.put("%ravencleaner_last_units%", Integer.toString(lastUnits));
        values.put("%ravencleaner_last_mobs%", Integer.toString(lastMobs));
        values.put("%ravencleaner_last_vehicles%", Integer.toString(lastVehicles));
        values.put("%ravencleaner_last_total%", Long.toString((long) lastDrops + lastMobs + lastVehicles));
        return Map.copyOf(values);
    }

    static String duration(long seconds) {
        long h = seconds / 3600;
        long m = seconds / 60 % 60;
        long s = seconds % 60;
        return h > 0 ? "%02d:%02d:%02d".formatted(h, m, s)
            : "%02d:%02d".formatted(m, s);
    }
}
