/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.plugin.bedrock;

import me.filoghost.fcommons.logging.Log;
import me.filoghost.holographicdisplays.api.hologram.VisibilitySettings.Visibility;
import me.filoghost.holographicdisplays.core.HolographicDisplaysCore;
import me.filoghost.holographicdisplays.core.base.BaseHologram;
import me.filoghost.holographicdisplays.core.base.ImmutablePosition;
import me.filoghost.holographicdisplays.plugin.HolographicDisplays;
import me.filoghost.holographicdisplays.plugin.config.Settings;
import me.filoghost.holographicdisplays.plugin.event.HolographicDisplaysReloadEvent;
import me.filoghost.holographicdisplays.plugin.event.InternalHologramChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps holograms from destroying the frame rate of Bedrock clients.
 * <p>
 * Every hologram line is one fake armor stand entity: Geyser has to translate each of them for the Bedrock client,
 * which is what makes phones stutter when a spawn area is full of text. This guard gives each Bedrock player a hard
 * budget of lines ({@code max-visible-lines}) inside a shorter radius ({@code view-range}), choosing the nearest
 * holograms, and it paces the resulting spawn/destroy packets ({@code max-lines-per-tick}) so that joining a crowded
 * server does not flood the connection, which is what usually makes mobile players time out on the login screen.
 * <p>
 * It only uses the public per-player visibility API, so Java players and every other plugin are untouched.
 */
public class BedrockGuard implements Listener {

    private static final double REVEAL_HYSTERESIS = 0.85;

    private final HolographicDisplays plugin;
    private final HolographicDisplaysCore core;
    private final BedrockGuardConfig config;
    private final BedrockPlayerDetector detector;

    private final Map<UUID, PlayerState> states = new HashMap<UUID, PlayerState>();
    private final List<PlayerState> scanOrder = new ArrayList<PlayerState>();
    private final List<Candidate> candidatePool = new ArrayList<Candidate>();
    private final Set<BaseHologram> allowedSet = new HashSet<BaseHologram>();

    private Comparator<Candidate> candidateOrder;

    private BukkitTask task;
    private volatile boolean started;
    private long tickCount;
    private List<BaseHologram> hologramSnapshot;
    private long hologramSnapshotTick = -1;
    private long totalHides;
    private long totalReveals;
    private int scanCursor;

    public BedrockGuard(@NotNull HolographicDisplays plugin, @NotNull HolographicDisplaysCore core,
                        @NotNull BedrockGuardConfig config) {
        this.plugin = plugin;
        this.core = core;
        this.config = config;
        this.detector = new BedrockPlayerDetector();
    }

    private static final class Candidate {
        BaseHologram hologram;
        double distanceSquared;
        int lines;
    }

    private final class PlayerState {
        final Player player;
        final Set<BaseHologram> hidden = new HashSet<BaseHologram>();
        final Deque<BaseHologram> hideQueue = new ArrayDeque<BaseHologram>();
        final Deque<BaseHologram> revealQueue = new ArrayDeque<BaseHologram>();
        final List<Candidate> candidates = new ArrayList<Candidate>();
        double lastScanX;
        double lastScanZ;
        String lastScanWorld;
        boolean scanned;
        int revealDelayTicks;
        int budgetLines;
        int budgetHolograms;
        long hidesApplied;
        long revealsApplied;
        // Location read while deciding whether a scan is needed, reused by the scan itself on the same tick
        Location probeLocation;
        long probeTick;

        private PlayerState(Player player) {
            this.player = player;
        }
    }

    // ---------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------

