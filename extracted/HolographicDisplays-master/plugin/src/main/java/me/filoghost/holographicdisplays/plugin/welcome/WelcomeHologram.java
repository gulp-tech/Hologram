/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.plugin.welcome;

import me.filoghost.fcommons.command.validation.CommandException;
import me.filoghost.fcommons.logging.Log;
import me.filoghost.holographicdisplays.api.Position;
import me.filoghost.holographicdisplays.plugin.commands.InternalHologramEditor;
import me.filoghost.holographicdisplays.plugin.config.Settings;
import me.filoghost.holographicdisplays.plugin.event.InternalHologramChangeEvent.ChangeType;
import me.filoghost.holographicdisplays.plugin.internal.hologram.InternalHologram;
import me.filoghost.holographicdisplays.plugin.internal.hologram.InternalHologramLine;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A welcome hologram for a server which has no holograms yet.
 * <p>
 * It is created once, when {@code database.yml} is empty, and saved into it right away: from that moment on it is an
 * ordinary hologram, editable with the usual commands. Deleting it removes it for good, this class will not bring it
 * back, because the database is no longer empty.
 * <p>
 * The lines are written in the same serialized format as {@code database.yml} uses, so the file below and this list
 * can be copied into each other. Hex colours are not supported by this plugin (it targets the legacy colour codes of
 * 1.8), so the blue gradient is expressed with the closest legacy codes.
 */
public final class WelcomeHologram {

    public static final String NAME = "welcome";

    private static final List<String> LINES = Collections.unmodifiableList(Arrays.asList(
            "icon: BEACON",
            "&b✦ &9V&9o&bi&bd&fP&fe &b✦",
            "&9━━━━━━━━━━━━━━━━━━",
            "&fдобро пожаловать на сервер!",
            "&bприятной игры и удачи ✦",
            "&9━━━━━━━━━━━━━━━━━━",
            "&7✦ наш телеграм-канал ✦",
            "&9t.me.voidpe_bedrock"
    ));

    private WelcomeHologram() {
    }

    /**
     * @return true if the hologram was created
     */
    public static boolean createIfDatabaseIsEmpty(InternalHologramEditor editor) {
        if (!Settings.welcomeHologramEnabled) {
            return false;
        }
        if (!editor.getHolograms().isEmpty()) {
            return false; // Someone already has holograms: nothing to add
        }
        if (editor.hologramExists(NAME)) {
            return false;
        }

        List<InternalHologramLine> lines = new ArrayList<InternalHologramLine>();
        for (String serializedLine : LINES) {
            try {
                lines.add(editor.parseHologramLine(serializedLine));
            } catch (CommandException e) {
                Log.warning("[Welcome hologram] Skipping the line \"" + serializedLine + "\": " + e.getMessage());
                return false;
            }
        }

        String worldName = Settings.welcomeHologramWorld;
        if (worldName == null || worldName.isEmpty()) {
            worldName = Bukkit.getWorlds().isEmpty() ? "world" : Bukkit.getWorlds().get(0).getName();
        }
        if (Bukkit.getWorld(worldName) == null) {
            Log.info("[Welcome hologram] The world \"" + worldName + "\" is not loaded, set 'welcome-hologram.world'"
                             + " in config.yml to create the welcome hologram.");
            return false;
        }

        try {
            InternalHologram hologram = editor.create(NAME, Position.of(worldName,
                                                                                Settings.welcomeHologramX,
                                                                                Settings.welcomeHologramY,
                                                                                Settings.welcomeHologramZ));
            hologram.addLines(lines);
            editor.saveChanges(hologram, ChangeType.CREATE);
        } catch (Throwable t) {
            Log.warning("[Welcome hologram] Can't create it", t);
            return false;
        }

        Log.info("[Welcome hologram] Created at " + worldName + ", " + Settings.welcomeHologramX + ", "
                         + Settings.welcomeHologramY + ", " + Settings.welcomeHologramZ
                         + " - edit it with /holo edit " + NAME + ", delete it with /holo delete " + NAME + ".");
        return true;
    }

    /**
     * The lines this plugin would write, exposed so that they can be documented and compared with a hand written
     * {@code database.yml}.
     */
    public static List<String> getSerializedLines() {
        return LINES;
    }

}
