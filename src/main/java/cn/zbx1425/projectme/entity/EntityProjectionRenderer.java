package cn.zbx1425.projectme.entity;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

public class EntityProjectionRenderer extends LivingEntityRenderer<EntityProjection, PlayerModel<EntityProjection>> {

    private final PlayerModel<EntityProjection> slimModel;
    private final PlayerModel<EntityProjection> wideModel;

    public EntityProjectionRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true), 0.5f);
        slimModel = model;
        wideModel = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false);
    }

    @Override
    public ResourceLocation getTextureLocation(EntityProjection entity) {
        Optional<GameProfile> result = entity.gameProfile.getNow(Optional.empty());
        if (result.isPresent()) {
            SkinManager skinManager = Minecraft.getInstance().getSkinManager();
            return skinManager.getInsecureSkin(result.get()).texture();
        }
        return ResourceLocation.withDefaultNamespace("textures/entity/player/slim/alex.png");
    }

    @Override
    public void render(EntityProjection entity, float entityYaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        Optional<GameProfile> result = entity.gameProfile.getNow(Optional.empty());
        if (result.isPresent()) {
            SkinManager skinManager = Minecraft.getInstance().getSkinManager();
            model = skinManager.getInsecureSkin(result.get()).model() == PlayerSkin.Model.SLIM ? slimModel : wideModel;
        } else {
            model = slimModel;
        }

        PlayerModel<EntityProjection> playerModel = this.getModel();
        playerModel.setAllVisible(true);
        ItemStack handStack = entity.getMainHandItem();
        playerModel.rightArmPose = !handStack.isEmpty() ? HumanoidModel.ArmPose.ITEM : HumanoidModel.ArmPose.EMPTY;
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }
}
