/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.core.display;

import me.filoghost.holographicdisplays.common.PositionCoordinates;
import me.filoghost.holographicdisplays.nms.common.PacketGroup;
import me.filoghost.holographicdisplays.nms.common.entity.ItemNMSPacketEntity;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

final class DisplayItemLine extends DisplayLine implements ItemNMSPacketEntity {

    DisplayItemLine(DisplayNMSManager manager) {
        super(manager);
    }

    @Override
    Type type() {
        return Type.ITEM;
    }

    @Override
    void applyContent(Entity entity, Object content) {
        DisplayEntities.applyItem(entity, (ItemStack) content);
    }

    @Override
    public PacketGroup newSpawnPackets(PositionCoordinates position, ItemStack itemStack) {
        return player -> {
            recordPosition(position);
            recordShow(player, itemStack);
        };
    }

    @Override
    public PacketGroup newChangePackets(ItemStack itemStack) {
        return player -> recordShow(player, itemStack);
    }

    @Override
    public PacketGroup newTeleportPackets(PositionCoordinates position) {
        return player -> recordPosition(position);
    }

    @Override
    public PacketGroup newDestroyPackets() {
        return this::recordHide;
    }

}
