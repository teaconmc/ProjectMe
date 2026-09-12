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

    private RedisMessage() {
        this(PEER_ID);
    }

    protected RedisMessage(ByteBuf src) {
        this.content = new FriendlyByteBuf(src);
        this.peerId = content.readUtf();
    }

    public static RedisMessage beginPlayerPresence(int playerCount) {
        RedisMessage result = new RedisMessage();
        result.content.writeVarInt(playerCount);
        return result;
    }

    public RedisMessage andWithPlayer(ServerPlayer player) {
        content.writeUUID(player.getGameProfile().id());
        content.writeUtf(player.getDisplayName().getString());
        content.writeResourceKey(player.level().dimension());
        Vec3.STREAM_CODEC.encode(content, player.position());
        content.writeFloat(player.getYHeadRot());
        content.writeFloat(player.getYRot());
        content.writeFloat(player.getXRot());
        return this;
    }

    private static final ArrayDeque<UUID> mockPlayers = new ArrayDeque<>();
    private static final Random random = new Random();

    public static RedisMessage mockPlayerPresence() {
        if (random.nextInt(mockPlayers.size() < 10 ? 10 : 100) < 1) {
            if (random.nextInt(3) < 1 || mockPlayers.size() >= 20) {
                if (!mockPlayers.isEmpty()) {
                    mockPlayers.pop();
                }
            } else {
                mockPlayers.add(UUID.randomUUID());
            }
        }
        RedisMessage presencePacket = new RedisMessage("mock-peer");
        presencePacket.content.writeVarInt(mockPlayers.size());
        for (UUID player : mockPlayers) {
            presencePacket.content.writeUUID(player);
            presencePacket.content.writeUtf(player.toString().substring(0, 8));
            presencePacket.content.writeResourceKey(Level.OVERWORLD);
            Vec3.STREAM_CODEC.encode(presencePacket.content, new Vec3(random.nextDouble(-10, 10), -60, random.nextDouble(-10, 10)));
            presencePacket.content.writeFloat(0);
            presencePacket.content.writeFloat(0);
            presencePacket.content.writeFloat(0);
        }
        return presencePacket;
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
                players.put(uuid, new Synchronizer.RemotePlayerData(playerName, level, position, yRotHead, yRotBody, xRot));
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
