package cn.zbx1425.projectme;

import cn.zbx1425.projectme.client.ProjectMeClient;
import cn.zbx1425.projectme.compat.ICompatibility;
import cn.zbx1425.projectme.compat.impl.MTRCompatibility;
import cn.zbx1425.projectme.compat.impl.VanillaCompatibility;
import cn.zbx1425.projectme.entity.EntityProjection;
import cn.zbx1425.projectme.sync.Synchronizer;
import net.minecraft.Util;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Mod(ProjectMe.MOD_ID)
public class ProjectMe {

    public static final String MOD_ID = "project_me";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, MOD_ID);
    public static final DeferredRegister<EntityDataSerializer<?>> ENTITY_DATA_SERIALIZERS = DeferredRegister.create(NeoForgeRegistries.ENTITY_DATA_SERIALIZERS, MOD_ID);

    public static final Supplier<EntityType<EntityProjection>> ENTITY_PROJECTION = ENTITY_TYPES.register("projection",
            () -> EntityType.Builder.of(EntityProjection::new, MobCategory.CREATURE).sized(0.6f, 1.8f).build("projection"));

    public static final Supplier<EntityDataSerializer<UUID>> UUID_ENTITY_DATA_SERIALIZER = ENTITY_DATA_SERIALIZERS.register("uuid",
            () -> EntityDataSerializer.forValueType(UUIDUtil.STREAM_CODEC));

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MOD_ID);

    public static final ServerConfig CONFIG = new ServerConfig();
    public static Synchronizer synchronizer;

    private static final List<ICompatibility> COMPATIBILITIES = Util.make(new ArrayList<>(), l -> {
        l.add(new VanillaCompatibility());
        if (ModList.get().isLoaded("mtr")) {
            l.add(new MTRCompatibility());
        }
    });

    public ProjectMe(IEventBus eventBus) {
        ENTITY_TYPES.register(eventBus);
        ENTITY_DATA_SERIALIZERS.register(eventBus);
        ATTACHMENT_TYPES.register(eventBus);
        NeoForge.EVENT_BUS.register(ForgeEventBusListener.class);
        eventBus.register(ModEventBusListener.class);

        if (FMLEnvironment.dist.isClient()) {
            new ProjectMeClient(eventBus);
        }
    }

    public static boolean computePlayerVisibility(ServerPlayer player) {
        for (ICompatibility compatibility : COMPATIBILITIES) {
            if (!compatibility.shouldDisplayPlayer(player)) {
                return false;
            }
        }
        return true;
    }

    public static class ForgeEventBusListener {

        @SubscribeEvent
        public static void onServerStart(ServerStartingEvent event) {
            try {
                if (synchronizer != null) synchronizer.close();
                CONFIG.load(event.getServer().getServerDirectory()
                        .resolve("config").resolve("project_me.json"));
                synchronizer = new Synchronizer(CONFIG.redisUrl.value, event.getServer());
            } catch (Exception ex) {
                ProjectMe.LOGGER.error("Failed to use server config", ex);
            }
        }

        @SubscribeEvent
        public static void onServerStop(ServerStoppingEvent event) {
            try {
                if (synchronizer != null) synchronizer.close();
            } catch (Exception ex) {
                ProjectMe.LOGGER.error("Failed to close sync dispatcher", ex);
            }
        }

        // private static final Random random = new Random();

        @SubscribeEvent
        public static void onServerTick(ServerTickEvent.Pre event) {
            if (synchronizer == null) return;
            if (event.getServer().getTickCount() % CONFIG.syncInterval.value == 0) {
                synchronizer.notifyPlayerPresence(event.getServer().getPlayerList().getPlayers());
                // synchronizer.mockPlayerPresence(new Vec3(random.nextDouble(-5, 5), -60, random.nextDouble(-5, 5)));
            }
        }

        @SubscribeEvent
        public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
            if (synchronizer == null) return;
            synchronizer.notifyPlayerAbsence(event.getEntity().getGameProfile().getId());
        }
    }

    public static class ModEventBusListener {

        @SubscribeEvent
        public static void registryEntityAttributes(EntityAttributeCreationEvent event) {
            event.put(ENTITY_PROJECTION.get(), EntityProjection.createAttributes().build());
        }
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
