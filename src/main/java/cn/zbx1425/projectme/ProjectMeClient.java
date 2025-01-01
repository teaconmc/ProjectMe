package cn.zbx1425.projectme;

import cn.zbx1425.projectme.entity.EntityProjectionRenderer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

public class ProjectMeClient {

    public ProjectMeClient(IEventBus eventBus) {
        eventBus.register(ModEventBusListener.class);
    }

    public static class ModEventBusListener {

        @SubscribeEvent
        public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(ProjectMe.ENTITY_PROJECTION.get(), EntityProjectionRenderer::new);
        }
    }
}
