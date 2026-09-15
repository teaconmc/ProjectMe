package cn.zbx1425.projectme.sync;

import cn.zbx1425.projectme.ProjectMe;
import cn.zbx1425.projectme.entity.EntityProjection;
import com.mojang.authlib.GameProfile;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

public class Synchronizer implements AutoCloseable {

    public record RemotePlayerData(String name, ResourceKey<Level> level, Vec3 position,
                                   float yRotHead, float yRotBody, float xRot) {}

    static class PeerState {
        long lastUpdateTick;
        final Map<UUID, RemotePlayerData> players = new HashMap<>();
    }

    private final MinecraftServer server;
    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, ByteBuf> redisConn;
    private final StatefulRedisPubSubConnection<String, ByteBuf> redisSub;

    private final Map<UUID, EntityProjection> currentProjections = new HashMap<>();
    private final Map<String, PeerState> peerStates = new HashMap<>();
    private final Map<UUID, GameProfile> currentFakeTabEntries = new HashMap<>();

    private static final String REDIS_PROFILES_KEY = "projectme:profiles";
    private final Set<UUID> profilesWritten = new HashSet<>();
    private final Map<UUID, GameProfile> knownProfiles = new HashMap<>();
    private final Map<UUID, Long> profileFetchCooldown = new HashMap<>();

    public Synchronizer(String URI, MinecraftServer server) {
        redisClient = RedisClient.create(URI);
        redisConn = redisClient.connect(ByteBufCodec.INSTANCE);
        redisSub = redisClient.connectPubSub(ByteBufCodec.INSTANCE);
        redisSub.addListener(new Listener());
        redisSub.sync().subscribe(RedisMessage.COMMAND_CHANNEL);
        this.server = server;
    }

    public void notifyPlayerPresence(List<ServerPlayer> players) {
        List<ServerPlayer> visiblePlayers = players.stream()
                .filter(ProjectMe::computePlayerVisibility)
                .toList();

        // Write profiles to Redis Hash for new players
        for (ServerPlayer p : visiblePlayers) {
            if (profilesWritten.add(p.getGameProfile().id())) {
                ByteBuf buf = Unpooled.buffer();
                try {
                    ByteBufCodecs.GAME_PROFILE.encode(buf, p.getGameProfile());
                    redisConn.async().hset(REDIS_PROFILES_KEY, p.getGameProfile().id().toString(), buf);
                } catch (Exception e) {
                    buf.release();
                    ProjectMe.LOGGER.warn("Failed to write profile for {}", p.getGameProfile().id(), e);
                }
            }
        }
        Set<UUID> visibleUuids = new HashSet<>();
        for (ServerPlayer p : visiblePlayers) {
            visibleUuids.add(p.getGameProfile().id());
        }
        profilesWritten.retainAll(visibleUuids);

        RedisMessage msg = RedisMessage.beginPlayerPresence(visiblePlayers.size());
        for (ServerPlayer p : visiblePlayers) {
            msg.andWithPlayer(p);
        }
        msg.publishAsync(redisConn);

        server.execute(() -> {
            for (ServerPlayer p : players) {
                UUID uuid = p.getGameProfile().id();
                EntityProjection proj = currentProjections.remove(uuid);
                if (proj != null) proj.discard();
                currentFakeTabEntries.remove(uuid);
            }
        });
    }

    public void mockPlayerPresence() {
        RedisMessage message = RedisMessage.mockPlayerPresence();
        message.publishAsync(redisConn);
    }

    public void handlePeerPresence(String peerId, Map<UUID, RemotePlayerData> newPlayers) {
        server.execute(() -> {
            PeerState state = peerStates.computeIfAbsent(peerId, k -> new PeerState());
            state.lastUpdateTick = server.getTickCount();
            state.players.clear();
            state.players.putAll(newPlayers);

            reconcileGlobalState();
        });
    }

    public void checkPeerTimeouts(long currentTick) {
        int timeout = ProjectMe.CONFIG.peerTimeout.value;
        boolean anyRemoved = peerStates.entrySet().removeIf(
                entry -> currentTick - entry.getValue().lastUpdateTick > timeout
        );
        if (anyRemoved) {
            reconcileGlobalState();
        }
    }

