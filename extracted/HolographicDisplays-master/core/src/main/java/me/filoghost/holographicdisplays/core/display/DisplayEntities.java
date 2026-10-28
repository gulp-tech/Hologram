/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.core.display;

import me.filoghost.fcommons.logging.Log;
import me.filoghost.holographicdisplays.common.PositionCoordinates;
import me.filoghost.holographicdisplays.core.CoreGlobalConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * Creates and configures the entities used by the display renderer.
 * <p>
 * Optional API (anything added after the oldest Bukkit version this plugin compiles against) is called through
 * {@link DisplayReflect}, so that a missing method degrades into a single warning instead of breaking holograms or
 * the connection of players.
 */
final class DisplayEntities {

    private static final int MAX_TICKS = Integer.MAX_VALUE;
    private static final int MAX_POTION_DURATION = Integer.MAX_VALUE >> 5;
    private static final Set<String> WARNED = new HashSet<>();

    private static boolean invisibilityUnsupported;
    private static boolean interactionWarned;

    private DisplayEntities() {
    }

    static @Nullable Entity create(Player viewer, DisplayLine.Type type, PositionCoordinates coordinates,
                                   double yOffset, int markerEntityID, @Nullable Object content) {
        World world = viewer.getWorld();
        if (world == null) {
            return null;
        }
        Location location = new Location(world, coordinates.getX(), coordinates.getY() + yOffset, coordinates.getZ(),
                                         0.0f, 0.0f);

        Entity entity;
        switch (type) {
            case TEXT:
                // A text display has no model, so there is no stand body to hide: it is what modern servers get
                entity = DisplayTextDisplay.spawn(location);
                if (entity != null) {
                    if (!DisplayTextDisplay.applyText(entity, (String) content)) {
                        DisplayEntities.remove(entity);
                        entity = spawnNamedStand(location, (String) content);
                    }
                } else {
                    entity = spawnNamedStand(location, (String) content);
                }
                break;
            case ITEM:
                entity = spawnItem(location, (ItemStack) content);
                break;
            case CLICKABLE:
                entity = spawnInteraction(world, location);
                break;
            default:
                throw new IllegalArgumentException("unknown type: " + type);
        }
        if (entity == null) {
            return null;
        }
        if (DisplayTextDisplay.isTextDisplay(entity)) {
            protect(entity); // A display entity must not be saved in the world nor removed by despawning
        }

        DisplayMarker.tag(entity, markerEntityID);
        return entity;
    }

    private static @Nullable Entity spawnNamedStand(Location location, @Nullable String text) {
        ArmorStand stand;
        try {
            stand = location.getWorld().spawn(location, ArmorStand.class);
        } catch (Throwable t) {
            warnOnce("spawn-stand", "Can't spawn an armor stand, are armor stands allowed on this server?", t);
            return null;
        }
        if (stand == null) {
            return null;
        }

        // Marker + invisible: nothing but the name tag is shown, and players can't push or hit the stand
        DisplayReflect.invoke(stand, "setMarker", boolean.class, true);
        DisplayReflect.invoke(stand, "setSmall", boolean.class, true);
        DisplayReflect.invoke(stand, "setBasePlate", boolean.class, false);
        DisplayReflect.invoke(stand, "setArms", boolean.class, false);
        DisplayReflect.invoke(stand, "setGravity", boolean.class, false);
        DisplayReflect.invoke(stand, "setSilent", boolean.class, true);
        hideBody(stand);
        protect(stand);
        applyText(stand, text);
        return stand;
    }

    private static @Nullable Entity spawnItem(Location location, @Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return null;
        }
        Item item;
        try {
            item = location.getWorld().dropItem(location, itemStack);
        } catch (Throwable t) {
            warnOnce("spawn-item", "Can't spawn an item entity", t);
            return null;
        }
        if (item == null) {
            return null;
        }

