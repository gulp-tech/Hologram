/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.core.display;

import me.filoghost.fcommons.logging.Log;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tags the entities created by the display renderer so that they can be recognized in the world: click routing uses
 * it as a fallback, and it allows removing leftovers after an unclean shutdown.
 */
final class DisplayMarker {

    /**
     * Entity IDs used to route clicks. They start far above the IDs a vanilla server assigns, so that they can't
     * collide with entities of other plugins.
     */
    private static final AtomicInteger ENTITY_ID_COUNTER = new AtomicInteger(1_500_000_000);
    private static final int SWEEP_LIMIT = 20000;

    private static @Nullable Object key;

    private DisplayMarker() {
    }

    static void init(Plugin plugin) {
        key = DisplayReflect.newKey(plugin);
        if (key == null) {
            Log.info("[Display renderer] This server has no persistent data API, hologram entities won't be tagged"
                             + " and leftovers after a crash can't be removed automatically.");
        }
    }

    static @Nullable Object getKey() {
        return key;
    }

    static int newEntityID() {
        return ENTITY_ID_COUNTER.incrementAndGet();
    }

    static void tag(Entity entity, int markerEntityID) {
        DisplayReflect.writeMarker(entity, key, markerEntityID);
    }

    static int read(Entity entity) {
        return DisplayReflect.readMarker(entity, key);
    }

    /**
     * Removes entities left in the world by a previous run (e.g. after a crash), which would otherwise keep showing
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
                        Log.warning("[Display renderer] Too many leftover entities, the cleanup stopped at "
                                                + SWEEP_LIMIT + " - some outdated hologram entities may remain");
                        return removed;
                    }
                    if (DisplayReflect.hasMarker(entity, key)) {
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

}