    private void reconcileGlobalState() {
        Map<UUID, RemotePlayerData> allRemote = new HashMap<>();
        for (PeerState ps : peerStates.values()) {
            allRemote.putAll(ps.players);
        }

        // Trigger async profile fetches for new remote UUIDs
        long currentTick = server.getTickCount();
        for (UUID uuid : allRemote.keySet()) {
            if (!knownProfiles.containsKey(uuid)
                    && server.getPlayerList().getPlayer(uuid) == null) {
                Long cooldown = profileFetchCooldown.get(uuid);
                if (cooldown == null || currentTick >= cooldown) {
                    fetchProfileFromRedis(uuid);
                    profileFetchCooldown.put(uuid, currentTick + 20);
                }
            }
        }
        knownProfiles.keySet().retainAll(allRemote.keySet());
        profileFetchCooldown.keySet().retainAll(allRemote.keySet());

        // Only process players whose profiles have been resolved
        Map<UUID, RemotePlayerData> readyRemote = new HashMap<>();
        for (var entry : allRemote.entrySet()) {
            if (knownProfiles.containsKey(entry.getKey())) {
                readyRemote.put(entry.getKey(), entry.getValue());
            }
        }

        reconcileProjections(readyRemote);
        reconcileFakeTabEntries(readyRemote);
    }

    private void fetchProfileFromRedis(UUID uuid) {
        redisConn.async().hget(REDIS_PROFILES_KEY, uuid.toString())
                .whenComplete((profileBytes, ex) -> {
                    server.execute(() -> {
                        if (profileBytes != null && ex == null) {
                            try {
                                GameProfile profile = ByteBufCodecs.GAME_PROFILE.decode(profileBytes);
                                knownProfiles.put(uuid, profile);
                                reconcileGlobalState();
                            } finally {
                                profileBytes.release();
                            }
                        }
                    });
                });
    }

    private void reconcileProjections(Map<UUID, RemotePlayerData> allRemote) {
        currentProjections.entrySet().removeIf(entry -> {
            UUID uuid = entry.getKey();
            if (!allRemote.containsKey(uuid) || server.getPlayerList().getPlayer(uuid) != null) {
                entry.getValue().discard();
                return true;
            }
            return false;
        });

        for (var entry : allRemote.entrySet()) {
            UUID uuid = entry.getKey();
            RemotePlayerData data = entry.getValue();

            if (server.getPlayerList().getPlayer(uuid) != null) continue;

            EntityProjection currentEntity = currentProjections.get(uuid);
            if (currentEntity == null || currentEntity.isRemoved()
                    || !currentEntity.level().dimension().equals(data.level())) {
                if (currentEntity != null) {
                    currentEntity.discard();
                    currentProjections.remove(uuid);
                }
                ServerLevel targetLevel = server.getLevel(data.level());
                if (targetLevel == null) continue;
                if (!targetLevel.areEntitiesLoaded(ChunkPos.containing(BlockPos.containing(data.position())).pack())) continue;
                EntityProjection newEntity = ProjectMe.ENTITY_PROJECTION.get().create(targetLevel, EntitySpawnReason.COMMAND);
                if (newEntity == null) continue;
                newEntity.setProjectingPlayer(uuid);
                newEntity.setNoGravity(true);
                newEntity.setCustomName(Component.literal(data.name()));
                newEntity.setPos(data.position());
                newEntity.setYRot(data.yRotHead());
                newEntity.setXRot(data.xRot());
                newEntity.setYHeadRot(data.yRotHead());
                newEntity.setYBodyRot(data.yRotBody());
                if (!targetLevel.addFreshEntity(newEntity)) {
                    ProjectMe.LOGGER.warn("Cannot add entity, player: {}", uuid);
                    continue;
                }
                currentProjections.put(uuid, newEntity);
            } else {
                currentEntity.moveOrInterpolateTo(data.position(), data.yRotHead(), data.xRot());
                currentEntity.setYHeadRot(data.yRotHead());
                currentEntity.setYBodyRot(data.yRotBody());
            }
        }
    }

    private void reconcileFakeTabEntries(Map<UUID, RemotePlayerData> allRemote) {
        Map<UUID, GameProfile> desired = new HashMap<>();
        for (var entry : allRemote.entrySet()) {
            if (server.getPlayerList().getPlayer(entry.getKey()) == null) {
                GameProfile profile = knownProfiles.get(entry.getKey());
                if (profile != null) {
                    desired.put(entry.getKey(), profile);
                }
            }
        }

        Map<UUID, GameProfile> toSend = new HashMap<>();
        for (var entry : desired.entrySet()) {
            GameProfile current = currentFakeTabEntries.get(entry.getKey());
            if (current == null || !current.equals(entry.getValue())) {
                toSend.put(entry.getKey(), entry.getValue());
            }
        }

        // Only send REMOVE for UUIDs that are no longer desired AND not a local player.
        // If the UUID belongs to a player who just joined locally, the server already sent
        // a real ADD packet which overwrites our fake entry; sending REMOVE here would
        // incorrectly destroy the real entry.
        Set<UUID> toRemove = new HashSet<>(currentFakeTabEntries.keySet());
        toRemove.removeAll(desired.keySet());
        toRemove.removeIf(uuid -> server.getPlayerList().getPlayer(uuid) != null);

        // Entries that changed profile need REMOVE + re-ADD (client putIfAbsent ignores second ADD)
        Set<UUID> toUpdate = new HashSet<>();
        for (UUID uuid : toSend.keySet()) {
            if (currentFakeTabEntries.containsKey(uuid)) {
                toUpdate.add(uuid);
            }
        }

        if (!toRemove.isEmpty() || !toUpdate.isEmpty()) {
            Set<UUID> allRemoveIds = new HashSet<>(toRemove);
            allRemoveIds.addAll(toUpdate);
            broadcastPacket(new ClientboundPlayerInfoRemovePacket(new ArrayList<>(allRemoveIds)));
        }

        if (!toSend.isEmpty()) {
            broadcastPacket(createFakePlayerInfoPacket(toSend));
        }

        currentFakeTabEntries.clear();
        currentFakeTabEntries.putAll(desired);
    }

