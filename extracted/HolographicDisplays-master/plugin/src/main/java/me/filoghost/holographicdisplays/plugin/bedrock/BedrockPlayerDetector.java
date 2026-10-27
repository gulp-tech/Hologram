/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.plugin.bedrock;

import me.filoghost.fcommons.logging.Log;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;

/**
 * Recognizes Bedrock players. Geyser and Floodgate are only soft dependencies, so their classes are resolved
 * reflectively and nothing breaks when they are not installed (or when the server runs them as a standalone proxy).
 */
public class BedrockPlayerDetector {

    public enum Detection {

        AUTO,
        FLOODGATE,
        GEYSER,
        PREFIX,
        /** Treat every player as a Bedrock player: useful to measure the effect without a Bedrock client. */
        EVERYONE,
        /** Guard disabled for every player. */
        NONE;

        public static @NotNull Detection parse(@Nullable String value) {
            if (value == null || value.trim().isEmpty()) {
                return AUTO;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                Log.warning("[BedrockGuard] Unknown detection mode \"" + value + "\", using \"auto\".");
                return AUTO;
            }
        }

    }

    /**
     * A method of a provider class, either "boolean isBedrock(UUID)" or "Connection getConnection(UUID)" where a
     * non-null result means the player is connected through Geyser.
     */
    private static final class ReflectiveCheck {

        private final String name;
        private final Method instanceMethod;
        private final Object instance;
        private final boolean objectResult;

        private ReflectiveCheck(String name, Method instanceMethod, Object instance, boolean objectResult) {
            this.name = name;
            this.instanceMethod = instanceMethod;
            this.instance = instance;
            this.objectResult = objectResult;
        }

        private boolean isBedrock(UUID uuid) {
            try {
                Object result = instanceMethod.invoke(instance, uuid);
                if (objectResult) {
                    return result != null;
                }
                return result instanceof Boolean && (Boolean) result;
            } catch (ReflectiveOperationException | RuntimeException e) {
                return false;
            }
        }

    }

    /** Provider class names that expose a static getInstance() giving us the check we need. */
    private static final String[][] FLOODGATE_CANDIDATES = {
            {"org.geysermc.floodgate.api.FloodgateApi", "isFloodgatePlayer"},
            {"org.geysermc.floodgate.FloodgateApi", "isFloodgatePlayer"},
    };
    private static final String[][] GEYSER_CANDIDATES = {
            {"org.geysermc.geyser.GeyserImpl", "getConnectionByUuid"},
            {"org.geysermc.geyser.Geyser", "isBedrockPlayer"},
            {"org.geysermc.api.GeyserApi", "isBedrockPlayer"},
            {"org.geysermc.api.GeyserApiBase", "isBedrockPlayer"},
    };

    private volatile Detection detection = Detection.AUTO;
    private volatile String namePrefix = "";
    private volatile ReflectiveCheck floodgateCheck;
    private volatile ReflectiveCheck geyserCheck;
    private volatile String resolvedProvider = "none";

    public void configure(@NotNull Detection detection, @NotNull String namePrefix) {
        boolean providerChanged = this.detection != detection;
        this.detection = detection;
        this.namePrefix = namePrefix;
        if (providerChanged) {
            floodgateCheck = detection == Detection.PREFIX || detection == Detection.NONE || detection == Detection.EVERYONE
                    ? null : resolve(FLOODGATE_CANDIDATES);
            geyserCheck = detection == Detection.PREFIX || detection == Detection.NONE || detection == Detection.EVERYONE
                    ? null : resolve(GEYSER_CANDIDATES);
            resolvedProvider = describeProviders();
            Log.info("[BedrockGuard] Bedrock detection: " + detection.name().toLowerCase(Locale.ROOT)
                    + ", provider: " + resolvedProvider);
        }
    }

    public @NotNull String getResolvedProvider() {
        return resolvedProvider;
    }

    public boolean isBedrock(@Nullable Player player) {
        if (player == null) {
            return false;
        }
        switch (detection) {
            case EVERYONE:
                return true;
            case NONE:
                return false;
            case PREFIX:
                return matchesPrefix(player);
            case FLOODGATE:
                return floodgateCheck != null && floodgateCheck.isBedrock(player.getUniqueId());
            case GEYSER:
                return geyserCheck != null && geyserCheck.isBedrock(player.getUniqueId());
            case AUTO:
            default:
                if (floodgateCheck != null && floodgateCheck.isBedrock(player.getUniqueId())) {
                    return true;
                }
                if (geyserCheck != null && geyserCheck.isBedrock(player.getUniqueId())) {
                    return true;
                }
                // Without any API (e.g. Geyser-Standalone) the name prefix is the only hint left.
                return (floodgateCheck == null && geyserCheck == null) && matchesPrefix(player);
        }
    }

    private boolean matchesPrefix(@NotNull Player player) {
        if (namePrefix.isEmpty()) {
            return false;
        }
        String name = player.getName();
        return name != null && name.startsWith(namePrefix);
    }

    private static @Nullable ReflectiveCheck resolve(String[][] candidates) {
        for (String[] candidate : candidates) {
            ReflectiveCheck check = resolve(candidate[0], candidate[1]);
            if (check != null) {
                return check;
            }
        }
        return null;
    }

    private static @Nullable ReflectiveCheck resolve(@NotNull String className, @NotNull String methodName) {
        try {
            Class<?> providerClass = Class.forName(className, true, BedrockPlayerDetector.class.getClassLoader());
            Method getInstance = providerClass.getMethod("getInstance");
            Object instance = getInstance.invoke(null);
            if (instance == null) {
                return null;
            }
            Method booleanMethod;
            try {
                booleanMethod = instance.getClass().getMethod(methodName, UUID.class);
            } catch (NoSuchMethodException e) {
                return null;
            }
            Class<?> returnType = booleanMethod.getReturnType();
            if (returnType == boolean.class || returnType == Boolean.class) {
                return new ReflectiveCheck(className, booleanMethod, instance, false);
            }
            if (returnType.isInterface() || !returnType.isPrimitive()) {
                return new ReflectiveCheck(className, booleanMethod, instance, true);
            }
            return null;
        } catch (ClassNotFoundException e) {
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.warning("[BedrockGuard] Could not use " + className + " for Bedrock detection: " + e);
            return null;
        }
    }

    private @NotNull String describeProviders() {
        StringBuilder builder = new StringBuilder();
        if (floodgateCheck != null) {
            builder.append(floodgateCheck.name);
        }
        if (geyserCheck != null) {
            if (builder.length() > 0) {
                builder.append(" + ");
            }
            builder.append(geyserCheck.name);
        }
        if (builder.length() == 0) {
            builder.append("no API found, using name prefix \"");
            builder.append(namePrefix.isEmpty() ? "<disabled>" : namePrefix).append("\"");
            if (Bukkit.getPluginManager().getPlugin("Geyser-Spigot") == null) {
                builder.append(" (Geyser-Spigot not installed)");
            }
        }
        return builder.toString();
    }

}
