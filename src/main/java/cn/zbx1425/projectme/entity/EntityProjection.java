package cn.zbx1425.projectme.entity;

import cn.zbx1425.projectme.ClientConfig;
import cn.zbx1425.projectme.ProjectMe;
import com.mojang.authlib.GameProfile;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class EntityProjection extends LivingEntity {
    private static final CompletableFuture<Optional<GameProfile>> EMPTY_GAME_PROFILE = CompletableFuture.completedFuture(Optional.empty());

    public CompletableFuture<Optional<GameProfile>> gameProfile = EMPTY_GAME_PROFILE;

    public EntityProjection(EntityType<? extends LivingEntity> entityType, Level level) {
        super(entityType, level);
        this.noPhysics = true;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putUUID("projectingPlayer", entityData.get(PROJECTING_PLAYER));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(PROJECTING_PLAYER, tag.getUUID("projectingPlayer"));
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (PROJECTING_PLAYER.equals(key)) {
            if (level().isClientSide) {
                gameProfile = SkullBlockEntity.fetchGameProfile(entityData.get(PROJECTING_PLAYER));
            }
        }
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean canAttackType(EntityType<?> arg) {
        return super.canAttackType(arg);
    }

    @Override
    public boolean isInvulnerable() {
        return true;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource arg) {
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
    public Iterable<ItemStack> getArmorSlots() {
        return List.of();
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
