/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.core.display;

import me.filoghost.holographicdisplays.common.PositionCoordinates;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One line of a hologram, rendered with real entities that are shown and hidden per player.
 * <p>
 * The core plugin describes what should be visible to whom through "packet groups", which it sends from a separate
 * thread. This class only records those requests in concurrent collections, the entities themselves are created,
 * moved and destroyed on the main thread by {@link DisplayNMSManager}. Entities are reused whenever possible: a text
 * change updates an existing entity instead of spawning a new one, which keeps the cost similar to sending a
 * metadata packet.
 */
abstract class DisplayLine {

    /**
     * Content which doesn't change, used for entities that only need to exist (e.g. the hitbox of a clickable line).
     */
    protected static final Object STATIC_CONTENT = new Object();

    private final DisplayNMSManager manager;

    private volatile @Nullable PositionCoordinates position;

    // Written from the packet sender thread, read on the main thread
    private final Set<Player> showQueue = ConcurrentHashMap.newKeySet();
    private final Set<Player> hideQueue = ConcurrentHashMap.newKeySet();
    private final Map<Player, Object> pendingContent = new ConcurrentHashMap<>();

    // Main thread only
    private final Map<Object, Entity> entityByContent = new HashMap<>();
    private final Map<Player, Object> contentByViewer = new HashMap<>();
    private final Set<Player> wantedViewers = new HashSet<>();

    private @Nullable PositionCoordinates appliedPosition;
    private volatile boolean registered;
    private boolean finished;

    DisplayLine(DisplayNMSManager manager) {
        this.manager = manager;
    }

    abstract Type type();

    /**
     * Vertical offset applied to the position reported by the core, which matches what the packet renderer does.
     */
    double yOffset() {
        return 0.0;
    }

    /**
     * ID stored in the marker of the entity, used to route clicks of a clickable line. Zero for lines which don't
     * need it.
     */
    int markerEntityID() {
        return 0;
    }

    /**
     * Called when an entity is created or reused for a different content.
     */
    void applyContent(Entity entity, Object content) {
    }

    void onEntityCreated(Entity entity, Object content) {
    }

    void onEntityRemoved(Entity entity) {
    }

    final DisplayNMSManager getManager() {
        return manager;
    }

    final void recordPosition(PositionCoordinates position) {
        this.position = position;
    }

    final void recordShow(Player player, @Nullable Object content) {
        if (content != null) {
            pendingContent.put(player, content);
        }
        hideQueue.remove(player);
        showQueue.add(player);
        if (!registered) {
            // This line was released before (nobody could see it), so it needs to be ticked again
            manager.register(this);
        }
    }

    final void recordHide(Player player) {
        showQueue.remove(player);
        hideQueue.add(player);
    }

    final boolean isFinished() {
        return finished;
    }

    /**
     * Re-applies visibility for a player who just joined, respawned or changed world: real entities are visible to
     * everyone by default, so the players which must not see a hologram have to be hidden explicitly.
     */
    final void syncViewer(Player player) {
        if (!isOnline(player)) {
            return;
        }
        Object content = contentByViewer.get(player);
        if (wantedViewers.contains(player) && content != null) {
            Entity entity = entityByContent.get(content);
            if (entity != null) {
                manager.show(player, entity);
            }
            return;
        }
        for (Entity entity : entityByContent.values()) {
            manager.hide(player, entity);
        }
    }

    final void setRegistered(boolean registered) {
        this.registered = registered;
    }

