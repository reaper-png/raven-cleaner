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
import java.util.HashMap;
import java.util.Map;

/** Server-side language files. Reload is atomic with the main configuration. */
public final class CleanerLanguage {
    private final Map<String, String> values;
    private CleanerLanguage(Map<String, String> values) { this.values = Map.copyOf(values); }

    public String get(String key) {
        return values.getOrDefault(key, key);
    }

    public String duration(int remainingSeconds) {
        int count = remainingSeconds >= 60 && remainingSeconds % 60 == 0 ? remainingSeconds / 60 : remainingSeconds;
        boolean minutes = remainingSeconds >= 60 && remainingSeconds % 60 == 0;
        return count + " " + get(minutes ? (count == 1 ? "minute" : "minutes") : (count == 1 ? "second" : "seconds"));
    }

    public static Path languageFolder(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("serverconfig").resolve("ravencleaner").resolve("lang");
    }

    public static CleanerLanguage load(MinecraftServer server, String locale) throws IOException {
        Path folder = languageFolder(server);
        Files.createDirectories(folder);
        for (String fileName : new String[]{"en_us.toml", "pt_br.toml"}) {
            Path file = folder.resolve(fileName);
            if (Files.notExists(file)) {
                try (InputStream in = CleanerLanguage.class.getResourceAsStream("/lang/" + fileName)) {
                    if (in == null) throw new IOException("Bundled language file missing: " + fileName);
                    Files.copy(in, file);
                }
            }
        }
        // Bundled strings are fallbacks for existing servers whose language TOML
        // predates a new update. Local overrides always take priority.
        Map<String, String> values = readBundled("en_us.toml");
        values.putAll(read(folder.resolve("en_us.toml")));
        if (!locale.equals("en_us")) {
            values.putAll(readBundled(locale + ".toml"));
            values.putAll(read(folder.resolve(locale + ".toml")));
        }
        for (String key : new String[]{"prefix", "countdown", "countdownFinal", "startedAutomatic",
            "startedManual", "startedEmergency", "startedOverload", "finished", "failed", "status",
            "analysis", "reloaded", "reloadError", "paused", "resumed", "alreadyRunning",
            "modeManual", "modeAutomatic", "modeEmergency", "modeOverload", "minute", "minutes", "second", "seconds"}) {
            if (!values.containsKey(key)) throw new IOException("Missing language key: " + key);
        }
        return new CleanerLanguage(values);
    }

    private static Map<String, String> readBundled(String name) throws IOException {
        try (InputStream in = CleanerLanguage.class.getResourceAsStream("/lang/" + name)) {
            if (in == null) throw new IOException("Bundled language missing: " + name);
            try (Reader reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parse(reader, "bundled " + name);
            }
        }
    }

    private static Map<String, String> read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader, file.toString());
        }
    }

    private static Map<String, String> parse(Reader reader, String origin) throws IOException {
        try {
            CommentedConfig parsed = new TomlParser().parse(reader);
            Map<String, String> out = new HashMap<>();
            for (Map.Entry<String, Object> entry : parsed.valueMap().entrySet()) {
                if (!(entry.getValue() instanceof String text))
                    throw new IOException("Expected string for language key " + entry.getKey() + " in " + origin);
                out.put(entry.getKey(), text);
            }
            return out;
        } catch (RuntimeException ex) {
            throw new IOException("Invalid language TOML: " + origin + ": " + ex.getMessage(), ex);
        }
    }
}
