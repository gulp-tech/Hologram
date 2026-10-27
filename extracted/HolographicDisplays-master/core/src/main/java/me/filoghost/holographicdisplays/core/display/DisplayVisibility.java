/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.core.display;

import me.filoghost.fcommons.logging.Log;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * Per-player visibility of real entities, used instead of sending spawn/destroy packets to a single connection.
 * <p>
 * The Bukkit methods are resolved reflectively and once, because the display renderer must not fail to load on
 * servers whose API doesn't provide them: in that case the renderer reports the problem and holograms are simply
 * not shown, while the rest of the plugin keeps working.
 */
final class DisplayVisibility {

    private static @Nullable Method showMethod;
    private static @Nullable Method hideMethod;
    private static @Nullable Method visibleByDefaultMethod;

    private DisplayVisibility() {
    }

    static void detect() {
        try {
            showMethod = Player.class.getMethod("showEntity", Plugin.class, Entity.class);
            hideMethod = Player.class.getMethod("hideEntity", Plugin.class, Entity.class);
        } catch (Throwable t) {
            showMethod = null;
            hideMethod = null;
        }

        try {
            // Paper and Purpur: entities can start out hidden from everyone, which avoids a brief flash on spawn
            visibleByDefaultMethod = Entity.class.getMethod("setVisibleByDefault", boolean.class);
        } catch (Throwable t) {
            visibleByDefaultMethod = null;
        }
    }

    static boolean isSupported() {
        return showMethod != null && hideMethod != null;
    }

    static boolean canStartHidden() {
        return visibleByDefaultMethod != null;
    }

    static void show(Plugin plugin, Player player, Entity entity) {
        invoke(showMethod, plugin, player, entity);
    }

    static void hide(Plugin plugin, Player player, Entity entity) {
        invoke(hideMethod, plugin, player, entity);
    }

    static void startHidden(Entity entity) {
        if (visibleByDefaultMethod == null) {
            return;
        }
        try {
            visibleByDefaultMethod.invoke(entity, false);
        } catch (Throwable t) {
            Log.warning("[Display renderer] Can't hide entities by default", t);
            visibleByDefaultMethod = null;
        }
    }

    private static void invoke(@Nullable Method method, Plugin plugin, Player player, Entity entity) {
        if (method == null) {
            return;
        }
        try {
            method.invoke(player, plugin, entity);
        } catch (Throwable ignored) {
            // A player that is leaving can throw here, which is harmless
        }
    }

}
