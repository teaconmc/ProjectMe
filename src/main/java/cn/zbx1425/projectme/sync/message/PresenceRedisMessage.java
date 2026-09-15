package cn.zbx1425.projectme.sync.message;

import cn.zbx1425.projectme.sync.Synchronizer;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class PresenceRedisMessage extends RedisMessage {

    public static final String CHANNEL = "projectme:presence";

    private PresenceRedisMessage(String peerId) {
        super(peerId);
    }

    public PresenceRedisMessage(ByteBuf src) {
        super(src);
    }

    public static PresenceRedisMessage beginPlayerPresence(int playerCount) {
        return beginPlayerPresence(SELF_PEER_ID, playerCount);
    }

    public static PresenceRedisMessage beginPlayerPresence(String peerId, int playerCount) {
        PresenceRedisMessage result = new PresenceRedisMessage(peerId);
        result.content.writeVarInt(playerCount);
        return result;
    }

    public PresenceRedisMessage andWithPlayer(ServerPlayer player, boolean visible) {
        return andWithPlayerData(player.getGameProfile().id(), player.getGameProfile().name(),
                player.level().dimension(), player.position(),
                player.getYHeadRot(), player.getYRot(), player.getXRot(), visible);
    }

    public PresenceRedisMessage andWithPlayerData(UUID uuid, String name, ResourceKey<Level> level,
                                                  Vec3 position, float yRotHead, float yRotBody,
                                                  float xRot, boolean visible) {
        content.writeUUID(uuid);
        content.writeUtf(name);
        content.writeResourceKey(level);
        Vec3.STREAM_CODEC.encode(content, position);
        content.writeFloat(yRotHead);
        content.writeFloat(yRotBody);
        content.writeFloat(xRot);
        content.writeBoolean(visible);
        return this;
    }

    @Override
    public void handle(Synchronizer synchronizer) {
        if (isFromSelf()) return;
        int playerCount = content.readVarInt();
        Map<UUID, Synchronizer.RemotePlayerData> players = new HashMap<>();
        for (int i = 0; i < playerCount; i++) {
            UUID uuid = content.readUUID();
            String playerName = content.readUtf();
            ResourceKey<Level> level = content.readResourceKey(Registries.DIMENSION);
            Vec3 position = Vec3.STREAM_CODEC.decode(content);
            float yRotHead = content.readFloat();
            float yRotBody = content.readFloat();
            float xRot = content.readFloat();
            boolean visible = content.readBoolean();
            players.put(uuid, new Synchronizer.RemotePlayerData(playerName, level, position, yRotHead, yRotBody, xRot, visible));
        }
        synchronizer.handlePeerPresence(peerId, players);
    }

    @Override
    public String channel() {
        return CHANNEL;
    }
}
