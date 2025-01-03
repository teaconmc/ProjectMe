package cn.zbx1425.projectme.compat.impl;

import cn.zbx1425.projectme.compat.ICompatibility;
import net.minecraft.server.level.ServerPlayer;

public class VanillaCompatibility implements ICompatibility {

    @Override
    public boolean shouldDisplayPlayer(ServerPlayer player) {
        return !player.isSpectator();
    }
}
