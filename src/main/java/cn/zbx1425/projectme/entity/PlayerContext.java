package cn.zbx1425.projectme.entity;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import org.jetbrains.annotations.NotNull;

public record PlayerContext(boolean visibility) {
    public static final IAttachmentSerializer<Tag, PlayerContext> SERIALIZER = new IAttachmentSerializer<>() {
        @Override
        public PlayerContext read(@NotNull IAttachmentHolder holder, @NotNull Tag tag, HolderLookup.@NotNull Provider lookup) {
            try {
                return new PlayerContext(((CompoundTag) tag).getBoolean("visibility"));
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public @NotNull Tag write(@NotNull PlayerContext context, HolderLookup.@NotNull Provider arg) {
            CompoundTag tag = new CompoundTag();
            tag.putBoolean("visibility", context.visibility());
            return tag;
        }
    };
}
