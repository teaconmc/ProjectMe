package cn.zbx1425.projectme.compat.impl;

import cn.zbx1425.projectme.compat.ICompatibility;
import mtr.data.RailwayData;
import net.minecraft.server.level.ServerPlayer;

public class MTRCompatibility implements ICompatibility {
    @Override
    public boolean shouldDisplayPlayer(ServerPlayer player) {
        return RailwayData.getInstance(player.level()).railwayDataCoolDownModule.canRide(player);
    }
}
