/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.core.display;

import me.filoghost.fcommons.logging.Log;
import me.filoghost.holographicdisplays.nms.common.NMSManager;
import me.filoghost.holographicdisplays.nms.common.PacketListener;
import me.filoghost.holographicdisplays.nms.common.entity.ClickableNMSPacketEntity;
import me.filoghost.holographicdisplays.nms.common.entity.ItemNMSPacketEntity;
import me.filoghost.holographicdisplays.nms.common.entity.TextNMSPacketEntity;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Renderer which uses real entities from the public Bukkit API instead of hand-written protocol packets.
 * <p>
 * It exists so that HolographicDisplays can keep working on server versions whose internals it doesn't know (where
 * the packet renderer can't be used at all) and as a safer alternative on new versions. Because the entities are
 * real, visibility is controlled per player with {@code showEntity}/{@code hideEntity}: a player who shouldn't see a
 * hologram doesn't receive any packet for it, which is also what the Bedrock FPS guard relies on.
 */
public final class DisplayNMSManager implements NMSManager {

    private static final long FLUSH_INTERVAL_TICKS = 1L;
    /**
     * How many lines are re-synced per tick for one player. A player who joins has to be shown (or hidden from) every
     * line of every hologram, and sending all of that in a single tick overflows the queue of a client which is
     * already busy loading the world - which shows up as a stutter, or as a crash on clients with a small buffer.
     */
    private static final int SYNC_LINES_PER_TICK = 24;
    private static final long ERROR_LOG_INTERVAL_MILLIS = 20000L;

    private final Plugin plugin;
    private final CopyOnWriteArrayList<DisplayLine> lines = new CopyOnWriteArrayList<>();
    private volatile boolean stopped;
    private final Map<Integer, Integer> markerIDByEntityID = new ConcurrentHashMap<>();

    private volatile @Nullable PacketListener packetListener;
    private @Nullable BukkitTask flushTask;
    private boolean listenersRegistered;
    private long lastErrorLogMillis;
    private long currentTick;

    // Main thread only: players waiting for their visibility to be re-applied, and how far each of them got
    private final Map<Player, Integer> pendingVisibilitySync = new LinkedHashMap<Player, Integer>();

    public DisplayNMSManager(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Checks whether the server provides the per-player visibility API this renderer depends on.
     */
    public static boolean isSupported() {
        DisplayVisibility.detect();
        DisplayName.detect();
        return DisplayVisibility.isSupported() && DisplayName.isSupported();
    }

    public void start() {
        DisplayVisibility.detect();
        DisplayName.detect();
        DisplayMarker.init(plugin);

        int swept = DisplayMarker.sweepLeftovers();
        if (swept > 0) {
            Log.info("[Display renderer] Removed " + swept + " leftover hologram entities from a previous run.");
        }

        if (!listenersRegistered) {
            Bukkit.getPluginManager().registerEvents(new DisplayListener(), plugin);
            listenersRegistered = true;
        }

        flushTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flush, FLUSH_INTERVAL_TICKS,
                                                       FLUSH_INTERVAL_TICKS);
    }

