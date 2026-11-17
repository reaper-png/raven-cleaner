import fun.rederaven.cleaner.server.WarningPolicy;
import java.util.List;
public class WarningPolicyTest {
    public static void main(String[] args) {
        var minutes = List.of(5, 3, 2, 1);
        var seconds = List.of(30, 10, 5, 4, 3, 2, 1);
        int[] expected = {300, 180, 120, 60, 30, 10, 5, 4, 3, 2, 1};
        for (int remaining = 600; remaining >= 0; remaining--) {
            boolean should = WarningPolicy.shouldAnnounce(remaining, minutes, seconds);
            boolean expectedThis = false;
            for (int x : expected) if (x == remaining) expectedThis = true;
            if (should != expectedThis) throw new AssertionError("Unexpected warning for: " + remaining);
        }
        if (!WarningPolicy.countdownLabel(60).equals("1 minuto")) throw new AssertionError();
        if (!WarningPolicy.countdownLabel(1).equals("1 segundo")) throw new AssertionError();
        System.out.println("11 avisos e singular/plural validados.");
    }
}
