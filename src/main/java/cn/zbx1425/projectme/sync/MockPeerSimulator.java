package cn.zbx1425.projectme.sync;

import cn.zbx1425.projectme.sync.message.PresenceRedisMessage;
import com.mojang.authlib.GameProfile;
import io.lettuce.core.api.StatefulRedisConnection;
import io.netty.buffer.ByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class MockPeerSimulator {

    private static final String MOCK_PEER_ID = "mock-peer";

    private final LinkedHashMap<UUID, Long> mockPlayers = new LinkedHashMap<>();
    private final Random random = new Random();
    private final RemoteProfileStore profileStore;
    private final StatefulRedisConnection<String, ByteBuf> redisConn;

    public MockPeerSimulator(RemoteProfileStore profileStore,
                             StatefulRedisConnection<String, ByteBuf> redisConn) {
        this.profileStore = profileStore;
        this.redisConn = redisConn;
    }

    public void tick() {
        if (random.nextInt(mockPlayers.size() < 10 ? 10 : 100) < 1) {
            if (random.nextInt(3) < 1 || mockPlayers.size() >= 20) {
                if (!mockPlayers.isEmpty()) {
                    Iterator<UUID> it = mockPlayers.keySet().iterator();
                    it.next();
                    it.remove();
                }
            } else {
                UUID newUuid = UUID.randomUUID();
                mockPlayers.put(newUuid, System.currentTimeMillis());
                GameProfile profile = new GameProfile(newUuid, newUuid.toString().substring(0, 8));
                profileStore.writeProfile(newUuid, profile);
            }
        }

        long now = System.currentTimeMillis();
        PresenceRedisMessage msg = PresenceRedisMessage.beginPlayerPresence(MOCK_PEER_ID, mockPlayers.size());
        for (var entry : mockPlayers.entrySet()) {
            UUID uuid = entry.getKey();
            boolean visible = ((now - entry.getValue()) / 2000) % 2 == 0;
            msg.andWithPlayerData(uuid, uuid.toString().substring(0, 8),
                    Level.OVERWORLD,
                    new Vec3(random.nextDouble(-10, 10), -60, random.nextDouble(-10, 10)),
                    0, 0, 0, visible);
        }
        msg.publishAsync(redisConn);
    }
}
