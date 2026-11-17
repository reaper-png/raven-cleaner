package fun.rederaven.cleaner.integration;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Optional integration using TAB's public API at runtime.
 * Reflection intentionally avoids a hard dependency on the TAB jar: the mod
 * compiles and runs on servers without TAB, and ships no TAB classes of its
 * own.
 * Tested against API signatures in TAB NeoForge v5.5.0.
 */
public final class TabIntegration {
    private static final Set<String> STATUS = Set.of(
            "%ravencleaner_status%", "%ravencleaner_running%");
    private static final Set<String> COUNTDOWN = Set.of(
            "%ravencleaner_next_cleanup%", "%ravencleaner_next_seconds%");
    private static final Set<String> STATS = Set.of(
            "%ravencleaner_last_drops%", "%ravencleaner_last_units%",
            "%ravencleaner_last_mobs%", "%ravencleaner_last_vehicles%",
            "%ravencleaner_last_total%");

    private final Consumer<String> info;
    private final Consumer<String> warn;
    private final Map<String, Object> owned = new LinkedHashMap<>();
    private volatile Map<String, String> values = Map.of();
    private volatile boolean reloadRequested = true;
    private Object manager;
    private Object eventBus;
    private Object eventHandler;
    private int currentInterval = -1;
    private boolean missingLogged;
    private boolean registeredLogged;

    public TabIntegration(Consumer<String> info, Consumer<String> warn) {
        this.info = info;
        this.warn = warn;
    }

    /** Publish data only; does not invoke TAB or read world state. */
    public void publish(Map<String, String> snapshot) {
        values = Map.copyOf(snapshot);
    }

    public String value(String identifier) {
        return values.getOrDefault(identifier, "");
    }

    /** Call from the server tick (every second), also after /cleaner reload. */
    public void refresh(boolean enabled, int intervalMs,
            boolean enableStatus, boolean enableCountdown, boolean enableStats) {
        if (!enabled) {
            detach(false);
            return;
        }
        try {
            Class<?> apiType = Class.forName("me.neznamy.tab.api.TabAPI");
            Object api = apiType.getMethod("getInstance").invoke(null);
            if (api == null)
                return;
            Object nextManager = apiType.getMethod("getPlaceholderManager").invoke(api);
            Object nextBus = apiType.getMethod("getEventBus").invoke(api);
            if (nextManager == null || nextBus == null)
                return;
            missingLogged = false;

            if (manager != nextManager) {
                owned.clear(); // previous manager is gone; never unregister placeholders from the new one
                manager = nextManager;
                reloadRequested = true;
                currentInterval = -1;
            }
            if (eventBus != nextBus) {
                eventBus = nextBus;
                eventHandler = null;
                listenToTabReload(nextBus);
            }
            Set<String> wanted = new LinkedHashSet<>();
            if (enableStatus)
                wanted.addAll(STATUS);
            if (enableCountdown)
                wanted.addAll(COUNTDOWN);
            if (enableStats)
                wanted.addAll(STATS);

            // Never delete another mod's placeholders. Only remove our own instances.
            for (String name : Set.copyOf(owned.keySet())) {
                if (!wanted.contains(name) || currentInterval != intervalMs) {
                    unregisterOwned(name);
                }
            }
            currentInterval = intervalMs;

            for (String name : wanted) {
                Object current = find(name);
                Object ours = owned.get(name);

                // Placeholder already registered correctly.
                if (ours != null && ours == current) {
                    continue;
                }

                // Do not overwrite other registrations during normal operation.
                if (current != null && !reloadRequested) {
                    warn.accept("TAB placeholder conflict: " + name);
                    continue;
                }

                // During TAB reload, restore our placeholders.
                // TAB may have registered a fallback placeholder
                // while reloading its configuration.
                Supplier<String> supplier = () -> value(name);

                Object handle = manager.getClass()
                        .getMethod(
                                "registerServerPlaceholder",
                                String.class,
                                int.class,
                                Supplier.class)
                        .invoke(manager, name, intervalMs, supplier);

                owned.put(name, handle);
            }
            if (!registeredLogged && !owned.isEmpty()) {
                info.accept("Raven Cleaner registered " + owned.size() + " TAB placeholders.");
                registeredLogged = true;
            }
            reloadRequested = false;
        } catch (ClassNotFoundException ignored) {
            // TAB is not installed. No mandatory dependency, no user-facing error.
            if (!missingLogged)
                missingLogged = true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            // TAB may still be starting/reloading. Retry on the next server tick.
            if (!missingLogged) {
                warn.accept("TAB integration unavailable for now (will retry): " + rootMessage(ex));
                missingLogged = true;
            }
        }
    }

    private void listenToTabReload(Object bus) throws ReflectiveOperationException {
        Class<?> handlerClass = Class.forName("me.neznamy.tab.api.event.EventHandler");
        Class<?> loadClass = Class.forName("me.neznamy.tab.api.event.plugin.TabLoadEvent");
        InvocationHandler handle = (proxy, method, args) -> {
            if (method.getName().equals("handle")) {
                // Defer actual registration to next Minecraft server tick.
                reloadRequested = true;
                return null;
            }
            return switch (method.getName()) {
                case "toString" -> "RavenCleanerTabLoadListener";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null ? null : args[0]);
                default -> null;
            };
        };
        eventHandler = Proxy.newProxyInstance(handlerClass.getClassLoader(),
                new Class<?>[] { handlerClass }, handle);
        bus.getClass().getMethod("register", Class.class, handlerClass)
                .invoke(bus, loadClass, eventHandler);
    }

    private Object find(String name) throws ReflectiveOperationException {
        // TAB 5.5.0 getPlaceholder(String) has side effects: an unknown identifier
        // triggers PlaceholderRegisterEvent / unknown-placeholder creation.
        // Inspect the registered map instead (public on 5.5.0 implementation).
        Object registered = manager.getClass().getMethod("getRegisteredPlaceholders")
                .invoke(manager);
        if (!(registered instanceof Map<?, ?> entries)) {
            throw new IllegalStateException("Unexpected TAB placeholder registry");
        }
        return entries.get(name);
    }

    private void unregisterOwned(String name) throws ReflectiveOperationException {
        Object handle = owned.remove(name);
        if (handle != null && find(name) == handle) {
            manager.getClass().getMethod("unregisterPlaceholder", String.class).invoke(manager, name);
        }
    }

    /**
     * Release our placeholders/listener without touching other registered plugins.
     */
    public void shutdown() {
        detach(true);
    }

    private void detach(boolean forget) {
        if (manager != null) {
            for (String name : Set.copyOf(owned.keySet())) {
                try {
                    unregisterOwned(name);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    owned.remove(name);
                }
            }
        }
        if (forget && eventBus != null && eventHandler != null) {
            try {
                Class<?> handlerClass = Class.forName("me.neznamy.tab.api.event.EventHandler");
                eventBus.getClass().getMethod("unregister", handlerClass)
                        .invoke(eventBus, eventHandler);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        if (forget) {
            manager = null;
            eventBus = null;
            eventHandler = null;
            values = Map.of();
            currentInterval = -1;
            reloadRequested = true;
            registeredLogged = false;
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null)
            t = t.getCause();
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
