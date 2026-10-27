/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.core.display;

import me.filoghost.holographicdisplays.common.PositionCoordinates;
import me.filoghost.holographicdisplays.core.CoreGlobalConfig;
import me.filoghost.holographicdisplays.nms.common.IndividualTextPacketGroup;
import me.filoghost.holographicdisplays.nms.common.PacketGroup;
import me.filoghost.holographicdisplays.nms.common.entity.TextNMSPacketEntity;
import org.bukkit.entity.Entity;

final class DisplayTextLine extends DisplayLine implements TextNMSPacketEntity {

    DisplayTextLine(DisplayNMSManager manager) {
        super(manager);
    }

    @Override
    Type type() {
        return Type.TEXT;
    }

    @Override
    double yOffset() {
        return CoreGlobalConfig.displayTextYOffset;
    }

    @Override
    void applyContent(Entity entity, Object content) {
        DisplayEntities.applyText(entity, (String) content);
    }

    @Override
    public PacketGroup newSpawnPackets(PositionCoordinates position, String text) {
        return player -> {
            recordPosition(position);
            recordShow(player, text);
        };
    }

    @Override
    public IndividualTextPacketGroup newSpawnPackets(PositionCoordinates position) {
        return IndividualTextPacketGroup.of(text -> player -> {
            recordPosition(position);
            recordShow(player, text);
        });
    }

    @Override
    public PacketGroup newChangePackets(String text) {
        return player -> recordShow(player, text);
    }

    @Override
    public IndividualTextPacketGroup newChangePackets() {
        return IndividualTextPacketGroup.of(text -> player -> recordShow(player, text));
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
