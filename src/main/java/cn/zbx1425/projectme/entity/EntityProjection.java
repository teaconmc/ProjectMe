package cn.zbx1425.projectme.entity;

import cn.zbx1425.projectme.ClientConfig;
import cn.zbx1425.projectme.ProjectMe;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class EntityProjection extends LivingEntity {
    public ResolvableProfile gameProfile = ResolvableProfile.createUnresolved(Util.NIL_UUID);

    public EntityProjection(EntityType<? extends LivingEntity> entityType, Level level) {
        super(entityType, level);
        this.noPhysics = true;
    }

    @Override
    public void addAdditionalSaveData(ValueOutput tag) {
        super.addAdditionalSaveData(tag);
        tag.store("projectingPlayer", UUIDUtil.CODEC, entityData.get(PROJECTING_PLAYER));
    }

    @Override
    public void readAdditionalSaveData(ValueInput tag) {
        super.readAdditionalSaveData(tag);
        tag.read("projectingPlayer", UUIDUtil.CODEC).ifPresent(uuid -> entityData.set(PROJECTING_PLAYER, uuid));
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (PROJECTING_PLAYER.equals(key)) {
            if (level().isClientSide()) {
                gameProfile = ResolvableProfile.createUnresolved(entityData.get(PROJECTING_PLAYER));
            }
        }
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean isInvulnerable() {
        return true;
    }

    @Override
    public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
        return true;
    }

    @Override
    public boolean isPickable() {
        return ClientConfig.isProjectionEntityEnabled && super.isPickable();
    }

    @Override
    public boolean isInvisible() {
        return !ClientConfig.isProjectionEntityEnabled;
    }

    @Override
    public boolean isInvisibleTo(Player arg) {
        return !ClientConfig.isProjectionEntityEnabled;
    }

    @Override
    public @NotNull ItemStack getItemBySlot(@NotNull EquipmentSlot arg) {
        return ItemStack.EMPTY;
    }

    @Override
    public void setItemSlot(@NotNull EquipmentSlot arg, @NotNull ItemStack arg2) {
    }

    @Override
    public @NotNull HumanoidArm getMainArm() {
        return HumanoidArm.RIGHT;
    }

    private static final EntityDataAccessor<UUID> PROJECTING_PLAYER = SynchedEntityData.defineId(EntityProjection.class,
            ProjectMe.UUID_ENTITY_DATA_SERIALIZER.get());

    @Override
    protected void defineSynchedData(SynchedEntityData.@NotNull Builder builder) {
        super.defineSynchedData(builder);
        builder.define(PROJECTING_PLAYER, Util.NIL_UUID);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return LivingEntity.createLivingAttributes()
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D);
    }

    public UUID getProjectingPlayer() {
        return entityData.get(PROJECTING_PLAYER);
    }
}
