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

}
