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

    /**
     * What draws a text line in the display renderer: "auto" or "text-display" uses TEXT_DISPLAY entities, which
     * have no model at all, so nothing can show up behind the text; "armor-stand" uses invisible armor stands, the
     * only option on servers older than 1.19.4.
     */
    public static String displayTextCarrier = "auto";

    /**
     * Vertical offset of a text display entity. Unlike a name tag, the text of a display is positioned by the
     * entity itself, so it needs no correction by default.
     */
    public static double displayTextYOffsetTextDisplay = 0.0;

    /**
     * Whether the entities which carry hologram content are hidden from clients. Turning it off is only useful to
     * see what a hologram is made of, which costs an armor stand body per line.
     */
    public static boolean displayHideBodies = true;

}
