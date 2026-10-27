/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.plugin.commands.subs;

import me.filoghost.fcommons.command.sub.SubCommandContext;
import me.filoghost.fcommons.command.validation.CommandException;
import me.filoghost.fcommons.command.validation.CommandValidate;
import me.filoghost.holographicdisplays.plugin.HolographicDisplays;
import me.filoghost.holographicdisplays.plugin.bedrock.BedrockGuard;
import me.filoghost.holographicdisplays.plugin.bedrock.BedrockGuardConfig;
import me.filoghost.holographicdisplays.plugin.bedrock.BedrockPlayerDetector;
import me.filoghost.holographicdisplays.plugin.commands.HologramSubCommand;
import me.filoghost.holographicdisplays.plugin.config.Settings;
import me.filoghost.holographicdisplays.plugin.format.ColorScheme;
import me.filoghost.holographicdisplays.plugin.format.DisplayFormat;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Inspects and tunes the Bedrock FPS guard without restarting: useful to find the right budget for a server.
 * The values stay in memory, so /holograms reload restores them from config.yml.
 */
public class BedrockGuardCommand extends HologramSubCommand {

    private final HolographicDisplays holographicDisplays;

    public BedrockGuardCommand(HolographicDisplays holographicDisplays) {
        super("bedrockguard", "bg");
        setMinArgs(0);
        setUsageArgs("[status|on|off|debug|everyone|radius <blocks>|budget <lines>|pace <lines>|show] [player]");
        setDescription("Controls the FPS guard for Bedrock (Geyser) players.");

        this.holographicDisplays = holographicDisplays;
    }

    @Override
    public void execute(CommandSender sender, String[] args, SubCommandContext context) throws CommandException {
        BedrockGuard guard = holographicDisplays.getBedrockGuard();
        if (guard == null) {
            throw new CommandException("The Bedrock guard is not active (the plugin is still enabling or failed to load).");
        }

        BedrockGuardConfig config = guard.getConfig();
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "status";

        switch (action) {
            case "on":
                config.enabled = true;
                guard.refresh();
                sender.sendMessage(ColorScheme.PRIMARY + "Bedrock guard enabled (config.yml is not modified).");
                return;
            case "off":
                config.enabled = false;
                guard.refresh();
                sender.sendMessage(ColorScheme.PRIMARY + "Bedrock guard disabled: every player now sees everything.");
                return;
            case "debug":
                config.debug = !config.debug;
                sender.sendMessage(ColorScheme.PRIMARY + "Guard debug logging: " + config.debug);
                return;
            case "everyone":
                config.detection = config.detection == BedrockPlayerDetector.Detection.EVERYONE
                        ? BedrockPlayerDetector.Detection.AUTO : BedrockPlayerDetector.Detection.EVERYONE;
                guard.refresh();
                sender.sendMessage(ColorScheme.PRIMARY + "Detection mode: " + config.detection.name().toLowerCase(Locale.ROOT)
                        + (config.detection == BedrockPlayerDetector.Detection.EVERYONE
                        ? " (every player is treated as Bedrock: testing mode)" : ""));
                return;
            case "radius":
                CommandValidate.check(args.length > 1, "Missing the radius in blocks.");
                config.viewRange = clamp(CommandValidate.parseInteger(args[1]), 1, 512);
                guard.markAllDirty();
                sender.sendMessage(ColorScheme.PRIMARY + "Bedrock players now see holograms within "
                        + config.viewRange + " blocks.");
                return;
            case "budget":
                CommandValidate.check(args.length > 1, "Missing the number of lines.");
                config.maxVisibleLines = clamp(CommandValidate.parseInteger(args[1]), 1, 512);
                guard.markAllDirty();
                sender.sendMessage(ColorScheme.PRIMARY + "Bedrock players now receive at most "
                        + config.maxVisibleLines + " hologram lines at the same time.");
                return;
            case "pace":
                CommandValidate.check(args.length > 1, "Missing the number of lines per tick.");
                config.maxLinesPerTick = clamp(CommandValidate.parseInteger(args[1]), 1, 256);
                sender.sendMessage(ColorScheme.PRIMARY + "Visibility changes are now applied at "
                        + config.maxLinesPerTick + " lines per tick.");
                return;
            case "show":
                CommandValidate.check(config.debug, "Debug logging is off: run /" + context.getRootLabel()
                        + " bedrockguard debug first.");
                sender.sendMessage(ColorScheme.PRIMARY + "The guard is now recomputing every player, watch the console.");
                guard.markAllDirty();
                return;
            case "status":
                sendStatus(sender, guard, config);
                return;
            default:
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null) {
                    throw new CommandException("Unknown argument \"" + args[0] + "\".");
                }
                for (String line : guard.describePlayer(target)) {
                    sender.sendMessage(ColorScheme.SECONDARY + target.getName() + ": " + ColorScheme.PRIMARY + line);
                }
        }
    }

    private void sendStatus(CommandSender sender, BedrockGuard guard, BedrockGuardConfig config) {
        DisplayFormat.sendTitle(sender, "Bedrock FPS guard");

        String state;
        if (!guard.isStarted()) {
            state = "not running";
        } else if (!config.enabled) {
            state = ColorScheme.ERROR + "disabled";
        } else {
            state = ColorScheme.PRIMARY + "enabled";
        }
        sender.sendMessage(ColorScheme.SECONDARY + "State: " + state
                + " | detection: " + guard.describeDetection());
        sender.sendMessage(ColorScheme.SECONDARY + "Range: " + config.viewRange
                + " blocks | line budget: " + config.maxVisibleLines
                + " | pace: " + config.maxLinesPerTick + "/tick"
                + " | settle: " + config.joinSettleTicks + " ticks");
        sender.sendMessage(ColorScheme.SECONDARY + "Global view range (config.yml): " + Settings.viewRange
                + " | stored holograms: " + holographicDisplays.getInternalHologramManager().getHolograms().size());
        sender.sendMessage(ColorScheme.SECONDARY + "Managed players: " + guard.getManagedPlayersCount()
                + "/" + Bukkit.getOnlinePlayers().size()
                + " | applied hides: " + guard.getTotalHides()
                + " | reveals: " + guard.getTotalReveals());
        sender.sendMessage(ColorScheme.SECONDARY + "Remember: these values are lost on /holograms reload,"
                + " edit config.yml to make them permanent.");
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }

}
