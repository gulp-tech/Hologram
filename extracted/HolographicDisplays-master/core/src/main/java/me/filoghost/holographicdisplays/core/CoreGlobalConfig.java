/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.core;

public class CoreGlobalConfig {

    public static double spaceBetweenLines;
    public static int maxViewRange;

    /**
     * Which renderer to use: "auto" sends protocol packets on supported versions and falls back to the display
     * renderer elsewhere, "display" always uses real entities, "packets" keeps the old behavior of refusing to start
     * on unsupported versions.
     */
    public static String rendererPreference = "auto";

    /**
     * Vertical offset of the armor stand which carries a text line, same as the one used by the packet renderer so
     * that holograms don't shift when the renderer changes.
     */
    public static double displayTextYOffset = -0.29;

    /**
     * How many ticks must pass before the text of a line is written to its entity again. A new name tag makes the
     * client rebuild the text it renders, so re-writing it on every tick of a fast changing placeholder is what
     * costs frames on weak devices. One tick means "update as soon as it changes", like the packet renderer.
     */
    public static int displayTextUpdateIntervalTicks = 3;

    /**
     * Whether clickable lines also spawn an INTERACTION entity (the hitbox which receives the click). Servers whose
     * clients can't handle that entity type, or holograms without any click action, can save one entity per line
     * by turning it off.
     */
    public static boolean displayInteractionEntities = true;

}
