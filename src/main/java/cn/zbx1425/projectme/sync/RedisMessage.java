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

    private static final long INSTANCE_ID = new Random().nextLong();

    public long initiator;
    public Action action;
    public FriendlyByteBuf content;

    private RedisMessage(Action action) {
        this(action, INSTANCE_ID);
    }

    private RedisMessage(Action action, long initiator) {
        this.initiator = initiator;
        this.action = action;
        this.content = new FriendlyByteBuf(Unpooled.buffer());
        content.writeByte(action.ordinal());
        content.writeLong(initiator);
    }

    protected RedisMessage(ByteBuf src) {
        this.content = new FriendlyByteBuf(src);
        this.action = Action.values()[content.readByte()];
        this.initiator = content.readLong();
    }

    public static RedisMessage beginPlayerPresence(int playerCount) {
        RedisMessage result = new RedisMessage(Action.PLAYER_PRESENCE);
        result.content.writeVarInt(playerCount);
        return result;
    }

    public RedisMessage andWithPlayer(ServerPlayer player, boolean isVisible) {
        content.writeUUID(player.getGameProfile().id());
        content.writeBoolean(isVisible);
        if (isVisible) {
            content.writeUtf(player.getDisplayName().getString());
            content.writeResourceKey(player.level().dimension());
            Vec3.STREAM_CODEC.encode(content, player.position());
            content.writeFloat(player.getYHeadRot());
            content.writeFloat(player.getYRot());
            content.writeFloat(player.getXRot());
        }
        return this;
    }

    private static final ArrayDeque<UUID> mockPlayers = new ArrayDeque<>();
    private static final Random random = new Random();

    public static List<RedisMessage> mockPlayerPresence() {
        List<RedisMessage> result = new ArrayList<>();
        if (random.nextInt(100) < 1) {
            if (random.nextBoolean() || mockPlayers.size() >= 20) {
                if (!mockPlayers.isEmpty()) {
                    UUID playerLeft = mockPlayers.pop();
                    RedisMessage absencePacket = playerAbsence(playerLeft);
                    absencePacket.resetInitiator(0);
                    result.add(absencePacket);
                }
            } else {
                mockPlayers.add(UUID.randomUUID());
            }
        }
        RedisMessage presencePacket = beginPlayerPresence(mockPlayers.size());
        for (UUID player : mockPlayers) {
            presencePacket.content.writeUUID(player);
            presencePacket.content.writeBoolean(true);
            presencePacket.content.writeUtf(player.toString().substring(0, 8));
            presencePacket.content.writeResourceKey(Level.OVERWORLD);
            Vec3.STREAM_CODEC.encode(presencePacket.content, new Vec3(random.nextDouble(-10, 10), -60, random.nextDouble(-10, 10)));
            presencePacket.content.writeFloat(0);
            presencePacket.content.writeFloat(0);
            presencePacket.content.writeFloat(0);
        }
        presencePacket.resetInitiator(0);
        result.add(presencePacket);
        return result;
    }

    public static RedisMessage playerAbsence(UUID uuid) {
        RedisMessage result = new RedisMessage(Action.PLAYER_ABSENCE);
        result.content.writeUUID(uuid);
        return result;
    }

    public void publishAsync(StatefulRedisConnection<String, ByteBuf> connection) {
        connection.async().publish(COMMAND_CHANNEL, content);
    }

    public void handle(Synchronizer synchronizer) throws IOException {
        try {
            if (isFromSelf()) return;
            switch (action) {
                case PLAYER_PRESENCE: {
                    int playerCount = content.readVarInt();
                    for (int i = 0; i < playerCount; i++) {
                        UUID player = content.readUUID();
                        boolean isVisible = content.readBoolean();
                        if (isVisible) {
                            String playerName = content.readUtf();
                            ResourceKey<Level> level = content.readResourceKey(Registries.DIMENSION);
                            Vec3 position = Vec3.STREAM_CODEC.decode(content);
                            float yRotHead = content.readFloat();
                            float yRotBody = content.readFloat();
                            float xRot = content.readFloat();
                            synchronizer.handlePlayerPresence(player, playerName, level, position,
                                yRotHead, yRotBody, xRot);
                        } else {
                            synchronizer.handlePlayerAbsence(player);
                        }
                    }
                    break;
                }
                case PLAYER_ABSENCE: {
                    UUID player = content.readUUID();
                    synchronizer.handlePlayerAbsence(player);
                    break;
                }
            }
        } finally {
            content.release();
        }
    }

    public boolean isFromSelf() {
        return initiator == INSTANCE_ID;
    }

    public void resetInitiator(long initiator) {
        this.initiator = initiator;
        int writerIndex = this.content.writerIndex();
        this.content.writerIndex(1);
        this.content.writeLong(initiator);
        this.content.writerIndex(writerIndex);
    }

    public enum Action {
        PLAYER_PRESENCE, PLAYER_ABSENCE
    }
}
