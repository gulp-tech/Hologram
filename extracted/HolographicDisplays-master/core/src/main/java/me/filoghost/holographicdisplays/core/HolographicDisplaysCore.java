/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.core;

import com.gmail.filoghost.holographicdisplays.api.internal.HologramsAPIProvider;
import me.filoghost.fcommons.FCommonsPlugin.PluginEnableException;
import me.filoghost.fcommons.logging.ErrorCollector;
import me.filoghost.fcommons.logging.Log;
import me.filoghost.holographicdisplays.api.internal.HolographicDisplaysAPIProvider;
import me.filoghost.holographicdisplays.core.NMSVersion.OutdatedVersionException;
import me.filoghost.holographicdisplays.core.NMSVersion.UnknownVersionException;
import me.filoghost.holographicdisplays.core.api.current.APIHologramManager;
import me.filoghost.holographicdisplays.core.api.current.DefaultHolographicDisplaysAPIProvider;
import me.filoghost.holographicdisplays.core.api.v2.V2HologramManager;
import me.filoghost.holographicdisplays.core.api.v2.V2HologramsAPIProvider;
import me.filoghost.holographicdisplays.core.base.BaseHologram;
import me.filoghost.holographicdisplays.core.display.DisplayNMSManager;
import me.filoghost.holographicdisplays.core.listener.ChunkListener;
import me.filoghost.holographicdisplays.core.listener.LineClickListener;
import me.filoghost.holographicdisplays.core.listener.PlayerListener;
import me.filoghost.holographicdisplays.core.placeholder.registry.PlaceholderRegistry;
import me.filoghost.holographicdisplays.core.placeholder.tracking.ActivePlaceholderTracker;
import me.filoghost.holographicdisplays.core.tick.TickClock;
import me.filoghost.holographicdisplays.core.tick.TickingTask;
import me.filoghost.holographicdisplays.core.tracking.LineTrackerManager;
import me.filoghost.holographicdisplays.core.tracking.PacketSenderExecutor;
import me.filoghost.holographicdisplays.nms.common.NMSManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class HolographicDisplaysCore {

    private NMSManager nmsManager;
    private LineTrackerManager lineTrackerManager;
    private APIHologramManager apiHologramManager;
    private V2HologramManager v2HologramManager;

    private String rendererName = "packets";

    public void enable(Plugin plugin, ErrorCollector errorCollector) throws PluginEnableException {
        boolean forceDisplay = "display".equalsIgnoreCase(CoreGlobalConfig.rendererPreference);
        Throwable versionError = null;

        if (!forceDisplay) {
            try {
                nmsManager = NMSVersion.getCurrent().createNMSManager(errorCollector);
            } catch (UnknownVersionException e) {
                versionError = e;
            } catch (OutdatedVersionException e) {
                versionError = e;
            } catch (Throwable t) {
                versionError = t;
            }
        }

        if (nmsManager == null) {
            if ("packets".equalsIgnoreCase(CoreGlobalConfig.rendererPreference)) {
                // Compatibility with the old behavior: refuse to start instead of rendering differently
                if (versionError instanceof UnknownVersionException) {
                    throw new PluginEnableException("Holographic Displays only supports Spigot from 1.8 to 1.20."
                                                            + " Set 'renderer: display' in config.yml to use real"
                                                            + " entities instead.");
                }
                if (versionError instanceof OutdatedVersionException) {
                    throw new PluginEnableException("Holographic Displays only supports "
                                                            + ((OutdatedVersionException) versionError)
                                                                    .getMinimumSupportedVersion() + " and above.");
                }
                if (versionError != null) {
                    throw new PluginEnableException(versionError, "Couldn't initialize the NMS manager.");
                }
                throw new PluginEnableException("Couldn't initialize the NMS manager.");
            }

            if (!DisplayNMSManager.isSupported()) {
                throw new PluginEnableException(
                        "Can't render holograms: this server doesn't support the API the display renderer needs"
                                + " (per-player entity visibility via Player#showEntity and entity custom names).",
                        "Try a server which provides it (Paper/Purpur/Spigot 1.18+) or a version with a bundled NMS"
                                + " adapter (1.8.4 - 1.20.4), which uses the packet renderer.");
            }

            DisplayNMSManager displayManager = new DisplayNMSManager(plugin);
            displayManager.start();
            nmsManager = displayManager;
            rendererName = "display";
            Log.warning("HolographicDisplays can't use protocol packets on this server (Minecraft version isn't"
                                + " supported by its NMS adapters), so holograms are rendered with real entities"
                                + " instead. Text lines use invisible armor stands, item lines use item entities.");
            if (versionError != null) {
                Log.warning("[Display renderer] Reason: " + versionError.getMessage());
            }
        }

        PacketSenderExecutor.start();

        PlaceholderRegistry placeholderRegistry = new PlaceholderRegistry();
        TickClock tickClock = new TickClock();
        ActivePlaceholderTracker placeholderTracker = new ActivePlaceholderTracker(placeholderRegistry, tickClock);
        LineClickListener lineClickListener = new LineClickListener();
        lineTrackerManager = new LineTrackerManager(nmsManager, placeholderTracker, lineClickListener);
        apiHologramManager = new APIHologramManager(lineTrackerManager);
        v2HologramManager = new V2HologramManager(lineTrackerManager);

        // Add packet listener for players currently online
        for (Player player : Bukkit.getOnlinePlayers()) {
            nmsManager.injectPacketListener(player, lineClickListener);
        }

        // Tasks
        TickingTask tickingTask = new TickingTask(tickClock, placeholderTracker, lineTrackerManager, lineClickListener);
        Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, tickingTask, 0, 1);

        // Listeners
        Bukkit.getPluginManager().registerEvents(new PlayerListener(nmsManager, lineClickListener, tickingTask), plugin);
        Bukkit.getPluginManager().registerEvents(new ChunkListener(plugin, apiHologramManager, v2HologramManager), plugin);

        // Enable the APIs
        HolographicDisplaysAPIProvider.setImplementation(
                new DefaultHolographicDisplaysAPIProvider(apiHologramManager, placeholderRegistry));
        enableLegacyAPI(v2HologramManager, placeholderRegistry);
    }

    @SuppressWarnings("deprecation")
    private void enableLegacyAPI(V2HologramManager hologramManager, PlaceholderRegistry placeholderRegistry) {
        HologramsAPIProvider.setImplementation(new V2HologramsAPIProvider(hologramManager, placeholderRegistry));
    }

    /**
     * Name of the renderer in use: "packets" for the version-specific protocol implementation, "display" for real
     * entities.
     */
    public String getRendererName() {
        return rendererName;
    }

    public void setSpaceBetweenHologramLines(double spaceBetweenLines) {
        CoreGlobalConfig.spaceBetweenLines = spaceBetweenLines;
        for (BaseHologram hologram : apiHologramManager.getHolograms()) {
            hologram.getLines().updatePositions();
        }
        for (BaseHologram hologram : v2HologramManager.getHolograms()) {
            hologram.getLines().updatePositions();
        }
    }

    public void setMaxViewRange(int maxViewRange) {
        CoreGlobalConfig.maxViewRange = maxViewRange;
    }

    /**
     * Returns a snapshot of every hologram currently registered, from both the current and the legacy API, including
     * the ones created by other plugins. Must only be used on the main thread.
     */
    public @NotNull List<BaseHologram> getRegisteredHolograms() {
        List<BaseHologram> holograms = new ArrayList<>();
        if (apiHologramManager != null) {
            holograms.addAll(apiHologramManager.getHolograms());
        }
        if (v2HologramManager != null) {
            holograms.addAll(v2HologramManager.getHolograms());
        }
        return holograms;
    }

    public void disable() {
        if (lineTrackerManager != null) {
            lineTrackerManager.resetViewersAndSendDestroyPackets();
        }

        if (nmsManager != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                nmsManager.uninjectPacketListener(player);
            }
        }

        PacketSenderExecutor.stopGracefully();

        if (nmsManager instanceof DisplayNMSManager) {
            ((DisplayNMSManager) nmsManager).stop();
        }
    }

}