    public synchronized void start() {
        detector.configure(config.detection, config.namePrefix);
        if (started) {
            return;
        }
        started = true;
        candidateOrder = new Comparator<Candidate>() {
            @Override
            public int compare(Candidate first, Candidate second) {
                return Double.compare(first.distanceSquared, second.distanceSquared);
            }
        };
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayer(player, true);
        }
        // One tick after the core's own task, so visibility changes are seen immediately by the trackers.
        task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    tick();
                } catch (RuntimeException e) {
                    Log.warning("[BedrockGuard] Unexpected error, the guard stays active: " + e);
                }
            }
        }, 1L, 1L);
    }

    public synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
        releaseAll();
        invalidateSnapshot();
    }

    /** Called on /holograms reload: holograms were all re-created, so the previous overrides are gone with them. */
    private void invalidateSnapshot() {
        hologramSnapshot = null;
        hologramSnapshotTick = -1;
    }

    public void onConfigurationReload() {
        invalidateSnapshot(); // The holograms are being re-created, the old list must not be kept
        detector.configure(config.detection, config.namePrefix);
        if (!config.enabled) {
            releaseAll();
            return;
        }
        // The reload re-creates every internal hologram, so the overrides we set are already gone with them.
        for (PlayerState state : new ArrayList<PlayerState>(states.values())) {
            state.hidden.clear();
            state.hideQueue.clear();
            state.revealQueue.clear();
            state.scanned = false;
            state.budgetLines = 0;
            state.budgetHolograms = 0;
        }
    }

    /** Makes every player be re-evaluated on the next ticks (used when holograms are added or edited). */
    public void markAllDirty() {
        for (PlayerState state : states.values()) {
            state.scanned = false;
        }
    }

    public boolean isStarted() {
        return started;
    }

    public @NotNull String describeDetection() {
        return config.detection.name().toLowerCase() + ", provider: " + detector.getResolvedProvider();
    }

    public @NotNull BedrockGuardConfig getConfig() {
        return config;
    }

    public long getTotalHides() {
        return totalHides;
    }

    public long getTotalReveals() {
        return totalReveals;
    }

    public int getManagedPlayersCount() {
        int count = 0;
        for (PlayerState state : states.values()) {
            if (state.scanned) {
                count++;
            }
        }
        return count;
    }

    public @NotNull List<String> describePlayer(@NotNull Player player) {
        List<String> report = new ArrayList<String>();
        PlayerState state = states.get(player.getUniqueId());
        report.add("Bedrock client: " + (detector.isBedrock(player) ? "yes" : "no"));
        report.add("Limited by the guard: " + (state != null ? "yes" : "no"));
        report.add("Bypass permission (" + config.bypassPermission + "): " + hasBypass(player));
        if (state == null) {
            return report;
        }
        report.add("Visible holograms: " + state.budgetHolograms + " (" + state.budgetLines + " lines, budget "
                + config.maxVisibleLines + ")");
        report.add("Hidden by the guard: " + state.hidden.size()
                + " (queued: " + state.hideQueue.size() + " hide / " + state.revealQueue.size() + " reveal)");
        report.add("Applied since login: " + state.hidesApplied + " hides, " + state.revealsApplied + " reveals");
        report.add("Scan: " + (state.scanned ? "done at " + state.lastScanWorld
                + " " + (int) state.lastScanX + "," + (int) state.lastScanZ : "pending"));
        return report;
    }

    // ---------------------------------------------------------------------------------
    // Events
    // ---------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (started && config.enabled) {
            updatePlayer(event.getPlayer(), true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        PlayerState state = states.remove(event.getPlayer().getUniqueId());
        if (state == null) {
            return;
        }
        scanOrder.remove(state);
        // Do not leave a stale HIDDEN behind: the UUID may come back and the holograms outlive players.
        for (BaseHologram hologram : new ArrayList<BaseHologram>(state.hidden)) {
            applyShow(state, hologram);
        }
        state.hidden.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInternalHologramChange(InternalHologramChangeEvent event) {
        if (started) {
            markAllDirty();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginReload(HolographicDisplaysReloadEvent event) {
        onConfigurationReload();
    }

    // ---------------------------------------------------------------------------------
    // Guard loop
    // ---------------------------------------------------------------------------------

    private void updatePlayer(@NotNull Player player, boolean initialSettle) {
        boolean managed = config.enabled && detector.isBedrock(player) && !hasBypass(player);
        PlayerState state = states.get(player.getUniqueId());
        if (!managed) {
            if (state != null) {
                for (BaseHologram hologram : new ArrayList<BaseHologram>(state.hidden)) {
                    applyShow(state, hologram);
                }
                states.remove(player.getUniqueId());
                scanOrder.remove(state);
            }
            return;
        }

        if (state == null) {
            state = new PlayerState(player);
            states.put(player.getUniqueId(), state);
            scanOrder.add(state);
        }
        state.scanned = false;
        state.revealDelayTicks = initialSettle ? config.joinSettleTicks : 0;

        // Before the core's next tick, hide everything it could otherwise send at once: this is what keeps the
        // login packet burst small. Revealing then happens in paced batches.
        Location location = player.getLocation();
        if (initialSettle && location != null && location.getWorld() != null) {
            hideAllInRange(state, location.getWorld().getName(), location.getX(), location.getZ(), true);
        }
    }

    private void tick() {
        if (!started || !config.enabled || scanOrder.isEmpty()) {
            return;
        }
        tickCount++;

        int lineBudget = config.maxLinesPerTick;
        // 1. Apply the queued changes for everybody: this is the only part that touches packets.
        for (int i = 0; i < scanOrder.size() && lineBudget > 0; i++) {
            PlayerState state = scanOrder.get((scanCursor + i) % scanOrder.size());
            if (state.hideQueue.isEmpty() && state.revealQueue.isEmpty()) {
                continue; // Nothing to send: don't enter the queue processing at all
            }
            lineBudget -= applyQueues(state, lineBudget);
        }

        // 2. Compute new decisions for a bounded number of players, so the cost never grows with the player count.
        int scans = 0;
        for (int i = 0; i < scanOrder.size() && scans < config.playersPerTick; i++) {
            PlayerState state = scanOrder.get((scanCursor + i) % scanOrder.size());
            if (!needsScan(state)) {
                continue;
            }
            scan(state);
            scans++;
        }
        if (!scanOrder.isEmpty()) {
            scanCursor = (scanCursor + 1) % scanOrder.size();
        }
    }

    private boolean needsScan(@NotNull PlayerState state) {
        if (!state.scanned) {
            return true;
        }
        if (tickCount % config.rescanIntervalTicks == 0) {
            return true;
        }
        Player player = state.player;
        if (!player.isOnline()) {
            return false;
        }
        Location location = player.getLocation();
        if (location == null || location.getWorld() == null) {
            return false;
        }
        state.probeLocation = location;
        state.probeTick = tickCount;
        if (!location.getWorld().getName().equals(state.lastScanWorld)) {
            return true;
        }
        double moveX = location.getX() - state.lastScanX;
        double moveZ = location.getZ() - state.lastScanZ;
        double rescanRadius = config.rescanMoveRadius;
        return moveX * moveX + moveZ * moveZ >= rescanRadius * rescanRadius;
    }

    private void scan(@NotNull PlayerState state) {
        Player player = state.player;
        if (!player.isOnline()) {
            return;
        }
        // Reuse the location which needsScan() already read on this tick: reading it costs a new Location object
        Location location = state.probeTick == tickCount ? state.probeLocation : player.getLocation();
        state.probeLocation = null;
        if (location == null || location.getWorld() == null) {
            return;
        }

        state.lastScanX = location.getX();
        state.lastScanZ = location.getZ();
        state.lastScanWorld = location.getWorld().getName();
        state.scanned = true;

        double guardRange = Math.min(config.viewRange, Settings.viewRange);
        double revealRange = guardRange * REVEAL_HYSTERESIS;
        double guardRangeSquared = guardRange * guardRange;
        double revealRangeSquared = revealRange * revealRange;

        collectCandidates(state, state.lastScanWorld, location.getX(), location.getZ(),
                Math.max(config.viewRange, Settings.viewRange));

        // Nearest holograms win the line budget.
        allowedSet.clear();
        int usedLines = 0;
        int usedHolograms = 0;
        for (int i = 0; i < state.candidates.size(); i++) {
            Candidate candidate = state.candidates.get(i);
            if (candidate.distanceSquared > guardRangeSquared) {
                continue;
            }
            boolean currentlyHidden = state.hidden.contains(candidate.hologram);
            if (currentlyHidden && candidate.distanceSquared > revealRangeSquared) {
                continue; // Hysteresis: don't reveal something the player is just leaving.
            }
            if (usedLines + candidate.lines > config.maxVisibleLines && usedLines > 0) {
                continue;
            }
            allowedSet.add(candidate.hologram);
            usedLines += candidate.lines;
            usedHolograms++;
        }
        state.budgetLines = usedLines;
        state.budgetHolograms = usedHolograms;

        state.hideQueue.clear();
        state.revealQueue.clear();
        for (int i = 0; i < state.candidates.size(); i++) {
            Candidate candidate = state.candidates.get(i);
            boolean shouldBeHidden = !allowedSet.contains(candidate.hologram);
            boolean isHidden = state.hidden.contains(candidate.hologram);
            if (shouldBeHidden && !isHidden) {
                state.hideQueue.add(candidate.hologram);
            } else if (!shouldBeHidden && isHidden) {
                state.revealQueue.add(candidate.hologram);
            }
        }
    }

    /**
     * The list of registered holograms is rebuilt at most once per tick and shared by all the players scanned in
     * that tick: the core builds a new snapshot on every call, and several players are usually evaluated together.
     */
    private List<BaseHologram> getHologramSnapshot() {
        if (hologramSnapshot == null || hologramSnapshotTick != tickCount) {
            hologramSnapshot = core.getRegisteredHolograms();
            hologramSnapshotTick = tickCount;
        }
        return hologramSnapshot;
    }

    /**
     * Lists the holograms the core could show to this player, sorted from the nearest one.
     */
    private void collectCandidates(@NotNull PlayerState state, @NotNull String worldName, double x, double z,
                                    double radius) {
        List<BaseHologram> holograms = getHologramSnapshot();
        double radiusSquared = radius * radius;
        state.candidates.clear();
        int poolIndex = 0;

        int total = holograms.size();
        for (int i = 0; i < total; i++) {
            BaseHologram hologram = holograms.get(i);
            boolean wasHidden = state.hidden.contains(hologram);
            if (hologram.isDeleted()) {
                if (wasHidden) {
                    state.hidden.remove(hologram); // Its visibility settings are gone with it.
                }
                continue;
            }
            ImmutablePosition position = hologram.getPosition();
            if (!worldName.equals(position.getWorldName())) {
                if (wasHidden) {
                    state.hidden.remove(hologram);
                    applyShow(state, hologram);
                }
                continue;
            }
            double diffX = x - position.getX();
            double diffZ = z - position.getZ();
            double distanceSquared = diffX * diffX + diffZ * diffZ;
            if (distanceSquared > radiusSquared) {
                if (wasHidden) {
                    state.hidden.remove(hologram);
                    applyShow(state, hologram); // Out of every possible range: stop overriding it.
                }
                continue;
            }

            Candidate candidate = getCandidate(poolIndex++);
            candidate.hologram = hologram;
            candidate.distanceSquared = distanceSquared;
            candidate.lines = Math.max(1, hologram.getLines().size());
            state.candidates.add(candidate);
        }
        state.candidates.sort(candidateOrder);
    }

    /**
     * Hides everything the player could currently receive. Used on login, so it is applied at once instead of being
     * paced over ticks: at this point the player has no viewers yet, so no packets are sent at all.
     */
    private void hideAllInRange(@NotNull PlayerState state, @NotNull String worldName, double x, double z,
                                boolean reportLargeSets) {
        List<BaseHologram> holograms = core.getRegisteredHolograms();
        double radius = Math.max(config.viewRange, Settings.viewRange);
        double radiusSquared = radius * radius;
        int hiddenCount = 0;

        int total = holograms.size();
        for (int i = 0; i < total; i++) {
            BaseHologram hologram = holograms.get(i);
            if (hologram.isDeleted()) {
                state.hidden.remove(hologram);
                continue;
            }
            ImmutablePosition position = hologram.getPosition();
            if (!worldName.equals(position.getWorldName())) {
                continue;
            }
            double diffX = x - position.getX();
            double diffZ = z - position.getZ();
            if (diffX * diffX + diffZ * diffZ > radiusSquared) {
                continue;
            }
            if (applyHide(state, hologram)) {
                hiddenCount++;
            }
        }

        if (reportLargeSets && hiddenCount > 200 && config.debug) {
            Log.info("[BedrockGuard] " + hiddenCount + " holograms were in range of " + state.player.getName()
                    + " on login: consider lowering \"holograms-view-range\" in config.yml.");
        }
    }

    private int applyQueues(@NotNull PlayerState state, int lineBudget) {
        if (state.revealDelayTicks > 0) {
            state.revealDelayTicks--;
            return applyHides(state, lineBudget); // Hiding is urgent, revealing can wait.
        }

        int used = 0;
        used += applyHides(state, lineBudget - used);
        used += applyReveals(state, lineBudget - used);
        return used;
    }

    private int applyHides(@NotNull PlayerState state, int lineBudget) {
        int used = 0;
        while (!state.hideQueue.isEmpty() && (lineBudget - used) > 0) {
            BaseHologram hologram = state.hideQueue.poll();
            int lines = Math.max(1, hologram.getLines().size());
            if (applyHide(state, hologram)) {
                used += lines;
            }
        }
        return used;
    }

    private int applyReveals(@NotNull PlayerState state, int lineBudget) {
        int used = 0;
        while (!state.revealQueue.isEmpty() && (lineBudget - used) > 0) {
            BaseHologram hologram = state.revealQueue.poll();
            int lines = Math.max(1, hologram.getLines().size());
            if (applyShow(state, hologram)) {
                used += lines;
            }
        }
        return used;
    }

    private boolean applyHide(@NotNull PlayerState state, @NotNull BaseHologram hologram) {
        if (hologram.isDeleted() || !state.hidden.add(hologram)) {
            return false;
        }
        hologram.getVisibilitySettings().setIndividualVisibility(state.player, Visibility.HIDDEN);
        state.hidesApplied++;
        totalHides++;
        if (config.debug) {
            Log.info("[BedrockGuard] hide \"" + describe(hologram) + "\" for " + state.player.getName());
        }
        return true;
    }

    private boolean applyShow(@NotNull PlayerState state, @NotNull BaseHologram hologram) {
        if (!state.hidden.remove(hologram)) {
            return false;
        }
        if (!hologram.isDeleted()) {
            hologram.getVisibilitySettings().removeIndividualVisibility(state.player);
        }
        state.revealsApplied++;
        totalReveals++;
        if (config.debug) {
            Log.info("[BedrockGuard] show \"" + describe(hologram) + "\" for " + state.player.getName());
        }
        return true;
    }

    /** Re-reads the configuration (also used by the command) and re-evaluates every online player. */
    public void refresh() {
        detector.configure(config.detection, config.namePrefix);
        if (!started) {
            return;
        }
        if (!config.enabled) {
            releaseAll();
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayer(player, false);
        }
        markAllDirty();
    }

    public int getHologramsInRange(@NotNull Player player) {
        PlayerState state = states.get(player.getUniqueId());
        return state == null ? -1 : state.candidates.size();
    }

    private void releaseAll() {
        for (PlayerState state : new ArrayList<PlayerState>(states.values())) {
            for (BaseHologram hologram : new ArrayList<BaseHologram>(state.hidden)) {
                if (!hologram.isDeleted()) {
                    hologram.getVisibilitySettings().removeIndividualVisibility(state.player);
                }
            }
            state.hidden.clear();
            state.hideQueue.clear();
            state.revealQueue.clear();
        }
        states.clear();
        scanOrder.clear();
    }

    private static @NotNull String describe(@NotNull BaseHologram hologram) {
        ImmutablePosition position = hologram.getPosition();
        return position.getWorldName() + " " + (int) position.getX() + "," + (int) position.getY() + ","
                + (int) position.getZ();
    }

    private boolean hasBypass(@NotNull Player player) {
        return !config.bypassPermission.isEmpty() && player.hasPermission(config.bypassPermission);
    }

    private Candidate getCandidate(int index) {
        while (candidatePool.size() <= index) {
            candidatePool.add(new Candidate());
        }
        return candidatePool.get(index);
    }

}
