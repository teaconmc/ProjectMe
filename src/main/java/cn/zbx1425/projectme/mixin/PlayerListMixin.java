package cn.zbx1425.projectme.mixin;

import cn.zbx1425.projectme.ProjectMe;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;
import java.util.function.Predicate;

@Mixin(PlayerList.class)
public class PlayerListMixin {

    @Inject(
        method = "placeNewPlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;addNewPlayer(Lnet/minecraft/server/level/ServerPlayer;)V"
        )
    )
    private void sendFakeTabBeforeEntityPairing(Connection connection,
                                                          ServerPlayer player,
                                                          CommonListenerCookie cookie,
                                                          CallbackInfo ci) {
        if (ProjectMe.synchronizer != null) {
            ProjectMe.synchronizer.sendAllFakeTabEntriesToPlayer(player);
        }
    }

    @Unique
    private static final Set<ResourceKey<ChatType>> PROJECT_ME$SYNCED_CHAT_TYPES = Set.of(
            ChatType.CHAT, ChatType.SAY_COMMAND, ChatType.EMOTE_COMMAND
    );

    @Inject(
        method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Ljava/util/function/Predicate;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
        at = @At("HEAD")
    )
    private void onBroadcastChatMessage(PlayerChatMessage message,
                                                   Predicate<ServerPlayer> isFiltered,
                                                   ServerPlayer senderPlayer,
                                                   ChatType.Bound chatType,
                                                   CallbackInfo ci) {
        if (ProjectMe.synchronizer == null) return;
        if (senderPlayer == null) return;

        ResourceKey<ChatType> chatTypeKey = chatType.chatType().unwrapKey().orElse(null);
        if (chatTypeKey == null || !PROJECT_ME$SYNCED_CHAT_TYPES.contains(chatTypeKey)) return;

        try {
            ProjectMe.synchronizer.publishChat(
                    senderPlayer.getGameProfile().id(),
                    chatTypeKey,
                    chatType.name(),
                    message.decoratedContent()
            );
        } catch (Exception e) {
            ProjectMe.LOGGER.error("Failed to publish chat to Redis", e);
        }
    }
}
