package cn.zbx1425.projectme.sync;

import cn.zbx1425.projectme.ProjectMe;
import cn.zbx1425.projectme.entity.EntityProjection;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Synchronizer implements AutoCloseable {

    private final MinecraftServer server;
    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, ByteBuf> redisConn;
    private final StatefulRedisPubSubConnection<String, ByteBuf> redisSub;

    private final Map<UUID, EntityProjection> currentProjections = new HashMap<>();

    public static final String HMAP_ALL_KEY = "SSHARD_DATA_ALL";

    public Synchronizer(String URI, MinecraftServer server) {
        redisClient = RedisClient.create(URI);
        redisConn = redisClient.connect(ByteBufCodec.INSTANCE);
        redisSub = redisClient.connectPubSub(ByteBufCodec.INSTANCE);
        redisSub.addListener(new Listener());
        redisSub.sync().subscribe(RedisMessage.COMMAND_CHANNEL);
        this.server = server;
    }

    public void notifyPlayerPresence(List<ServerPlayer> player) {
        RedisMessage playerPresence = RedisMessage.beginPlayerPresence(player.size());
        for (ServerPlayer p : player) {
            boolean v1 = ProjectMe.computePlayerVisibility(p);
            playerPresence.andWithPlayer(p, v1);
        }
        playerPresence.publishAsync(redisConn);
    }

    public void mockPlayerPresence(Vec3 position) {
        RedisMessage.mockPlayerPresence(position).publishAsync(redisConn);
    }

    protected void handlePlayerPresence(UUID player, String playerName, ResourceKey<Level> level, Vec3 position,
                                        float yRotHead, float yRotBody, float xRot) {
        server.execute(() -> {
            EntityProjection currentEntity = currentProjections.get(player);
            if (server.getPlayerList().getPlayer(player) != null) {
                if (currentEntity != null) {
                    currentEntity.discard();
                    currentProjections.remove(player);
                }
                return;
            }
            if (currentEntity == null || currentEntity.isRemoved()
                    || !currentEntity.level().dimension().equals(level)) {
                if (currentEntity != null) {
                    currentEntity.discard();
                    currentProjections.remove(player);
                }
                ServerLevel targetLevel = server.getLevel(level);
                if (targetLevel == null) return;
                if (!targetLevel.isLoaded(new BlockPos((int) position.x, (int) position.y, (int) position.z))) return;
                CompoundTag entityInitData = new CompoundTag();
                entityInitData.putString("id", ProjectMe.id("projection").toString());
                entityInitData.putUUID("projectingPlayer", player);
                entityInitData.putBoolean("NoGravity", true);
                entityInitData.putString("CustomName", playerName);
                EntityProjection entity = (EntityProjection) EntityType.loadEntityRecursive(entityInitData, targetLevel, newEntity -> {
                    newEntity.moveTo(position, yRotBody, xRot);
                    return newEntity;
                });
                if (entity == null) return;
                if (!targetLevel.tryAddFreshEntityWithPassengers(entity)) return;
                currentProjections.put(player, entity);
            } else {
                currentEntity.moveTo(position, yRotBody, xRot);
                currentEntity.setYHeadRot(yRotHead);
            }
        });
    }

    public void notifyPlayerAbsence(UUID player) {
        RedisMessage.playerAbsence(player).publishAsync(redisConn);
    }

    protected void handlePlayerAbsence(UUID player) {
        server.execute(() -> {
            EntityProjection currentEntity = currentProjections.get(player);
            if (currentEntity != null) {
                currentEntity.discard();
                currentProjections.remove(player);
            }
        });
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
            RedisMessage message = new RedisMessage(rawMessage);
            try {
                message.handle(Synchronizer.this);
            } catch (IOException ex) {
                ProjectMe.LOGGER.error("Redis handler", ex);
            }
        }

        @Override
        public void message(String pattern, String channel, ByteBuf message) { }

        @Override
        public void subscribed(String channel, long count) { }

        @Override
        public void psubscribed(String pattern, long count) { }

        @Override
        public void unsubscribed(String channel, long count) { }

        @Override
        public void punsubscribed(String pattern, long count) { }
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
