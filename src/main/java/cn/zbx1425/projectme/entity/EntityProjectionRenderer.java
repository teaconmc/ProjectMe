package cn.zbx1425.projectme.entity;

import cn.zbx1425.projectme.ClientConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.NonNull;

public class EntityProjectionRenderer extends LivingEntityRenderer<EntityProjection, AvatarRenderState, PlayerModel> {

    private final PlayerModel slimModel;
    private final PlayerModel wideModel;

    private final PlayerSkinRenderCache playerSkinRenderCache;

    public EntityProjectionRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER_SLIM), true), 0.5f);
        slimModel = model;
        wideModel = new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false);
        this.playerSkinRenderCache = context.getPlayerSkinRenderCache();
    }

    @Override
    public @NonNull Identifier getTextureLocation(AvatarRenderState state) {
        return state.skin.body().texturePath();
    }

    @Override
    public void submit(@NonNull AvatarRenderState state, @NonNull PoseStack poseStack, @NonNull SubmitNodeCollector submitNodeCollector, @NonNull CameraRenderState camera) {
        if (!ClientConfig.isProjectionEntityEnabled) return;

        model = state.skin.model() == PlayerModelType.SLIM ? slimModel : wideModel;
        super.submit(state, poseStack, submitNodeCollector, camera);
    }

    @Override
    public @NonNull AvatarRenderState createRenderState() {
        return new AvatarRenderState();
    }

    @Override
    public void extractRenderState(@NonNull EntityProjection entity, @NonNull AvatarRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        HumanoidMobRenderer.extractHumanoidRenderState(entity, state, partialTicks, this.itemModelResolver);
        state.skin = resolveClientSkin(entity);
        ItemStack handStack = entity.getMainHandItem();
        state.rightArmPose = !handStack.isEmpty() ? HumanoidModel.ArmPose.ITEM : HumanoidModel.ArmPose.EMPTY;
        state.id = entity.getId();
    }

    public PlayerSkin resolveClientSkin(EntityProjection entity) {
        return playerSkinRenderCache.getOrDefault(entity.gameProfile).playerSkin();
    }
}