    public void stop() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }

        // Entities must not stay in the world after the plugin is disabled
        for (DisplayLine line : lines) {
            try {
                line.discard();
            } catch (Throwable t) {
                logError(t, "Can't remove a hologram entity");
            }
        }
        for (DisplayLine line : lines) {
            line.setRegistered(false);
        }
        lines.clear();
        markerIDByEntityID.clear();
        pendingVisibilitySync.clear();
        packetListener = null;
        stopped = true;
    }

    /**
     * Lines register themselves when they get a viewer, and are removed when they don't need any entity anymore, so
     * that the flush loop only visits lines which actually do something.
     */
    void register(DisplayLine line) {
        if (stopped) {
            return;
        }
        if (lines.addIfAbsent(line)) {
            line.setRegistered(true);
        }
    }

    private void unregister(DisplayLine line) {
        line.setRegistered(false);
        if (line.hasPendingRequests()) {
            // A show request arrived while the line was being released: it has to be ticked again, otherwise the
            // hologram would stay invisible until something else touches it.
            register(line);
        }
    }

    /**
     * Applies everything that was requested since the last tick. Runs on the main thread, since creating and moving
     * entities is only allowed there.
     */
    private void flush() {
        currentTick++;

        // CopyOnWriteArrayList iterators don't support remove(), so finished lines are collected and dropped in one
        // batch after the loop: removing them while iterating used to throw UnsupportedOperationException on every
        // tick, which both spammed the console and left the released lines registered forever.
        List<DisplayLine> finished = null;
        for (DisplayLine line : lines) {
            try {
                line.tick();
                if (line.isFinished()) {
                    if (finished == null) {
                        finished = new ArrayList<DisplayLine>(2);
                    }
                    finished.add(line);
                }
            } catch (Throwable t) {
                if (finished == null) {
                    finished = new ArrayList<DisplayLine>(2);
                }
                finished.add(line);
                try {
                    line.discard();
                } catch (Throwable ignored) {
                }
                logError(t, "Error while updating a hologram, its entities were removed");
            }
        }

        if (finished != null) {
            lines.removeAll(finished);
            for (DisplayLine line : finished) {
                unregister(line);
            }
        }

        processVisibilitySyncs();
    }

    long getCurrentTick() {
        return currentTick;
    }

    /**
     * Queues a full visibility update for a player, spread over several ticks instead of one burst of packets.
     */
    void requestVisibilitySync(Player player) {
        pendingVisibilitySync.put(player, Integer.valueOf(0));
    }

    private void processVisibilitySyncs() {
        if (pendingVisibilitySync.isEmpty()) {
            return;
        }

        int budget = SYNC_LINES_PER_TICK;
        List<DisplayLine> snapshot = new ArrayList<DisplayLine>(lines);
        for (Iterator<Map.Entry<Player, Integer>> iterator = pendingVisibilitySync.entrySet().iterator();
             iterator.hasNext();) {
            Map.Entry<Player, Integer> entry = iterator.next();
            Player player = entry.getKey();
            if (!isOnline(player)) {
                iterator.remove();
                continue;
            }

            int start = entry.getValue().intValue();
            int index = start;
            int end = Math.min(snapshot.size(), index + budget);
            for (; index < end; index++) {
                try {
                    snapshot.get(index).syncViewer(player);
                } catch (Throwable t) {
                    logError(t, "Can't update hologram visibility for " + player.getName());
                }
            }

            budget -= (index - start);
            if (index >= snapshot.size()) {
                iterator.remove(); // Every line of every hologram was visited
            } else {
                entry.setValue(Integer.valueOf(index));
            }
            if (budget <= 0) {
                break;
            }
        }
    }

    private static boolean isOnline(Player player) {
        try {
            return player.isOnline();
        } catch (Throwable t) {
            return false; // A player which is leaving isn't worth an update
        }
    }

    private void logError(Throwable t, String message) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLogMillis < ERROR_LOG_INTERVAL_MILLIS) {
            return;
        }
        lastErrorLogMillis = now;
        Log.warning("[Display renderer] " + message, t);
    }

    /**
     * Applies the visibility of every line to one player at once.
     */
    void syncPlayer(Player player) {
        for (DisplayLine line : lines) {
            try {
                line.syncViewer(player);
            } catch (Throwable t) {
                logError(t, "Can't update hologram visibility for " + player.getName());
            }
        }
    }

    void show(Player player, Entity entity) {
        DisplayVisibility.show(plugin, player, entity);
    }

    void hide(Player player, Entity entity) {
        DisplayVisibility.hide(plugin, player, entity);
    }

    /**
     * On servers without {@code setVisibleByDefault} (plain Spigot) a spawned entity is briefly visible to everyone
     * in range, so it has to be hidden from the players which should not see it.
     */
    void hideFromEveryoneExcept(Entity entity, Set<Player> viewers) {
        if (DisplayVisibility.canStartHidden()) {
            DisplayVisibility.startHidden(entity);
            return;
        }

        try {
            World world = entity.getWorld();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (viewers.contains(player)) {
                    continue;
                }
                if (world != null && world.equals(player.getWorld())) {
                    hide(player, entity);
                }
            }
        } catch (Throwable t) {
            logError(t, "Can't hide a hologram entity from players");
        }
    }

    void registerClickEntity(Entity entity, int markerID) {
        try {
            markerIDByEntityID.put(entity.getEntityId(), markerID);
        } catch (Throwable ignored) {
        }
    }

    void unregisterClickEntity(Entity entity) {
        try {
            markerIDByEntityID.remove(entity.getEntityId());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public TextNMSPacketEntity newTextPacketEntity() {
        return new DisplayTextLine(this);
    }

    @Override
    public ItemNMSPacketEntity newItemPacketEntity() {
        return new DisplayItemLine(this);
    }

    @Override
    public ClickableNMSPacketEntity newClickablePacketEntity() {
        return new DisplayClickableLine(this);
    }

    /**
     * Clicks are received through regular Bukkit events, so there is nothing to inject into the connection of a
     * player. The listener is kept because the core uses it to route clicks to hologram lines.
     */
    @Override
    public void injectPacketListener(Player player, PacketListener packetListener) {
        if (this.packetListener == null) {
            this.packetListener = packetListener;
        }
    }

    @Override
    public void uninjectPacketListener(Player player) {
        // Nothing was injected
    }

    public int getManagedLineCount() {
        return lines.size();
    }

    private class DisplayListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
            syncPlayerLater(event.getPlayer());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
            syncPlayerLater(event.getPlayer());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent event) {
            syncPlayerLater(event.getPlayer());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
            if (event.getTo() == null || event.getFrom() == null) {
                return;
            }
            if (event.getFrom().getWorld() != event.getTo().getWorld()) {
                syncPlayerLater(event.getPlayer());
            }
        }

        /**
         * Runs after the core had a chance to update its viewers for this tick.
         */
        private void syncPlayerLater(final Player player) {
            // One tick later the core knows which lines this player should see; the update itself is spread out
            Bukkit.getScheduler().runTask(plugin, () -> requestVisibilitySync(player));
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        public void onPlayerInteract(PlayerInteractEntityEvent event) {
            if (handle(event.getPlayer(), event.getRightClicked())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        public void onAttack(EntityDamageByEntityEvent event) {
            if (!(event.getDamager() instanceof Player)) {
                return;
            }
            if (handle((Player) event.getDamager(), event.getEntity())) {
                event.setCancelled(true);
            }
        }

        private boolean handle(Player player, Entity entity) {
            if (entity == null) {
                return false;
            }

            int markerID;
            try {
                Integer byEntityID = markerIDByEntityID.get(entity.getEntityId());
                markerID = byEntityID != null ? byEntityID : DisplayMarker.read(entity);
            } catch (Throwable t) {
                return false;
            }
            if (markerID == 0) {
                return false;
            }

            PacketListener listener = packetListener;
            if (listener == null) {
                return false;
            }
            try {
                return listener.onAsyncEntityInteract(player, markerID);
            } catch (Throwable t) {
                logError(t, "Error while routing a hologram click");
                return false;
            }
        }

    }

}
