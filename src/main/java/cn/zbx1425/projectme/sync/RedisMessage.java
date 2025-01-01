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
    public ByteBuf content;

    public RedisMessage(Action action, ByteBuf content) {
        this.initiator = INSTANCE_ID;
        this.action = action;
        this.content = content;
    }

    public RedisMessage(Action action, ByteBuf content, long initiator) {
        this.initiator = initiator;
        this.action = action;
        this.content = content;
    }

    public RedisMessage(ByteBuf src) {
        this.action = Action.values()[src.readByte()];
        this.initiator = src.readLong();
        int length = src.readInt();
        this.content = src.readBytes(length);
    }

    public static RedisMessage playerPresence(ServerPlayer player) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUUID(player.getGameProfile().getId());
        buffer.writeUtf(player.getDisplayName().getString());
        buffer.writeResourceKey(player.level().dimension());
        buffer.writeVec3(player.position());
        buffer.writeFloat(player.getYHeadRot());
        buffer.writeFloat(player.getYRot());
        buffer.writeFloat(player.getXRot());
        return new RedisMessage(Action.PLAYER_PRESENCE, buffer);
    }

    private static final UUID MOCK_PLAYER_UUID = UUID.fromString("8f50bdf3-cb09-4e29-ab76-dc1cf9db86a1");
    public static RedisMessage mockPlayerPresence(Vec3 position) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUUID(MOCK_PLAYER_UUID);
        buffer.writeUtf("MalayP");
        buffer.writeResourceKey(Level.OVERWORLD);
        buffer.writeVec3(position);
        buffer.writeFloat(0);
        buffer.writeFloat(0);
        buffer.writeFloat(0);
        return new RedisMessage(Action.PLAYER_PRESENCE, buffer, 0);
    }

    public static RedisMessage playerLeave(UUID uuid) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUUID(uuid);
        return new RedisMessage(Action.PLAYER_LEAVE, buffer);
    }

    public void publishAsync(StatefulRedisConnection<String, ByteBuf> connection) {
        ByteBuf buffer = Unpooled.buffer(content.readableBytes() + 16);
        buffer.writeByte(action.ordinal());
        buffer.writeLong(initiator);
        buffer.writeInt(content.readableBytes());
        buffer.writeBytes(content);
        connection.async().publish(COMMAND_CHANNEL, buffer);
    }

    public void handle(Synchronizer synchronizer) throws IOException {
        if (isFromSelf()) return;
        switch (action) {
            case PLAYER_PRESENCE: {
                FriendlyByteBuf buffer = new FriendlyByteBuf(content);
                UUID player = buffer.readUUID();
                String playerName = buffer.readUtf();
                ResourceKey<Level> level = buffer.readResourceKey(Registries.DIMENSION);
                Vec3 position = buffer.readVec3();
                float yRotHead = buffer.readFloat();
                float yRotBody = buffer.readFloat();
                float xRot = buffer.readFloat();
                synchronizer.handlePlayerPresence(player, playerName, level, position,
                        yRotHead, yRotBody, xRot);
                break;
            }
            case PLAYER_LEAVE: {
                FriendlyByteBuf buffer = new FriendlyByteBuf(content);
                UUID player = buffer.readUUID();
                synchronizer.handlePlayerLeave(player);
                break;
            }
        }
    }

    public boolean isFromSelf() {
        return initiator == INSTANCE_ID;
    }

    public enum Action {
        PLAYER_PRESENCE, PLAYER_LEAVE
    }
}
