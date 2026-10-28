/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.plugin.builtin;

import me.filoghost.fcommons.config.Config;
import me.filoghost.fcommons.config.ConfigLoader;
import me.filoghost.fcommons.config.ConfigPath;
import me.filoghost.fcommons.config.ConfigSection;
import me.filoghost.fcommons.config.ConfigType;
import me.filoghost.fcommons.config.exception.ConfigException;
import me.filoghost.fcommons.logging.Log;
import me.filoghost.holographicdisplays.api.Position;
import me.filoghost.holographicdisplays.plugin.commands.InternalHologramEditor;
import me.filoghost.holographicdisplays.plugin.config.ConfigManager;
import me.filoghost.holographicdisplays.plugin.config.InternalHologramConfig;
import me.filoghost.holographicdisplays.plugin.config.InternalHologramLoadException;
import me.filoghost.holographicdisplays.plugin.config.Settings;
import me.filoghost.holographicdisplays.plugin.event.InternalHologramChangeEvent.ChangeType;
import me.filoghost.holographicdisplays.plugin.internal.hologram.InternalHologram;
import me.filoghost.holographicdisplays.plugin.internal.hologram.InternalHologramLine;
import org.bukkit.Bukkit;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * Holograms which the server ships with, described in {@code builtin-holograms.yml} next to config.yml.
 * <p>
 * The file uses exactly the format of {@code database.yml}, so a section can be copied from one file to the other
 * and back. Every section whose name is not taken by an existing hologram is created once and saved into the
 * database, which makes it an ordinary hologram afterwards: {@code /holo edit <name>} works on it, and moving or
 * re-editing it never touches this file again.
 * <p>
 * A hologram which was deleted is created again on the next start, because the file still lists it - remove the
 * section (or set {@code builtin-holograms.enabled: false}) to keep it gone.
 */
public final class BuiltinHolograms {

    public static final String FILE_NAME = "builtin-holograms.yml";

    private BuiltinHolograms() {
    }

    /**
     * @return how many holograms were added to the database
     */
    public static int createMissing(InternalHologramEditor editor, ConfigManager configManager) {
        if (!Settings.builtinHologramsEnabled) {
            return 0;
        }

        ConfigLoader configLoader = configManager.getConfigLoader(FILE_NAME);
        Config config;
        try {
            if (!Files.isRegularFile(configLoader.getFile())) {
                configLoader.createDefault(); // Written from the plugin jar, so it can be edited afterwards
            }
            config = configLoader.init();
        } catch (ConfigException e) {
            Log.warning("[Builtin holograms] Can't read or create \"" + FILE_NAME + "\", skipping", e);
            return 0;
        }

        int created = 0;
        int skipped = 0;
        for (Map.Entry<ConfigPath, ConfigSection> entry : config.toMap(ConfigType.SECTION).entrySet()) {
            String hologramName = entry.getKey().asRawKey();
            if (hologramName == null || hologramName.isEmpty()) {
                continue;
            }
            if (editor.hologramExists(hologramName)) {
                continue; // Already there, possibly edited or moved by an admin
            }
            if (createOne(editor, hologramName, entry.getValue())) {
                created++;
            } else {
                skipped++;
            }
        }

        if (created > 0) {
            Log.info("[Builtin holograms] Created " + created + " hologram(s) from \"" + FILE_NAME
                             + "\", they are ordinary holograms now (/holo edit <name>).");
        }
        if (created > 0 && skipped > 0) {
            Log.info("[Builtin holograms] " + skipped + " section(s) in \"" + FILE_NAME
                             + "\" could not be created, see the warnings above.");
        }
        return created;
    }

    private static boolean createOne(InternalHologramEditor editor, String hologramName, ConfigSection section) {
        InternalHologramConfig hologramConfig = new InternalHologramConfig(hologramName, section);

        List<InternalHologramLine> lines;
        Position position;
        try {
            lines = hologramConfig.deserializeLines();
            position = hologramConfig.deserializePosition();
        } catch (InternalHologramLoadException e) {
            Log.warning("[Builtin holograms] Skipping \"" + hologramName + "\": " + e.getMessage());
            return false;
        }

        if (Bukkit.getWorld(position.getWorldName()) == null) {
            Log.warning("[Builtin holograms] Skipping \"" + hologramName + "\": the world \""
                                + position.getWorldName() + "\" is not loaded.");
            return false;
        }

        try {
            InternalHologram hologram = editor.create(hologramName, position);
            hologram.addLines(lines);
            editor.saveChanges(hologram, ChangeType.CREATE);
            return true;
        } catch (Throwable t) {
            Log.warning("[Builtin holograms] Can't create \"" + hologramName + "\"", t);
            return false;
        }
    }

}
