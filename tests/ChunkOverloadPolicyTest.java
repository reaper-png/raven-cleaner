import fun.rederaven.cleaner.server.ChunkOverloadPolicy;
import java.util.Map;
import java.util.Set;

public final class ChunkOverloadPolicyTest {
    public static void main(String[] args) {
        assertEquals(Set.of("minecraft:witch"), ChunkOverloadPolicy.excessiveTypes(
            50, Map.of("minecraft:zombie", 10, "minecraft:witch", 30, "minecraft:skeleton", 10), 50, 20));
        assertEquals(Set.of(), ChunkOverloadPolicy.excessiveTypes(
            49, Map.of("minecraft:witch", 30, "minecraft:zombie", 19), 50, 20));
        assertEquals(Set.of(), ChunkOverloadPolicy.excessiveTypes(
            50, Map.of("minecraft:witch", 20, "minecraft:zombie", 20, "minecraft:skeleton", 10), 50, 20));
        assertEquals(Set.of("minecraft:zombie", "minecraft:witch"), ChunkOverloadPolicy.excessiveTypes(
            60, Map.of("minecraft:witch", 30, "minecraft:zombie", 30), 50, 20));
        System.out.println("Chunk overload policy tests PASSED");
    }
    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + " got " + actual);
    }
}
