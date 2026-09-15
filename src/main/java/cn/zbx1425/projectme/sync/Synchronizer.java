package cn.zbx1425.projectme.sync;

import cn.zbx1425.projectme.ProjectMe;
import cn.zbx1425.projectme.entity.EntityProjection;
import cn.zbx1425.projectme.sync.message.ChatRedisMessage;
import cn.zbx1425.projectme.sync.message.PresenceRedisMessage;
import cn.zbx1425.projectme.sync.message.RedisConnection;
import cn.zbx1425.projectme.sync.message.RedisMessage;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.OutgoingChatMessage;
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

import java.util.*;
import java.util.function.Function;

public class Synchronizer implements AutoCloseable {

    public record RemotePlayerData(String name, ResourceKey<Level> level, Vec3 position,
                                   float yRotHead, float yRotBody, float xRot,
                                   boolean visible) {}

    static class PeerState {
        long lastUpdateTick;
        final Map<UUID, RemotePlayerData> players = new HashMap<>();
    }

    private final RedisConnection redisConn;
    private final MinecraftServer server;

    private final Map<UUID, EntityProjection> currentProjections = new HashMap<>();
    private final Map<String, PeerState> peerStates = new HashMap<>();
    private final Map<UUID, GameProfile> currentFakeTabEntries = new HashMap<>();

    private final RemoteProfileStore remoteProfiles;
    private final MockPeerSimulator mockSimulator;

    private static final Map<String, Function<ByteBuf, ? extends RedisMessage>> MESSAGE_TYPES = Map.of(
        PresenceRedisMessage.CHANNEL, PresenceRedisMessage::new,
        ChatRedisMessage.CHANNEL, ChatRedisMessage::new
    );

    public Synchronizer(String URI, MinecraftServer server) {
        this.redisConn = RedisConnection.create(URI, MESSAGE_TYPES, this);
        this.server = server;
        this.remoteProfiles = new RemoteProfileStore(redisConn.get(), server::execute);
        this.mockSimulator = new MockPeerSimulator(remoteProfiles, redisConn.get());
    }

    public void notifyPlayerPresence(List<ServerPlayer> players) {
        PresenceRedisMessage msg = PresenceRedisMessage.beginPlayerPresence(players.size());
        for (ServerPlayer p : players) {
            msg.andWithPlayer(p, ProjectMe.computePlayerVisibility(p));
        }
        msg.publishAsync(redisConn.get());

        // "Defensive" check
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
        mockSimulator.tick();
    }

    public void handlePeerPresence(String peerId, Map<UUID, RemotePlayerData> newPlayers) {
        server.execute(() -> {
            PeerState state = peerStates.computeIfAbsent(peerId, k -> new PeerState());
            state.lastUpdateTick = server.getTickCount();
            state.players.clear();
            state.players.putAll(newPlayers);
        });
    }

    public void tick(long currentTick) {
        int timeout = ProjectMe.CONFIG.peerTimeout.value;
        peerStates.entrySet().removeIf(entry -> 
            currentTick - entry.getValue().lastUpdateTick > timeout
        );
        updateGlobalState();
    }

    private void updateGlobalState() {
        Map<UUID, RemotePlayerData> allRemotes = new HashMap<>();
        for (PeerState ps : peerStates.values()) {
            allRemotes.putAll(ps.players);
        }

        for (UUID uuid : allRemotes.keySet()) {
            if (server.getPlayerList().getPlayer(uuid) == null) {
                remoteProfiles.fetchProfileIfNeeded(uuid);
            }
        }
        remoteProfiles.retainOnly(allRemotes.keySet());

        Map<UUID, RemotePlayerData> readyRemotes = new HashMap<>();
        for (var entry : allRemotes.entrySet()) {
            if (remoteProfiles.hasProfile(entry.getKey())) {
                readyRemotes.put(entry.getKey(), entry.getValue());
            }
        }

        updateFakeTabEntries(readyRemotes);
        updateProjections(readyRemotes);
    }

    private void updateProjections(Map<UUID, RemotePlayerData> remotes) {
        currentProjections.entrySet().removeIf(entry -> {
            UUID uuid = entry.getKey();
            RemotePlayerData data = remotes.get(uuid);
            if (data == null || !data.visible()
                    || server.getPlayerList().getPlayer(uuid) != null) {
                entry.getValue().discard();
                return true;
            }
            return false;
        });

        for (var entry : remotes.entrySet()) {
            UUID uuid = entry.getKey();
            RemotePlayerData data = entry.getValue();

            if (server.getPlayerList().getPlayer(uuid) != null) continue;
            if (!data.visible()) continue;

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

    private void updateFakeTabEntries(Map<UUID, RemotePlayerData> remotes) {
        Map<UUID, GameProfile> desired = new HashMap<>();
        for (var entry : remotes.entrySet()) {
            if (server.getPlayerList().getPlayer(entry.getKey()) == null) {
                GameProfile profile = remoteProfiles.getProfile(entry.getKey());
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

        remoteProfiles.writeProfile(uuid, player.getGameProfile());

        EntityProjection proj = currentProjections.remove(uuid);
        if (proj != null) proj.discard();

        boolean hadFakeEntry = currentFakeTabEntries.containsKey(uuid);
        currentFakeTabEntries.remove(uuid);
        remoteProfiles.remove(uuid);

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
        // Note: sendAllFakeTabEntriesToPlayer is called from PlayerListMixin
        // before addNewPlayer (within the suspendFlushing window) to ensure
        // fake tab entries arrive before EntityProjection spawn packets.
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
        remoteProfiles.remove(playerUuid);
    }

    public void publishChat(UUID senderUuid, ResourceKey<ChatType> chatTypeKey,
                            Component senderName, Component chatContent) {
        ChatRedisMessage msg = ChatRedisMessage.beginChat(server, senderUuid, chatTypeKey, senderName, chatContent);
        msg.publishAsync(redisConn.get());
    }

    public void handleRemoteChat(UUID senderUuid, ResourceKey<ChatType> chatTypeKey,
                                 Component senderName, Component chatContent) {
        server.execute(() -> {
            ChatType.Bound bound;
            try {
                bound = ChatType.bind(chatTypeKey, server.registryAccess(), senderName);
            } catch (Exception e) {
                ProjectMe.LOGGER.warn("Unknown chat type key {}, falling back to CHAT", chatTypeKey, e);
                bound = ChatType.bind(ChatType.CHAT, server.registryAccess(), senderName);
            }
            OutgoingChatMessage disguised = new OutgoingChatMessage.Disguised(chatContent);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!player.getGameProfile().id().equals(senderUuid)) {
                    player.sendChatMessage(disguised, false, bound);
                }
            }
            server.logChatMessage(chatContent, bound, "Cluster");
        });
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

    public Map<String, Map<UUID, String>> snapshotRemotePlayers() {
        Map<String, Map<UUID, String>> snapshot = new HashMap<>();
        for (var entry : peerStates.entrySet()) {
            Map<UUID, String> players = new HashMap<>();
            for (var player : entry.getValue().players.entrySet()) {
                players.put(player.getKey(), player.getValue().name());
            }
            snapshot.put(entry.getKey(), players);
        }
        return snapshot;
    }

    @Override
    public void close() {
        redisConn.close();
    }
}
