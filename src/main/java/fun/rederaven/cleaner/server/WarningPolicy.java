package fun.rederaven.cleaner.server;

import java.util.List;

/** Pure policy, independently testable without running Minecraft. */
public final class WarningPolicy {
    private WarningPolicy() {}

    public static boolean shouldAnnounce(int remainingSeconds, List<Integer> minutes, List<Integer> seconds) {
        if (remainingSeconds <= 0) return false;
        return (remainingSeconds % 60 == 0 && minutes.contains(remainingSeconds / 60))
            || seconds.contains(remainingSeconds);
    }

    public static String countdownLabel(int remainingSeconds) {
        if (remainingSeconds >= 60 && remainingSeconds % 60 == 0) {
            int m = remainingSeconds / 60;
            return m + (m == 1 ? " minuto" : " minutos");
        }
        return remainingSeconds + (remainingSeconds == 1 ? " segundo" : " segundos");
    }
}
