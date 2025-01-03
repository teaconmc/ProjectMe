package cn.zbx1425.projectme.client;

import cn.zbx1425.projectme.ProjectMe;
import cn.zbx1425.projectme.entity.EntityProjection;
import cn.zbx1425.projectme.entity.EntityProjectionRenderer;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class ProjectMeClient {

    public ProjectMeClient(IEventBus eventBus) {
        NeoForge.EVENT_BUS.register(ForgeEventBusListener.class);
        eventBus.register(ModEventBusListener.class);
    }

    private static UUID projectionEntityUUID;
    private static long projectionEntityTime = -1;

    private static GameProfile computeTargetProjectionEntityUUID() {
        HitResult hit = Minecraft.getInstance().hitResult;
        if (hit != null && hit.getType() == HitResult.Type.ENTITY && ((EntityHitResult) hit).getEntity() instanceof EntityProjection entity) {
            return entity.gameProfile.getNow(Optional.empty()).orElse(null);
        } else {
            return null;
        }
    }

    public static class ForgeEventBusListener {

        @SubscribeEvent
        public static void clientTick(ClientTickEvent.Pre event) {
            GameProfile projection = computeTargetProjectionEntityUUID();
            if (projection == null) {
                projectionEntityUUID = null;
                projectionEntityTime = -1;
            } else if (projectionEntityUUID != projection.getId()) {
                projectionEntityUUID = projection.getId();
                projectionEntityTime = System.currentTimeMillis();

                Minecraft.getInstance().getChatListener().handleSystemMessage(Component.translatable("project_me.projection_entity.goto"), true);
            } else if (System.currentTimeMillis() - projectionEntityTime <= 500) {
                projectionEntityUUID = null;
                projectionEntityTime = -1;

                Objects.requireNonNull(Minecraft.getInstance().getConnection()).sendCommand("/go " + projection.getName());
            }
        }
    }

    public static class ModEventBusListener {

        @SubscribeEvent
        public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(ProjectMe.ENTITY_PROJECTION.get(), EntityProjectionRenderer::new);
        }
    }
}