    private ClientboundPlayerInfoUpdatePacket createFakePlayerInfoPacket(Map<UUID, GameProfile> entries) {
        EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions = EnumSet.of(
                ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME
        );
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), server.registryAccess()
        );
        try {
            buf.writeEnumSet(actions, ClientboundPlayerInfoUpdatePacket.Action.class);
            buf.writeVarInt(entries.size());
            for (var entry : entries.entrySet()) {
                buf.writeUUID(entry.getKey());
                // ADD_PLAYER
                GameProfile profile = entry.getValue();
                ByteBufCodecs.PLAYER_NAME.encode(buf, profile.name());
                ByteBufCodecs.GAME_PROFILE_PROPERTIES.encode(buf, profile.properties());
                // UPDATE_GAME_MODE
                buf.writeVarInt(GameType.ADVENTURE.getId());
                // UPDATE_LISTED
                buf.writeBoolean(true);
                // UPDATE_LATENCY
                buf.writeVarInt(-1);
                // UPDATE_DISPLAY_NAME
                FriendlyByteBuf.writeNullable(buf,
                        Component.literal(profile.name()).withStyle(ChatFormatting.ITALIC).withStyle(ChatFormatting.DARK_GRAY),
                        ComponentSerialization.TRUSTED_STREAM_CODEC);
            }
            return ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }

    public void onLocalPlayerJoin(ServerPlayer player) {
        UUID uuid = player.getGameProfile().id();

        EntityProjection proj = currentProjections.remove(uuid);
        if (proj != null) proj.discard();

        boolean hadFakeEntry = currentFakeTabEntries.containsKey(uuid);
        currentFakeTabEntries.remove(uuid);
        knownProfiles.remove(uuid);
        profileFetchCooldown.remove(uuid);

        if (hadFakeEntry) {
            // Client putIfAbsent prevented vanilla's ADD_PLAYER from replacing our fake entry.
            // Send REMOVE then re-ADD with the real player's full profile.
            Packet<?> removePacket = new ClientboundPlayerInfoRemovePacket(List.of(uuid));
            Packet<?> addPacket = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player));
            for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                if (!other.getUUID().equals(uuid)) {
                    other.connection.send(removePacket);
                    other.connection.send(addPacket);
                }
            }
        }

        sendAllFakeTabEntriesToPlayer(player);
    }

    public void sendAllFakeTabEntriesToPlayer(ServerPlayer player) {
        if (currentFakeTabEntries.isEmpty()) return;
        Map<UUID, GameProfile> toSend = new HashMap<>(currentFakeTabEntries);
        toSend.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) != null);
        if (toSend.isEmpty()) return;
        player.connection.send(createFakePlayerInfoPacket(toSend));
    }

    public void onLocalPlayerLeave(UUID playerUuid) {
        currentFakeTabEntries.remove(playerUuid);
        knownProfiles.remove(playerUuid);
        profileFetchCooldown.remove(playerUuid);

        // Remove profile from Redis Hash
        profilesWritten.remove(playerUuid);
        redisConn.async().hdel(REDIS_PROFILES_KEY, playerUuid.toString());

        server.execute(this::reconcileGlobalState);
    }

    private void broadcastPacket(Packet<?> packet) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(packet);
        }
    }

    public MinecraftServer getServer() {
        return server;
    }

    public int getPeerCount() {
        return peerStates.size();
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

        @Override public void message(String pattern, String channel, ByteBuf message) { }
        @Override public void subscribed(String channel, long count) { }
        @Override public void psubscribed(String pattern, long count) { }
        @Override public void unsubscribed(String channel, long count) { }
        @Override public void punsubscribed(String pattern, long count) { }
    }

    // ── Redis codec ───────────────────────────────────────────────────────

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
