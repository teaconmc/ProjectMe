package cn.zbx1425.projectme.client;

import cn.zbx1425.projectme.ProjectMe;
import cn.zbx1425.projectme.entity.EntityProjection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.*;
import snownee.jade.api.config.IPluginConfig;

@WailaPlugin(ProjectMe.MOD_ID)
public class ProjectMeJadePlugin implements IWailaPlugin {
    private static final ResourceLocation PROJECTION = ProjectMe.id("projection_entity_tooltip");

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerEntityComponent(new IComponentProvider<>() {
            @Override
            public void appendTooltip(ITooltip tooltip, EntityAccessor entity, IPluginConfig iPluginConfig) {
                tooltip.add(Component.translatable("project_me.projection_entity.tooltip"));
            }

            @Override
            public ResourceLocation getUid() {
                return PROJECTION;
            }
        }, EntityProjection.class);
    }
}
