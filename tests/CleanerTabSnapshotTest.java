import fun.rederaven.cleaner.integration.CleanerTabSnapshot;
import java.util.Map;
/** Standalone test: javac integration/CleanerTabSnapshot.java tests/CleanerTabSnapshotTest.java */
public class CleanerTabSnapshotTest {
    private static void eq(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    public static void main(String[] args) {
        Map<String,String> active = CleanerTabSnapshot.build(true, true, false, false, 272,
            10, 64, 4, 1, "&aActive", "&ePaused", "&bCleaning", "&cDisabled");
        eq(active.get("%ravencleaner_next_cleanup%"), "04:32");
        eq(active.get("%ravencleaner_next_seconds%"), "272");
        eq(active.get("%ravencleaner_status%"), "&aActive");
        eq(active.get("%ravencleaner_running%"), "false");
        eq(active.get("%ravencleaner_last_total%"), "15");
        eq(CleanerTabSnapshot.build(true,true,false,true,3723,0,0,0,0,"A","P","R","D")
            .get("%ravencleaner_next_cleanup%"), "01:02:03");
        eq(CleanerTabSnapshot.build(true,true,true,false,-1,0,0,0,0,"A","P","R","D")
            .get("%ravencleaner_status%"), "P");
        eq(CleanerTabSnapshot.build(true,true,true,false,-1,0,0,0,0,"A","P","R","D")
            .get("%ravencleaner_next_seconds%"), "-1");
        eq(CleanerTabSnapshot.build(false,false,false,false,-1,0,0,0,0,"A","P","R","D")
            .get("%ravencleaner_status%"), "D");
        System.out.println("PASS: TAB snapshot / countdown / pause / disabled / counters");
    }
}
