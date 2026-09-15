package cn.zbx1425.projectme.sync.message;

import cn.zbx1425.projectme.ProjectMe;
import cn.zbx1425.projectme.sync.Synchronizer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

public class RedisConnection implements AutoCloseable, Supplier<StatefulRedisConnection<String, ByteBuf>> {

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, ByteBuf> redisConn;
    private final StatefulRedisPubSubConnection<String, ByteBuf> redisSub;

    private final Map<String, Function<ByteBuf, ? extends RedisMessage>> subscribers;
    private final Synchronizer synchronizer;

    private RedisConnection(RedisURI redisUrl, Map<String, Function<ByteBuf, ? extends RedisMessage>> subscribers, Synchronizer synchronizer) {
        redisClient = RedisClient.create(redisUrl);
        redisConn = redisClient.connect(ByteBufCodec.INSTANCE);
        redisSub = redisClient.connectPubSub(ByteBufCodec.INSTANCE);
        redisSub.addListener(new Listener());
        redisSub.sync().subscribe(subscribers.keySet().toArray(String[]::new));
        this.subscribers = subscribers;
        this.synchronizer = synchronizer;
    }

    public static RedisConnection create(String uri, Map<String, Function<ByteBuf, ? extends RedisMessage>> subscribers, Synchronizer synchronizer) {
        return new RedisConnection(RedisURI.create(uri), subscribers, synchronizer);
    }

    @Override
    public StatefulRedisConnection<String, ByteBuf> get() {
        return redisConn;
    }

    @Override
    public void close() {
        redisSub.close();
        redisConn.close();
        redisClient.close();
    }

    public class Listener implements RedisPubSubListener<String, ByteBuf> {
        @Override
        public void message(String channel, ByteBuf rawMessage) {
            try {
                Function<ByteBuf, ? extends RedisMessage> typeConstructor = subscribers.get(channel);
                if (typeConstructor == null) throw new IllegalArgumentException("Unknown channel: " + channel);
                RedisMessage message = typeConstructor.apply(rawMessage);
                message.handle(synchronizer);
            } catch (Exception ex) {
                ProjectMe.LOGGER.error("Redis handler on channel {}", channel, ex);
            }
        }

        @Override public void message(String pattern, String channel, ByteBuf message) { }
        @Override public void subscribed(String channel, long count) { }
        @Override public void psubscribed(String pattern, long count) { }
        @Override public void unsubscribed(String channel, long count) { }
        @Override public void punsubscribed(String pattern, long count) { }
    }

    private static class ByteBufCodec implements RedisCodec<String, ByteBuf> {

        public static ByteBufCodec INSTANCE = new ByteBufCodec();

        @Override
        public String decodeKey(ByteBuffer bytes) {
            return StringCodec.UTF8.decodeKey(bytes);
        }

        @Override
        public ByteBuf decodeValue(ByteBuffer bytes) {
            ByteBuf result = Unpooled.buffer(bytes.remaining());
            result.writeBytes(bytes);
            return result;
        }

        @Override
        public ByteBuffer encodeKey(String key) {
            return StringCodec.UTF8.encodeKey(key);
        }

        @Override
        public ByteBuffer encodeValue(ByteBuf value) {
            return value.nioBuffer();
        }
    }
}
