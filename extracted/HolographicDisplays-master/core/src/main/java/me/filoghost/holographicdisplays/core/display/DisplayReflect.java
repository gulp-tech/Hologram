/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.core.display;

import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Optional API access for the display renderer.
 * <p>
 * The core module is compiled against the oldest Bukkit API the plugin supports, so every method which was added
 * later has to be resolved at runtime. Lookups are cached, and a missing method means "this server can't do it"
 * rather than an error, which keeps old and new servers on the same code path.
 */
final class DisplayReflect {

    private static final Map<String, Method> CACHE = new HashMap<>();

    private DisplayReflect() {
    }

    /**
     * Invokes {@code methodName(parameterType)} on the target if the server provides it.
     *
     * @return true if the method exists and didn't throw
     */
    static boolean invoke(Object target, String methodName, Class<?> parameterType, Object value) {
        if (target == null) {
            return false;
        }
        Method method = lookup(target.getClass(), methodName, parameterType);
        if (method == null) {
            return false;
        }
        try {
            method.invoke(target, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return false;
        }
        Method method = lookup(target.getClass(), methodName);
        if (method == null) {
            return false;
        }
        try {
            method.invoke(target);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static @Nullable Method lookup(Class<?> type, String methodName, Class<?>... parameterTypes) {
        String cacheKey = type.getName() + "#" + methodName + parameterTypes.length;
        if (CACHE.containsKey(cacheKey)) {
            return CACHE.get(cacheKey);
        }

        Method result = null;
        try {
            result = type.getMethod(methodName, parameterTypes);
        } catch (Throwable t) {
            result = null;
        }
        CACHE.put(cacheKey, result);
        return result;
    }

    /**
     * Builds a potion effect using whichever constructor this server provides: the extra flags (ambient, particles)
     * were added after the API this module compiles against.
     */
    static @Nullable Object newPotionEffect(Class<?> effectTypeClass, Object type, int duration, boolean ambient,
                                             boolean particles) {
        try {
            return effectTypeClass.getConstructor(type.getClass(), int.class, int.class, boolean.class, boolean.class)
                    .newInstance(type, duration, 0, ambient, particles);
        } catch (Throwable ignored) {
        }
        try {
            return effectTypeClass.getConstructor(type.getClass(), int.class, int.class, boolean.class)
                    .newInstance(type, duration, 0, ambient);
        } catch (Throwable ignored) {
        }
        try {
            return effectTypeClass.getConstructor(type.getClass(), int.class, int.class)
                    .newInstance(type, duration, 0);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Creates a {@code org.bukkit.NamespacedKey} without depending on that class at compile time.
     *
     * @return the key, or null if this server has no persistent data API
     */
    static @Nullable Object newKey(Plugin plugin) {
        try {
            Class<?> keyClass = Class.forName("org.bukkit.NamespacedKey");
            Constructor<?> constructor = keyClass.getConstructor(Plugin.class, String.class);
            return constructor.newInstance(plugin, "display-renderer");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Writes an integer into the persistent data container of an entity, so that entities created by this renderer
     * can be recognized in the world (used to remove leftovers after an unclean shutdown). Does nothing on servers
     * without that API.
     */
    static void writeMarker(Entity entity, @Nullable Object key, int value) {
        if (key == null) {
            return;
        }
        try {
            Object container = getContainer(entity);
            if (container == null) {
                return;
            }
            Class<?> dataTypeClass = Class.forName("org.bukkit.persistence.PersistentDataType");
            Object integerType = dataTypeClass.getField("INTEGER").get(null);
            Method set = container.getClass().getMethod("set", key.getClass(), dataTypeClass, Object.class);
            set.invoke(container, key, integerType, value);
        } catch (Throwable ignored) {
            // Only used for cleanup, losing it is not a problem
        }
    }

    static boolean hasMarker(Entity entity, @Nullable Object key) {
        if (key == null) {
            return false;
        }
        try {
            Object container = getContainer(entity);
            if (container == null) {
                return false;
            }
            Class<?> dataTypeClass = Class.forName("org.bukkit.persistence.PersistentDataType");
            Object integerType = dataTypeClass.getField("INTEGER").get(null);
            Method has = container.getClass().getMethod("has", key.getClass(), dataTypeClass);
            return Boolean.TRUE.equals(has.invoke(container, key, integerType));
        } catch (Throwable t) {
            return false;
        }
    }

    static int readMarker(Entity entity, @Nullable Object key) {
        if (key == null) {
            return 0;
        }
        try {
            Object container = getContainer(entity);
            if (container == null) {
                return 0;
            }
            Class<?> dataTypeClass = Class.forName("org.bukkit.persistence.PersistentDataType");
            Object integerType = dataTypeClass.getField("INTEGER").get(null);
            Method get = container.getClass().getMethod("get", key.getClass(), dataTypeClass);
            Object value = get.invoke(container, key, integerType);
            return value instanceof Integer ? (Integer) value : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static @Nullable Object getContainer(Entity entity) throws Throwable {
        Method getContainer = lookup(entity.getClass(), "getPersistentDataContainer");
        if (getContainer == null) {
            return null;
        }
        return getContainer.invoke(entity);
    }

}
