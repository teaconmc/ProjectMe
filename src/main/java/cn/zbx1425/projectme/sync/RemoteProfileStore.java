package cn.zbx1425.projectme.sync;

import cn.zbx1425.projectme.ProjectMe;
import com.mojang.authlib.GameProfile;
import io.lettuce.core.api.StatefulRedisConnection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.codec.ByteBufCodecs;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public class RemoteProfileStore {

    static final String REDIS_PROFILES_KEY = "projectme:profiles";
    private static final int MAX_RETRIES = 100;

    private final StatefulRedisConnection<String, ByteBuf> redisConn;
    private final Executor serverExecutor;

    private final Map<UUID, GameProfile> knownProfiles = new HashMap<>();
    private final Set<UUID> fetchInFlight = new HashSet<>();

    public RemoteProfileStore(StatefulRedisConnection<String, ByteBuf> redisConn, Executor serverExecutor) {
        this.redisConn = redisConn;
        this.serverExecutor = serverExecutor;
    }

    public void writeProfile(UUID uuid, GameProfile profile) {
        writeProfileWithRetry(uuid, profile, 0);
    }

    private void writeProfileWithRetry(UUID uuid, GameProfile profile, int attempt) {
        ByteBuf buf = Unpooled.buffer();
        try {
            ByteBufCodecs.GAME_PROFILE.encode(buf, profile);
            redisConn.async().hset(REDIS_PROFILES_KEY, uuid.toString(), buf)
                    .whenComplete((result, ex) -> {
                        buf.release();
                        if (ex != null) {
                            scheduleRetry(attempt,
                                    () -> writeProfileWithRetry(uuid, profile, attempt + 1),
                                    "write profile", uuid, ex);
                        }
                    });
        } catch (Exception e) {
            buf.release();
            scheduleRetry(attempt,
                    () -> writeProfileWithRetry(uuid, profile, attempt + 1),
                    "write profile", uuid, e);
        }
    }

    public boolean fetchProfileIfNeeded(UUID uuid) {
        if (knownProfiles.containsKey(uuid) || fetchInFlight.contains(uuid)) return false;
        fetchInFlight.add(uuid);
        fetchProfileWithRetry(uuid, 0);
        return true;
    }

    private void fetchProfileWithRetry(UUID uuid, int attempt) {
        redisConn.async().hget(REDIS_PROFILES_KEY, uuid.toString())
                .whenComplete((profileBytes, ex) -> {
                    if (profileBytes != null && ex == null) {
                        serverExecutor.execute(() -> {
                            try {
                                GameProfile profile = ByteBufCodecs.GAME_PROFILE.decode(profileBytes);
                                knownProfiles.put(uuid, profile);
                            } finally {
                                profileBytes.release();
                                fetchInFlight.remove(uuid);
                            }
                        });
                    } else {
                        if (profileBytes != null) profileBytes.release();
                        scheduleRetry(attempt,
                                () -> fetchProfileWithRetry(uuid, attempt + 1),
                                "fetch profile", uuid, ex);
                        if (attempt >= MAX_RETRIES) {
                            serverExecutor.execute(() -> fetchInFlight.remove(uuid));
                        }
                    }
                });
    }

    public GameProfile getProfile(UUID uuid) {
        return knownProfiles.get(uuid);
    }

    public boolean hasProfile(UUID uuid) {
        return knownProfiles.containsKey(uuid);
    }

    public void remove(UUID uuid) {
        knownProfiles.remove(uuid);
        fetchInFlight.remove(uuid);
    }

    public void retainOnly(Set<UUID> activeUuids) {
        knownProfiles.keySet().retainAll(activeUuids);
        fetchInFlight.retainAll(activeUuids);
    }

    private static long retryDelay(int attempt) {
        return Math.min(1000L << attempt, 16_000L);
    }

    private void scheduleRetry(int attempt, Runnable retryAction,
                               String operation, UUID uuid, Throwable ex) {
        if (attempt < MAX_RETRIES) {
            ProjectMe.LOGGER.warn("Failed to {} for {} (attempt {})",
                operation, uuid, attempt, ex);
            CompletableFuture.delayedExecutor(retryDelay(attempt), TimeUnit.MILLISECONDS)
                    .execute(retryAction);
        } else {
            ProjectMe.LOGGER.warn("Failed to {} for {} after {} attempts",
                    operation, uuid, MAX_RETRIES, ex);
        }
    }
}
