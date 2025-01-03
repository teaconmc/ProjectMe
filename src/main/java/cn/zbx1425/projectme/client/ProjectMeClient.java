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
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class ProjectMeClient {

    public ProjectMeClient(IEventBus eventBus) {
        NeoForge.EVENT_BUS.register(ForgeEventBusListener.class);
        eventBus.register(ModEventBusListener.class);
    }

    private static UUID peTargetUUID;
    private static long peFirstInteractTime = -1;

    public static class ForgeEventBusListener {

        @SubscribeEvent
        public static void onPlayerInteractEntity(PlayerInteractEvent.EntityInteractSpecific event) {
            if (!event.getEntity().level().isClientSide()) return;
            if (event.getTarget() instanceof EntityProjection projection) {
                if (System.currentTimeMillis() - peFirstInteractTime > 500) {
                    peTargetUUID = null;
                    peFirstInteractTime = -1;
                }
                if (!projection.getProjectingPlayer().equals(peTargetUUID)) {
                    peTargetUUID = projection.getProjectingPlayer();
                    peFirstInteractTime = System.currentTimeMillis();
                    Minecraft.getInstance().getChatListener().handleSystemMessage(Component.translatable("project_me.projection_entity.goto"), true);
                } else {
                    if (System.currentTimeMillis() - peFirstInteractTime >= 10 &&
                            System.currentTimeMillis() - peFirstInteractTime <= 500) {
                        peTargetUUID = null;
                        peFirstInteractTime = -1;
                        Objects.requireNonNull(Minecraft.getInstance().getConnection()).sendCommand("/go " + projection.getName().getString());
                    }
                }
            }
        }

        @SubscribeEvent
        public static void onAttackEntity(AttackEntityEvent event) {
            if (event.getTarget() instanceof EntityProjection) {
                event.setCanceled(true);
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
