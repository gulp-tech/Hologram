/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.core.display;

import me.filoghost.fcommons.logging.Log;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tags the entities created by the display renderer so that they can be recognized later, both for click routing
 * and to remove leftovers after an unclean shutdown.
 */
final class DisplayMarker {

    /**
     * Entity IDs used to route clicks. They start far above the IDs a vanilla server assigns, so that they can't
     * collide with the entities of other plugins.
     */
    private static final AtomicInteger ENTITY_ID_COUNTER = new AtomicInteger(1_500_000_000);
    private static final int SWEEP_LIMIT = 20000;

    private static @Nullable NamespacedKey key;

    private DisplayMarker() {
    }

    static void init(Plugin plugin) {
        try {
            key = new NamespacedKey(plugin, "display-renderer");
        } catch (Throwable t) {
            key = null;
            Log.warning("[Display renderer] Can't create a persistence key, leftover entities won't be cleaned up", t);
        }
    }

    static @Nullable NamespacedKey getKey() {
        return key;
    }

    static int newEntityID() {
        return ENTITY_ID_COUNTER.incrementAndGet();
    }

    /**
     * Removes entities left in the world by a previous run (e.g. a crash), which would otherwise keep displaying
     * outdated text.
     *
     * @return the number of removed entities
     */
    static int sweepLeftovers() {
        if (key == null) {
            return 0;
        }

        int removed = 0;
        try {
            for (World world : Bukkit.getWorlds()) {
                for (Entity entity : world.getEntities()) {
                    if (removed >= SWEEP_LIMIT) {
                        Log.warning("[Display renderer] Too many leftover entities, stopped the cleanup at "
                                                + SWEEP_LIMIT + " - some outdated hologram entities may still be visible");
                        return removed;
                    }
                    if (hasMarker(entity)) {
                        entity.remove();
                        removed++;
                    }
                }
            }
        } catch (Throwable t) {
            Log.warning("[Display renderer] Can't clean up leftover entities", t);
        }
        return removed;
    }

    static boolean hasMarker(Entity entity) {
        if (key == null || entity == null) {
            return false;
        }
        try {
            return entity.getPersistentDataContainer().has(key, PersistentDataType.INTEGER);
        } catch (Throwable t) {
            return false;
        }
    }

    static int read(Entity entity) {
        if (key == null || entity == null) {
            return 0;
        }
        try {
            Integer value = entity.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
            return value != null ? value : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

}