        try {
            item.setPickupDelay(MAX_TICKS); // A hologram must never become loot
            item.setVelocity(new Vector());
        } catch (Throwable t) {
            warnOnce("configure-item", "Can't configure an item entity", t);
        }
        DisplayReflect.invoke(item, "setGravity", boolean.class, false);
        protect(item);
        return item;
    }

    private static @Nullable Entity spawnInteraction(World world, Location location) {
        if (!CoreGlobalConfig.displayInteractionEntities) {
            return null; // Clicks are disabled on purpose, the hitbox entity is not needed at all
        }
        EntityType type;
        try {
            type = EntityType.valueOf("INTERACTION");
        } catch (Throwable t) {
            warnNoInteraction("the INTERACTION entity type is not available on this server");
            return null;
        }

        Entity entity;
        try {
            entity = world.spawnEntity(location, type);
        } catch (Throwable t) {
            warnNoInteraction("an interaction entity can't be spawned");
            return null;
        }
        if (entity == null) {
            return null;
        }

        // Same size as the hitbox of the packet renderer, so clicking feels the same
        DisplayReflect.invoke(entity, "setInteractionWidth", double.class, 0.51);
        DisplayReflect.invoke(entity, "setInteractionHeight", float.class, 0.5f);
        return entity;
    }

    static void applyText(Entity entity, @Nullable String text) {
        if (DisplayTextDisplay.isTextDisplay(entity)) {
            DisplayTextDisplay.applyText(entity, text);
            return;
        }
        if (!(entity instanceof ArmorStand)) {
            return;
        }
        DisplayName.apply(entity, text);
        try {
            ((ArmorStand) entity).setCustomNameVisible(text != null && !text.isEmpty());
        } catch (Throwable t) {
            warnOnce("name-visibility", "Can't show the text of a hologram line", t);
        }
    }

    static void applyItem(Entity entity, @Nullable ItemStack itemStack) {
        if (!(entity instanceof Item) || itemStack == null || itemStack.getType() == Material.AIR) {
            return;
        }
        try {
            ((Item) entity).setItemStack(itemStack);
        } catch (Throwable t) {
            warnOnce("item-content", "Can't set the item of a hologram line", t);
        }
    }

    static void move(Entity entity, PositionCoordinates coordinates, double yOffset) {
        if (isInvalid(entity)) {
            return;
        }
        try {
            Location current = entity.getLocation();
            World world = current.getWorld();
            if (world == null) {
                return;
            }
            double targetY = coordinates.getY() + yOffset;
            double diffX = current.getX() - coordinates.getX();
            double diffY = current.getY() - targetY;
            double diffZ = current.getZ() - coordinates.getZ();
            if (diffX * diffX + diffY * diffY + diffZ * diffZ < 1.0E-8) {
                return;
            }
            entity.teleport(new Location(world, coordinates.getX(), targetY, coordinates.getZ(),
                                         current.getYaw(), 0.0f));
        } catch (Throwable t) {
            warnOnce("move", "Can't move a hologram entity", t);
        }
    }

    static void remove(@Nullable Entity entity) {
        if (entity == null) {
            return;
        }
        try {
            entity.remove();
        } catch (Throwable ignored) {
            // The entity may already be gone (e.g. its chunk was unloaded), which is fine
        }
    }

    static boolean isInvalid(@Nullable Entity entity) {
        if (entity == null) {
            return true;
        }
        try {
            return !entity.isValid() || entity.isDead();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Hides the body of an entity while keeping its name tag visible.
     * <p>
     * The invisible flag is the one clients and Bedrock protocol translators are supposed to respect, so it is
     * applied first and the potion effect is applied as well: on servers where only one of the two works, the
     * hologram still ends up as floating text without a stand behind it.
     */
    private static void hideBody(LivingEntity entity) {
        if (!CoreGlobalConfig.displayHideBodies) {
            return;
        }

        DisplayReflect.invoke(entity, "setVisible", boolean.class, false);
        Object nmsEntity = DisplayReflect.invokeReturning(entity, "getHandle");
        if (nmsEntity != null) {
            // Mojang mappings call it setInvisible, older Spigot mappings setVisible - both set the same metadata
            DisplayReflect.invoke(nmsEntity, "setInvisible", boolean.class, true);
            DisplayReflect.invoke(nmsEntity, "setVisible", boolean.class, false);
        }
        makeInvisible(entity);
    }

    private static void makeInvisible(LivingEntity entity) {
        if (invisibilityUnsupported) {
            return;
        }
        try {
            // Bukkit caps a duration above 2^26 and logs about it, so the cap is applied here instead
            Object effect = DisplayReflect.newPotionEffect(PotionEffect.class, PotionEffectType.INVISIBILITY,
                                                           MAX_POTION_DURATION, true, false);
            if (effect == null || !DisplayReflect.invoke(entity, "addPotionEffect", PotionEffect.class, effect)) {
                invisibilityUnsupported = true;
                warnOnce("invisibility", "Hologram entities can't be made invisible, they will be shown as armor"
                        + " stands (this only affects their look, not performance)", null);
            }
        } catch (Throwable t) {
            invisibilityUnsupported = true;
            warnOnce("invisibility", "Hologram entities can't be made invisible, they will be shown as armor stands"
                    + " (this only affects their look, not performance)", t);
        }
    }

    private static void protect(Entity entity) {
        DisplayReflect.invoke(entity, "setInvulnerable", boolean.class, true);
        DisplayReflect.invoke(entity, "setRemoveWhenFarAway", boolean.class, false);
        DisplayReflect.invoke(entity, "setPersistent", boolean.class, false);
    }

    private static void warnNoInteraction(String reason) {
        if (interactionWarned) {
            return;
        }
        interactionWarned = true;
        Log.warning("[Display renderer] Hologram line clicks are disabled because " + reason
                            + ". Clicks work on Minecraft 1.19 and newer in the display renderer.");
    }

    private static void warnOnce(String key, String message, @Nullable Throwable t) {
        if (!WARNED.add(key)) {
            return;
        }
        if (t == null) {
            Log.warning("[Display renderer] " + message);
        } else {
            Log.warning("[Display renderer] " + message, t);
        }
    }

}