    /**
     * Applies the recorded requests. Must be called on the main thread.
     */
    final void tick() {
        // 1. Apply viewer changes
        if (!showQueue.isEmpty()) {
            for (Player player : showQueue) {
                wantedViewers.add(player);
            }
            showQueue.clear();
        }
        if (!hideQueue.isEmpty()) {
            for (Player player : hideQueue) {
                wantedViewers.remove(player);
                pendingContent.remove(player);
                forgetViewer(player);
            }
            hideQueue.clear();
        }

        // 2. Drop players who left: they won't be tracked anymore and there is nobody to hide entities from
        for (Iterator<Player> iterator = wantedViewers.iterator(); iterator.hasNext();) {
            Player player = iterator.next();
            if (!isOnline(player)) {
                iterator.remove();
                pendingContent.remove(player);
                contentByViewer.remove(player);
            }
        }

        if (wantedViewers.isEmpty()) {
            releaseAllEntities();
            contentByViewer.clear();
            // Only drop this line if no show request is in flight, otherwise it would never be rendered again
            finished = pendingContent.isEmpty();
            return;
        }

        // 3. Reuse existing entities for the contents which are needed now
        Set<Object> neededContent = new HashSet<>();
        for (Player player : wantedViewers) {
            Object content = pendingContent.get(player);
            if (content != null) {
                neededContent.add(content);
            }
        }

        List<Entity> spareEntities = new ArrayList<>();
        for (Iterator<Map.Entry<Object, Entity>> iterator = entityByContent.entrySet().iterator();
             iterator.hasNext();) {
            Map.Entry<Object, Entity> entry = iterator.next();
            Entity entity = entry.getValue();
            if (DisplayEntities.isInvalid(entity)) {
                iterator.remove();
                onEntityRemoved(entity);
            } else if (!neededContent.contains(entry.getKey())) {
                iterator.remove();
                spareEntities.add(entity);
            }
        }

        for (Object content : neededContent) {
            if (entityByContent.containsKey(content)) {
                continue;
            }
            Entity spare = spareEntities.isEmpty() ? null : spareEntities.remove(spareEntities.size() - 1);
            if (spare != null) {
                applyContent(spare, content);
                entityByContent.put(content, spare);
            } else {
                Entity entity = createEntity(content);
                if (entity == null) {
                    continue; // e.g. a content which can't be displayed yet
                }
                entityByContent.put(content, entity);
                onEntityCreated(entity, content);
            }
        }
        for (Entity spare : spareEntities) {
            DisplayEntities.remove(spare);
            onEntityRemoved(spare);
        }

        // 4. Move the entities if the line moved
        PositionCoordinates position = this.position;
        if (position != null && !position.equals(appliedPosition)) {
            for (Entity entity : entityByContent.values()) {
                DisplayEntities.move(entity, position, yOffset());
            }
            appliedPosition = position;
        }

        // 5. Show each viewer the entity matching its content
        for (Player player : wantedViewers) {
            Object content = pendingContent.get(player);
            if (content == null) {
                continue;
            }
            Entity entity = entityByContent.get(content);
            if (entity == null) {
                continue;
            }
            if (content.equals(contentByViewer.get(player))) {
                continue; // Already showing exactly this
            }
            Object previousContent = contentByViewer.put(player, content);
            if (previousContent != null) {
                Entity previousEntity = entityByContent.get(previousContent);
                if (previousEntity != null && previousEntity != entity) {
                    manager.hide(player, previousEntity);
                }
            }
            manager.show(player, entity);
        }

        finished = false;
    }

    private @Nullable Entity createEntity(Object content) {
        Player viewer = null;
        for (Player player : wantedViewers) {
            if (isOnline(player)) {
                viewer = player;
                break;
            }
        }
        PositionCoordinates position = this.position;
        if (viewer == null || position == null) {
            return null;
        }

        Entity entity = DisplayEntities.create(viewer, type(), position, yOffset(), markerEntityID(), content);
        if (entity == null) {
            return null;
        }
        applyContent(entity, content);
        manager.hideFromEveryoneExcept(entity, wantedViewers);
        return entity;
    }

    private void forgetViewer(Player player) {
        Object content = contentByViewer.remove(player);
        if (content == null) {
            return;
        }
        Entity entity = entityByContent.get(content);
        if (entity != null && !isViewing(content)) {
            entityByContent.remove(content);
            DisplayEntities.remove(entity);
            onEntityRemoved(entity);
        }
    }

    private boolean isViewing(Object content) {
        for (Object other : contentByViewer.values()) {
            if (Objects.equals(other, content)) {
                return true;
            }
        }
        return false;
    }

    private void releaseAllEntities() {
        if (entityByContent.isEmpty()) {
            return;
        }
        for (Entity entity : entityByContent.values()) {
            DisplayEntities.remove(entity);
            onEntityRemoved(entity);
        }
        entityByContent.clear();
        contentByViewer.clear();
    }

    /**
     * Called when the renderer is stopped: entities must not be left in the world.
     */
    final void discard() {
        wantedViewers.clear();
        showQueue.clear();
        hideQueue.clear();
        pendingContent.clear();
        contentByViewer.clear();
        releaseAllEntities();
        finished = true;
    }

    private static boolean isOnline(Player player) {
        try {
            return player.isOnline();
        } catch (Throwable t) {
            return false;
        }
    }

    enum Type {
        TEXT,
        ITEM,
        CLICKABLE
    }

}
