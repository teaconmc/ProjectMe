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
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
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

    public Synchronizer(String URI, MinecraftServer server) {
        redisClient = RedisClient.create(URI);
        redisConn = redisClient.connect(ByteBufCodec.INSTANCE);
        redisSub = redisClient.connectPubSub(ByteBufCodec.INSTANCE);
        redisSub.addListener(new Listener());
        redisSub.sync().subscribe(RedisMessage.COMMAND_CHANNEL);
        this.server = server;
    }

    public void notifyPlayerPresence(List<ServerPlayer> players) {
        RedisMessage playerPresence = RedisMessage.beginPlayerPresence(players.size());
        for (ServerPlayer p : players) {
            boolean v1 = ProjectMe.computePlayerVisibility(p);
            playerPresence.andWithPlayer(p, v1);
        }
        playerPresence.publishAsync(redisConn);

        server.execute(() -> {
            for (ServerPlayer p : players) {
                EntityProjection currentEntity = currentProjections.get(p.getGameProfile().id());
                if (currentEntity != null) {
                    currentEntity.discard();
                    currentProjections.remove(p.getGameProfile().id());
                }
            }
        });
    }

    public void mockPlayerPresence() {
        for (RedisMessage message : RedisMessage.mockPlayerPresence()) {
            message.publishAsync(redisConn);
        }
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
                if (!targetLevel.areEntitiesLoaded(ChunkPos.containing(BlockPos.containing(position)).pack())) return;
                EntityProjection newEntity = ProjectMe.ENTITY_PROJECTION.get().create(targetLevel, EntitySpawnReason.COMMAND);
                if (newEntity == null) return;
                newEntity.setProjectingPlayer(player);
                newEntity.setNoGravity(true);
                newEntity.setCustomName(Component.literal(playerName));
                newEntity.setPos(position);
                newEntity.setYRot(yRotHead);
                newEntity.setXRot(xRot);
                newEntity.setYHeadRot(yRotHead);
                newEntity.setYBodyRot(yRotBody);
                if (!targetLevel.addFreshEntity(newEntity)) {
                    ProjectMe.LOGGER.warn("Cannot add entity, player: {}", player);
                    return;
                }
                currentProjections.put(player, newEntity);
            } else {
                currentEntity.moveOrInterpolateTo(position, yRotHead, xRot);
                currentEntity.setYHeadRot(yRotHead);
                currentEntity.setYBodyRot(yRotBody);
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

    public MinecraftServer getServer() {
        return server;
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
