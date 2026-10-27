/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.plugin.bedrock;

import org.jetbrains.annotations.NotNull;

/**
 * Runtime configuration of the {@link BedrockGuard}. The instance is owned by {@link
 * me.filoghost.holographicdisplays.plugin.config.Settings} and refreshed on /holograms reload, so the
 * guard can keep a reference to it instead of copying values.
 */
public class BedrockGuardConfig {

    /** Master switch: when false the guard removes every override and stops scheduling. */
    public boolean enabled = true;

    /** How Bedrock players are recognized. */
    public BedrockPlayerDetector.Detection detection = BedrockPlayerDetector.Detection.AUTO;

    /**
     * Radius (in blocks, on the X/Z plane) in which holograms may be shown to Bedrock players. It is always clamped
     * by "holograms-view-range", which is used instead as the universe for hiding decisions.
     */
    public int viewRange = 16;

    /**
     * Hard budget of hologram lines (== fake armor stand entities) a single Bedrock player can receive at the same
     * time. This is what keeps the client FPS flat: nearest holograms are preferred, the rest stays hidden.
     */
    public int maxVisibleLines = 12;

    /**
     * Maximum number of hologram lines whose spawn/destroy packets the guard may trigger per tick. This spreads the
     * initial burst after a login over several ticks, which is what prevents Geyser clients from timing out.
     */
    public int maxLinesPerTick = 6;

    /** After joining, a Bedrock player receives no hologram at all for this many ticks. */
    public int joinSettleTicks = 20;

    /** A player is re-evaluated after moving this many blocks (and every "rescan-interval-ticks" anyway). */
    public int rescanMoveRadius = 2;

    /** Failsafe full re-scan interval, in ticks. */
    public int rescanIntervalTicks = 100;

    /** How many Bedrock players are scanned per tick, so the cost never depends on the player count. */
    public int playersPerTick = 6;

    /** Players with this permission are not limited by the guard. */
    public @NotNull String bypassPermission = "holographicdisplays.bedrockguard.bypass";

    /** Fallback way to recognize Bedrock players when neither Floodgate nor Geyser can be queried. */
    public @NotNull String namePrefix = "\u001F";

    /** Extra logging, including what each player currently sees. */
    public boolean debug = false;

    /** Keeps every value inside a sane range after it was read from the configuration. */
    public void clamp() {
        detection = detection != null ? detection : BedrockPlayerDetector.Detection.AUTO;
        viewRange = clamp(viewRange, 1, 512);
        maxVisibleLines = clamp(maxVisibleLines, 1, 512);
        maxLinesPerTick = clamp(maxLinesPerTick, 1, 256);
        joinSettleTicks = clamp(joinSettleTicks, 0, 200);
        rescanMoveRadius = clamp(rescanMoveRadius, 1, 64);
        rescanIntervalTicks = clamp(rescanIntervalTicks, 20, 20 * 600);
        playersPerTick = clamp(playersPerTick, 1, 64);
        bypassPermission = bypassPermission != null ? bypassPermission : "holographicdisplays.bedrockguard.bypass";
        namePrefix = namePrefix != null ? namePrefix : "";
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }
}
