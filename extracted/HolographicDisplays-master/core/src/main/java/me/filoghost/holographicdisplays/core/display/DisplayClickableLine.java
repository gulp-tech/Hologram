/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.core.display;

import me.filoghost.holographicdisplays.common.PositionCoordinates;
import me.filoghost.holographicdisplays.nms.common.EntityID;
import me.filoghost.holographicdisplays.nms.common.PacketGroup;
import me.filoghost.holographicdisplays.nms.common.entity.ClickableNMSPacketEntity;
import org.bukkit.entity.Entity;

/**
 * The hitbox of a clickable line. In the packet renderer this is an invisible slime, here it's an interaction
 * entity, which the server already uses for exactly this purpose and which needs no protocol knowledge.
 */
final class DisplayClickableLine extends DisplayLine implements ClickableNMSPacketEntity {

    private final int entityID = DisplayMarker.newEntityID();
    private final EntityID entityIDWrapper = new EntityID(() -> entityID);

    DisplayClickableLine(DisplayNMSManager manager) {
        super(manager);
    }

    @Override
    Type type() {
        return Type.CLICKABLE;
    }

    @Override
    int markerEntityID() {
        return entityID;
    }

    @Override
    void onEntityCreated(Entity entity, Object content) {
        getManager().registerClickEntity(entity, entityID);
    }

    @Override
    void onEntityRemoved(Entity entity) {
        getManager().unregisterClickEntity(entity);
    }

    @Override
    public EntityID getID() {
        return entityIDWrapper;
    }

    @Override
    public PacketGroup newSpawnPackets(PositionCoordinates position) {
        return player -> {
            recordPosition(position);
            recordShow(player, STATIC_CONTENT);
        };
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
