package cn.zbx1425.projectme.compat;

import net.minecraft.server.level.ServerPlayer;

public interface ICompatibility {
    boolean shouldDisplayPlayer(ServerPlayer player);
}
