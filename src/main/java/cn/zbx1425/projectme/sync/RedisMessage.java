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
import java.util.Random;
import java.util.UUID;

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
        content.writeUUID(player.getGameProfile().getId());
        content.writeBoolean(isVisible);
        if (isVisible) {
            content.writeUtf(player.getDisplayName().getString());
            content.writeResourceKey(player.level().dimension());
            content.writeVec3(player.position());
            content.writeFloat(player.getYHeadRot());
            content.writeFloat(player.getYRot());
            content.writeFloat(player.getXRot());
        }
        return this;
    }

    private static final UUID MOCK_PLAYER_UUID = UUID.fromString("8f50bdf3-cb09-4e29-ab76-dc1cf9db86a1");
    public static RedisMessage mockPlayerPresence(Vec3 position) {
        RedisMessage result = beginPlayerPresence(1);
        result.content.writeUUID(MOCK_PLAYER_UUID);
        result.content.writeBoolean(true);
        result.content.writeUtf("MalayP");
        result.content.writeResourceKey(Level.OVERWORLD);
        result.content.writeVec3(position);
        result.content.writeFloat(0);
        result.content.writeFloat(0);
        result.content.writeFloat(0);
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
                        Vec3 position = content.readVec3();
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
    }

    public boolean isFromSelf() {
        return initiator == INSTANCE_ID;
    }

    public enum Action {
        PLAYER_PRESENCE, PLAYER_ABSENCE
    }
}
