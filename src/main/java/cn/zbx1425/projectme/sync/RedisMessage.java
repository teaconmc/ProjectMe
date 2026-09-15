package cn.zbx1425.projectme.sync;

import io.lettuce.core.api.StatefulRedisConnection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.*;

public class RedisMessage {

    public static final String COMMAND_CHANNEL = "PROJECT_ME_COMMAND_CHANNEL";

    private static String PEER_ID = UUID.randomUUID().toString();

    public static void setPeerId(String peerId) {
        PEER_ID = peerId;
    }

    public String peerId;
    public FriendlyByteBuf content;

    private RedisMessage(String peerId) {
        this.peerId = peerId;
        this.content = new FriendlyByteBuf(Unpooled.buffer());
        content.writeUtf(peerId);
    }

    protected RedisMessage(ByteBuf src) {
        this.content = new FriendlyByteBuf(src);
        this.peerId = content.readUtf();
    }

    public static RedisMessage beginPlayerPresence(int playerCount) {
        return beginPlayerPresence(PEER_ID, playerCount);
    }

    public static RedisMessage beginPlayerPresence(String peerId, int playerCount) {
        RedisMessage result = new RedisMessage(peerId);
        result.content.writeVarInt(playerCount);
        return result;
    }

    public RedisMessage andWithPlayer(ServerPlayer player, boolean visible) {
        return andWithPlayerData(player.getGameProfile().id(), player.getGameProfile().name(),
                player.level().dimension(), player.position(),
                player.getYHeadRot(), player.getYRot(), player.getXRot(), visible);
    }

    public RedisMessage andWithPlayerData(UUID uuid, String name, ResourceKey<Level> level,
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

    public void publishAsync(StatefulRedisConnection<String, ByteBuf> connection) {
        connection.async().publish(COMMAND_CHANNEL, content);
    }

    public void handle(Synchronizer synchronizer) throws IOException {
        try {
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
        } finally {
            content.release();
        }
    }

    public boolean isFromSelf() {
        return PEER_ID.equals(peerId);
    }
}
