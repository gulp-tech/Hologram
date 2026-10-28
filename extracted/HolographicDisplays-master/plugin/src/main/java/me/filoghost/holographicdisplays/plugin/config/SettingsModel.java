/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.plugin.config;

import me.filoghost.fcommons.config.Config;
import me.filoghost.fcommons.config.mapped.MappedConfig;
import me.filoghost.fcommons.config.mapped.Path;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

public class SettingsModel implements MappedConfig {

    @Path("space-between-lines")
    double spaceBetweenLines = 0.02;

    @Path("holograms-view-range")
    int viewRange = 48;

    @Path("quick-edit-commands")
    boolean quickEditCommands = true;

    /*
     * How holograms are drawn.
     * "auto" - protocol packets on the versions this plugin knows (1.8.4 - 1.20.4), real entities on any other
     *          version instead of refusing to start.
     * "display" - always real entities (invisible armor stands for text, items for item lines), no NMS at all,
     *             so it also works on new/unknown server versions. Clicks need Minecraft 1.19+.
     * "packets" - old behavior, the plugin won't start on unsupported versions.
     */
    @Path("renderer")
    String renderer = "auto";

    /*
     * Vertical offset (in blocks) of the entity which carries a text line in "display" mode.
     * The default matches the packet renderer, change it only if the text looks misaligned.
     */
    @Path("display.text-y-offset")
    double displayTextYOffset = -0.29;

    /*
     * Minimum number of ticks between two updates of the text of a line in "display" mode. Lower values make
     * fast changing placeholders (health, TPS, coordinates) smoother but cost more frames on the clients,
     * higher values save FPS. 1 restores the "update every tick" behaviour, 0 or less is treated as 1.
     */
    @Path("display.text-update-interval-ticks")
    int displayTextUpdateIntervalTicks = 3;

    /*
     * Clickable lines use an extra invisible INTERACTION entity as the hitbox. Turn it off if clients on this
     * server misbehave around that entity type - holograms then just won't be clickable.
     */
    @Path("display.interaction-entities")
    boolean displayInteractionEntities = true;

    /*
     * The welcome hologram is created once, only when database.yml has no holograms at all, and saved into it like
     * any other hologram: delete or edit it and it won't come back. Set this to false to never create it.
     */
    @Path("welcome-hologram.enabled")
    boolean welcomeHologramEnabled = true;

    /*
     * Where the welcome hologram is put. Its name is "welcome", so afterwards it can be moved with
     * /holo moveHere welcome and edited with /holo edit welcome.
     */
    @Path("welcome-hologram.world")
    String welcomeHologramWorld = "world";

    @Path("welcome-hologram.x")
    double welcomeHologramX = -6.5;

    @Path("welcome-hologram.y")
    double welcomeHologramY = 68.5;

    @Path("welcome-hologram.z")
    double welcomeHologramZ = 0.5;

    @Path("placeholders.PlaceholderAPI.enabled")
    boolean placeholderAPIEnabled = true;

    @Path("placeholders.PlaceholderAPI.expand-short-format")
    boolean placeholderAPIShortFormat = true;

    @Path("placeholders.PlaceholderAPI.default-refresh-interval-ticks")
    int placeholderAPIDefaultRefreshIntervalTicks = 200;

    @Path("image-rendering.solid-pixel")
    String imageRenderingSolidPixel = "\u2588";

    @Path("image-rendering.transparent-pixel")
    String imageRenderingTransparentPixel = "&7 \u23B9 ";

    @Path("bungee.refresh-seconds")
    int bungeeRefreshSeconds = 3;

    @Path("bungee.use-RedisBungee")
    boolean useRedisBungee = false;

    @Path("bungee.pinger.enable")
    boolean pingerEnable = false;

    @Path("bungee.pinger.timeout")
    int pingerTimeout = 500;

    @Path("bungee.pinger.offline-motd")
    String pingerOfflineMotd = "&cOffline, couldn't get the MOTD";

    @Path("bungee.pinger.status.online")
    String pingerStatusOnline = "&aOnline";

    @Path("bungee.pinger.status.offline")
    String pingerStatusOffline = "&cOffline";

    @Path("bungee.pinger.motd-remove-leading-trailing-spaces")
    boolean pingerTrimMotd = true;

    @Path("bungee.pinger.servers")
    List<String> pingerServers = Arrays.asList("hub: 127.0.0.1:25565", "games: 127.0.0.1:25566");

    @Path("time.format")
    String timeFormat = "H:mm";

    @Path("time.zone")
    String timeZone = "GMT+1";

    @Path("update-notification")
    boolean updateNotification = true;

    @Path("debug")
    boolean debug = false;

    // Bedrock (Geyser/Floodgate) FPS guard: limits how many hologram lines a single Bedrock client receives.
    @Path("bedrock-guard.enabled")
    boolean bedrockGuardEnabled = true;

    @Path("bedrock-guard.detection")
    String bedrockGuardDetection = "auto";

    @Path("bedrock-guard.view-range")
    int bedrockGuardViewRange = 16;

    @Path("bedrock-guard.max-visible-lines")
    int bedrockGuardMaxVisibleLines = 12;

    @Path("bedrock-guard.max-lines-per-tick")
    int bedrockGuardMaxLinesPerTick = 6;

    @Path("bedrock-guard.join-settle-ticks")
    int bedrockGuardJoinSettleTicks = 20;

    @Path("bedrock-guard.rescan-move-radius")
    int bedrockGuardRescanMoveRadius = 2;

    @Path("bedrock-guard.rescan-interval-ticks")
    int bedrockGuardRescanIntervalTicks = 100;

    @Path("bedrock-guard.players-per-tick")
    int bedrockGuardPlayersPerTick = 6;

    @Path("bedrock-guard.bypass-permission")
    String bedrockGuardBypassPermission = "holographicdisplays.bedrockguard.bypass";

    @Path("bedrock-guard.bedrock-name-prefix")
    String bedrockGuardNamePrefix = "\u001F";

    @Path("bedrock-guard.debug")
    boolean bedrockGuardDebug = false;

    @Override
    public @NotNull List<String> getHeader() {
        return Arrays.asList(
                "",
                "Plugin page: https://dev.bukkit.org/projects/holographic-displays",
                "",
                "Created by filoghost.",
                ""
        );
    }

    @Override
    public boolean beforeLoad(Config rawConfig) {
        boolean modified = false;

        if (rawConfig.contains("images") && !rawConfig.contains("image-rendering")) {
            String oldTransparencyColor = rawConfig.getString("images.transparency.color");
            String oldTransparencySpace = rawConfig.getString("images.transparency.space");
            String oldSolidSymbol = rawConfig.getString("images.symbol");

            if (oldTransparencyColor != null && oldTransparencySpace != null) {
                rawConfig.setString("image-rendering.transparent-pixel", oldTransparencyColor + oldTransparencySpace);
                modified = true;
            }

            if (oldSolidSymbol != null) {
                rawConfig.setString("image-rendering.solid-pixel", oldSolidSymbol);
                modified = true;
            }
        }

        List<String> pathsToRemove = Arrays.asList(
                "vertical-spacing",
                "time-format",
                "bungee-refresh-seconds",
                "using-RedisBungee",
                "bungee-online-format",
                "bungee-offline-format",
                "precise-hologram-movement",
                "images"
        );


        for (String path : pathsToRemove) {
            if (rawConfig.contains(path)) {
                rawConfig.remove(path);
                modified = true;
            }
        }

        return modified;
    }

}
