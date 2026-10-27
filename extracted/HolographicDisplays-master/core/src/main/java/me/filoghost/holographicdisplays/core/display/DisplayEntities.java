/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.core.display;

import me.filoghost.fcommons.logging.Log;
import me.filoghost.holographicdisplays.common.PositionCoordinates;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Creates and configures the entities used by the display renderer.
 * <p>
 * Only the public Bukkit API is used, so that the renderer keeps working on server versions whose internals are
 * unknown to this plugin. Optional API elements are accessed reflectively: a missing or renamed method degrades into
 * a single warning instead of breaking holograms or the connection of players.
 */
final class DisplayEntities {

    private static final int MAX_TICKS = Integer.MAX_VALUE;
    private static boolean invisibilityUnsupported;
    private static boolean interactionWarned;
    private static boolean persistenceChecked;
    private static boolean persistentSetterMissing;

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
                entity = spawnNamedStand(location, (String) content);
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

        markAsOurs(entity, markerEntityID);
        return entity;
    }

    private static @Nullable Entity spawnNamedStand(Location location, @Nullable String text) {
        ArmorStand stand;
        try {
            stand = location.getWorld().spawn(location, ArmorStand.class);
        } catch (Throwable t) {
            warnOnce("spawn-stand", "Can't spawn an armor stand, are 'armor_stands' allowed in server config?", t);
            return null;
        }
        if (stand == null) {
            return null;
        }

        try {
            stand.setMarker(true); // No hitbox: players can't push or attack it
            stand.setSmall(true);
            stand.setBasePlate(false);
            stand.setArms(false);
            stand.setGravity(false);
            stand.setSilent(true);
            makeInvisible(stand);
            protect(stand);
            applyText(stand, text);
        } catch (Throwable t) {
            warnOnce("configure-stand", "Can't configure an armor stand", t);
        }
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
            item.setPickupDelay(MAX_TICKS); // Holograms must never become loot
            item.setVelocity(new Vector());
            item.setGravity(false);
            protect(item);
        } catch (Throwable t) {
            warnOnce("configure-item", "Can't configure an item entity", t);
        }
        return item;
    }

    private static @Nullable Entity spawnInteraction(World world, Location location) {
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
            warnNoInteraction("the interaction entity can't be spawned");
            return null;
        }
        if (entity == null) {
            return null;
        }

        // Same size as the hitbox used by the packet renderer, so that clicking feels the same
        invoke(entity, "setInteractionWidth", double.class, 0.51);
        invoke(entity, "setInteractionHeight", float.class, 0.5f);
        return entity;
    }

    private static final Set<String> WARNED = new HashSet<>();

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

    private static void warnNoInteraction(String reason) {
        if (interactionWarned) {
            return;
        }
        interactionWarned = true;
        Log.warning("[Display renderer] Hologram line clicks are disabled because " + reason
                            + ". Use Minecraft 1.19 or newer for click support in the display renderer.");
    }

    static void applyText(Entity entity, @Nullable String text) {
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
        try {
            if (isInvalid(entity)) {
                return;
            }
            Location current = entity.getLocation();
            World world = current.getWorld();
            if (world == null) {
                return;
            }
            double diffX = current.getX() - coordinates.getX();
            double diffY = current.getY() - (coordinates.getY() + yOffset);
            double diffZ = current.getZ() - coordinates.getZ();
            if (diffX * diffX + diffY * diffY + diffZ * diffZ < 1.0E-8) {
                return;
            }
            entity.teleport(new Location(world, coordinates.getX(), coordinates.getY() + yOffset,
                                         coordinates.getZ(), current.getYaw(), 0.0f));
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
            // The entity may already be gone (chunk unloaded), which is fine
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

    private static void makeInvisible(LivingEntity entity) {
        if (invisibilityUnsupported) {
            return;
        }
        try {
            entity.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, MAX_TICKS, 0, true, false, false));
        } catch (Throwable t) {
            if (invisibilityUnsupported) {
                return;
            }
            invisibilityUnsupported = true;
            warnOnce("invisibility", "Hologram entities can't be made invisible, they will be shown as armor stands"
                    + " (this only affects how they look, not the performance)", t);
        }
    }

    private static void protect(Entity entity) {
        try {
            entity.setInvulnerable(true);
            entity.setRemoveWhenFarAway(false);
        } catch (Throwable ignored) {
            // Not critical, the entity just behaves like a regular one
        }

        if (persistenceChecked) {
            if (!persistentSetterMissing) {
                try {
                    Method method = entity.getClass().getMethod("setPersistent", boolean.class);
                    method.invoke(entity, false);
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        persistenceChecked = true;
        try {
            Method method = entity.getClass().getMethod("setPersistent", boolean.class);
            method.invoke(entity, false);
        } catch (Throwable t) {
            persistentSetterMissing = true;
            Log.info("[Display renderer] This server can't keep entities out of the world files, hologram entities"
                             + " will be removed at shutdown and on the next startup instead.");
        }
    }

    private static void markAsOurs(Entity entity, int markerEntityID) {
        NamespacedKey key = DisplayMarker.getKey();
        if (key == null) {
            return;
        }
        try {
            entity.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, markerEntityID);
        } catch (Throwable ignored) {
            // Used for cleanup and click routing, both of which have fallbacks
        }
    }

    private static void invoke(Object target, String methodName, Class<?> parameterType, Object value) {
        try {
            Method method = target.getClass().getMethod(methodName, parameterType);
            method.invoke(target, value);
        } catch (Throwable ignored) {
            // Optional property, e.g. servers older than 1.19
        }
    }

}
