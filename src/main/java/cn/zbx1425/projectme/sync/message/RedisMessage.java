package cn.zbx1425.projectme.sync.message;

import cn.zbx1425.projectme.sync.Synchronizer;
import io.lettuce.core.api.StatefulRedisConnection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

public abstract class RedisMessage {

    protected static String SELF_PEER_ID = UUID.randomUUID().toString();

    public static void setSelfPeerId(String selfPeerId) {
        SELF_PEER_ID = selfPeerId;
    }

    public String peerId;
    public FriendlyByteBuf content;

    protected RedisMessage(String peerId) {
        this.peerId = peerId;
        this.content = new FriendlyByteBuf(Unpooled.buffer());
        content.writeUtf(peerId);
    }

    protected RedisMessage(ByteBuf src) {
        this.content = new FriendlyByteBuf(src);
        this.peerId = content.readUtf();
    }

    public void publishAsync(StatefulRedisConnection<String, ByteBuf> connection) {
        publishAsync(connection, channel());
    }

    public void publishAsync(StatefulRedisConnection<String, ByteBuf> connection, String channel) {
        connection.async().publish(channel, content);
    }

    public abstract void handle(Synchronizer synchronizer);

    public abstract String channel();

    public boolean isFromSelf() {
        return SELF_PEER_ID.equals(peerId);
    }
}
