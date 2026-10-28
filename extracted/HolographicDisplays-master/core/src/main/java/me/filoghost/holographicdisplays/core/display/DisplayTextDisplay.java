/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.core.display;

import me.filoghost.fcommons.logging.Log;
import me.filoghost.holographicdisplays.core.CoreGlobalConfig;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * Draws a text line with a TEXT_DISPLAY entity, so that no armor stand is used at all.
 * <p>
 * An armor stand can only be hidden by the "invisible" flag, which is a piece of entity metadata that some server
 * implementations and protocol translators (Bedrock) ignore: the stand is then rendered while its name tag is shown,
 * and players see a column of stands behind the text. A text display has no model to hide - the text is its content -
 * so it can't produce that artifact.
 * <p>
 * Text displays were added in Minecraft 1.19.4, and this module is compiled against the oldest Bukkit API the
 * plugin supports, so everything is resolved reflectively. If anything is missing or fails once, holograms keep
 * working on armor stands: the only cost is a warning in the log.
 */
final class DisplayTextDisplay {

    private static final String PREFERENCE_AUTO = "auto";
    private static final String PREFERENCE_TEXT_DISPLAY = "text-display";
    private static final String PREFERENCE_ARMOR_STAND = "armor-stand";

    private static boolean detected;
    private static boolean available;
    private static boolean broken; // The server has the API but using it failed: stop trying

    private static @Nullable EntityType entityType;
    private static @Nullable Method setTextMethod;
    private static @Nullable Class<?> billboardClass;
    private static @Nullable Object billboardCenter;
    private static @Nullable Class<?> alignmentClass;
    private static @Nullable Object alignmentCenter;

    private DisplayTextDisplay() {
    }

    /**
     * @return true if text lines should be drawn with text displays on this server
     */
    static boolean isActive() {
        if (!detected) {
            detect();
        }
        return available && !broken;
    }

    static boolean isTextDisplay(@Nullable Entity entity) {
        return entity != null && entityType != null && entity.getType() == entityType;
    }

    private static synchronized void detect() {
        if (detected) {
            return;
        }
        detected = true;

        String preference = CoreGlobalConfig.displayTextCarrier;
        preference = preference == null ? PREFERENCE_AUTO : preference.trim().toLowerCase(java.util.Locale.ROOT);
        if (PREFERENCE_ARMOR_STAND.equals(preference) || "stand".equals(preference) || "classic".equals(preference)) {
            Log.info("[Display renderer] Text lines use armor stand entities (display.carrier: " + preference + ").");
            return;
        }
        boolean forced = PREFERENCE_TEXT_DISPLAY.equals(preference);
        if (!PREFERENCE_AUTO.equals(preference) && !forced) {
            Log.warning("[Display renderer] Unknown display.carrier value \"" + preference
                                + "\", only \"text-display\", \"armor-stand\" and \"auto\" are known, using \"auto\".");
        }

        try {
            entityType = EntityType.valueOf("TEXT_DISPLAY");
        } catch (Throwable t) {
            entityType = null;
        }
        if (entityType == null) {
            if (forced) {
                Log.warning("[Display renderer] display.carrier: text-display was requested but this server has no"
                                    + " TEXT_DISPLAY entity type (it was added in Minecraft 1.19.4), armor stands"
                                    + " are used instead.");
            }
            return;
        }

        Class<?> textDisplayClass;
        try {
            textDisplayClass = Class.forName("org.bukkit.entity.TextDisplay");
            Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
            setTextMethod = textDisplayClass.getMethod("setText", componentClass);
        } catch (Throwable t) {
            warnUnsupported(forced, "this server doesn't provide the TextDisplay API needed to set its text", t);
            setTextMethod = null;
            return;
        }

        if (!DisplayName.hasComponentSupport()) {
            warnUnsupported(forced, "this server can't turn the text into a chat component", null);
            setTextMethod = null;
            return;
        }

        billboardClass = findEnumClass("org.bukkit.entity.TextDisplay$Billboard", "org.bukkit.entity.Display$Billboard");
        billboardCenter = findEnumValue(billboardClass, "CENTER");
        alignmentClass = findEnumClass("org.bukkit.entity.TextDisplay$Alignment");
        alignmentCenter = findEnumValue(alignmentClass, "CENTER");

        available = true;
        Log.info("[Display renderer] Text lines are drawn with TEXT_DISPLAY entities, so there is no armor stand"
                         + " body to hide. Set display.carrier: armor-stand in config.yml to go back to stands.");
    }

    private static void warnUnsupported(boolean forced, String reason, @Nullable Throwable t) {
        String message = "[Display renderer] Hologram text will use armor stands because " + reason + ".";
        if (forced) {
            Log.warning(message, t);
        } else {
            Log.info(message);
        }
    }

    private static @Nullable Class<?> findEnumClass(String... classNames) {
        for (String className : classNames) {
            try {
                Class<?> clazz = Class.forName(className);
                if (clazz.isEnum()) {
                    return clazz;
                }
            } catch (Throwable ignored) {
                // Optional API: not having it just leaves the server default in place
            }
        }
        return null;
    }

    private static @Nullable Object findEnumValue(@Nullable Class<?> enumClass, String name) {
        if (enumClass == null) {
            return null;
        }
        try {
            // valueOf is static on every enum, and this class is only known at runtime
            Method valueOf = enumClass.getMethod("valueOf", String.class);
            return valueOf.invoke(null, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Spawns a text display at the given position. The text itself is applied by {@link #applyText(Entity, String)}.
     *
     * @return the entity, or null if this server can't use text displays (the caller falls back to armor stands)
     */
    static @Nullable Entity spawn(Location location) {
        if (!isActive()) {
            return null;
        }
        World world = location.getWorld();
        EntityType type = entityType;
        if (world == null || type == null) {
            return null;
        }

        Entity entity;
        try {
            entity = world.spawnEntity(location, type);
        } catch (Throwable t) {
            giveUp("a text display entity can't be spawned", t);
            return null;
        }
        if (entity == null) {
            giveUp("a text display entity can't be spawned", null);
            return null;
        }

        // Centered billboard: the text follows the viewer and stays where the line is, like a name tag used to
        DisplayReflect.invoke(entity, "setBillboard", billboardClass, billboardCenter);
        DisplayReflect.invoke(entity, "setTextAlignment", alignmentClass, alignmentCenter);
        return entity;
    }

    /**
     * Writes the text of a line into a text display.
     *
     * @return false if the text can't be shown this way, which makes the renderer fall back to armor stands
     */
    static boolean applyText(Entity entity, @Nullable String text) {
        Method setter = setTextMethod;
        if (setter == null) {
            return false;
        }

        boolean hasText = text != null && !text.isEmpty();
        Object component = null;
        if (hasText) {
            component = DisplayName.toComponent(text);
            if (component == null) {
                giveUp("the text of a line can't be turned into a chat component", null);
                return false;
            }
        }

        try {
            setter.invoke(entity, component); // A null component clears the text, which hides the line
            return true;
        } catch (Throwable t) {
            giveUp("the text of a line can't be written into a text display", t);
            return false;
        }
    }

    private static void giveUp(String reason, @Nullable Throwable t) {
        if (!broken) {
            broken = true;
            Log.warning("[Display renderer] Hologram text falls back to armor stands because " + reason
                                + ". Text lines are still shown, but they may be visible as stands on clients which"
                                + " ignore the invisible flag. Use display.carrier: armor-stand in config.yml to"
                                + " skip this attempt in the future.", t);
        }
    }

}
