/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.core.display;

import me.filoghost.fcommons.logging.Log;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * Sets the text of an entity without depending on a particular text API.
 * <p>
 * Older servers only have {@code setCustomName(String)}, which parses legacy color codes. Newer ones moved to
 * adventure components and may have dropped the String overload, so the component path is resolved reflectively:
 * this way the display renderer works on both, and reports a single error instead of spamming the console if
 * neither is available.
 */
final class DisplayName {

    private static @Nullable Method setCustomNameStringMethod;
    private static @Nullable Method setCustomNameComponentMethod;
    private static @Nullable Method serializerLegacySectionMethod;
    private static @Nullable Method serializerDeserializeMethod;
    private static @Nullable Object legacySectionSerializer;
    private static boolean reportedFailure;
    private static boolean detected; // API lookups are done once per server start

    private DisplayName() {
    }

    static void detect() {
        if (detected) {
            return; // Lookups are done once: the API of a running server doesn't change
        }
        detected = true;
        setCustomNameStringMethod = findSetter(String.class);

        try {
            Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
            Class<?> serializerClass = Class.forName("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
            setCustomNameComponentMethod = Entity.class.getMethod("setCustomName", componentClass);
            serializerLegacySectionMethod = serializerClass.getMethod("legacySection");
            serializerDeserializeMethod = serializerClass.getMethod("deserialize", CharSequence.class);
            legacySectionSerializer = serializerLegacySectionMethod.invoke(null);
        } catch (Throwable t) {
            setCustomNameComponentMethod = null;
            legacySectionSerializer = null;
        }
    }

    static boolean isSupported() {
        return setCustomNameStringMethod != null || setCustomNameComponentMethod != null;
    }

    /**
     * @return true if legacy color codes can be turned into a chat component, which text display entities need
     */
    static boolean hasComponentSupport() {
        detect();
        return legacySectionSerializer != null && serializerDeserializeMethod != null;
    }

    /**
     * @return an {@code net.kyori.adventure.text.Component}, or null if this server has no adventure API
     */
    static @Nullable Object toComponent(String text) {
        if (legacySectionSerializer == null || serializerDeserializeMethod == null) {
            return null;
        }
        try {
            return serializerDeserializeMethod.invoke(legacySectionSerializer, text);
        } catch (Throwable t) {
            return null;
        }
    }

    private static @Nullable Method findSetter(Class<?> parameterType) {
        try {
            return Entity.class.getMethod("setCustomName", parameterType);
        } catch (Throwable t) {
            return null;
        }
    }

    static void apply(Entity entity, @Nullable String text) {
        Object argument = text == null ? " " : text;
        Method setter = setCustomNameStringMethod;

        if (setter == null && setCustomNameComponentMethod != null) {
            argument = toComponent(text == null ? " " : text);
            if (argument == null) {
                fail("can't convert the text into a chat component");
                return;
            }
            setter = setCustomNameComponentMethod;
        } else if (setter == null) {
            fail("the server has no 'setCustomName' method for entities");
            return;
        }

        try {
            setter.invoke(entity, argument);
        } catch (Throwable t) {
            fail("can't set the text of an entity: " + t);
        }
    }


    private static void fail(String reason) {
        if (reportedFailure) {
            return;
        }
        reportedFailure = true;
        Log.severe("[Display renderer] Holograms can't be shown because " + reason
                           + ". Please report this, or use a server version supported by the packet renderer"
                             + " (1.8.4 - 1.20.4).");
    }

}
