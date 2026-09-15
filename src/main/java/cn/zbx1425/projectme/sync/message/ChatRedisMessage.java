package cn.zbx1425.projectme.sync.message;

import cn.zbx1425.projectme.sync.Synchronizer;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

public class ChatRedisMessage extends RedisMessage {

    public static final String CHANNEL = "projectme:chat";

    private ChatRedisMessage(String peerId) {
        super(peerId);
    }

    public ChatRedisMessage(ByteBuf src) {
        super(src);
    }

    public static ChatRedisMessage beginChat(MinecraftServer server,
                                             UUID senderUuid, ResourceKey<ChatType> chatTypeKey,
                                             Component senderName, Component chatContent) {
        ChatRedisMessage result = new ChatRedisMessage(SELF_PEER_ID);
        result.content.writeUUID(senderUuid);
        result.content.writeResourceKey(chatTypeKey);
        RegistryFriendlyByteBuf regBuf = new RegistryFriendlyByteBuf(result.content, server.registryAccess());
        ComponentSerialization.TRUSTED_STREAM_CODEC.encode(regBuf, senderName);
        ComponentSerialization.TRUSTED_STREAM_CODEC.encode(regBuf, chatContent);
        return result;
    }

    @Override
    public void handle(Synchronizer synchronizer) {
        if (isFromSelf()) return;
        RegistryFriendlyByteBuf regBuf = new RegistryFriendlyByteBuf(
                content, synchronizer.getServer().registryAccess());
        UUID senderUuid = regBuf.readUUID();
        ResourceKey<ChatType> chatTypeKey = regBuf.readResourceKey(Registries.CHAT_TYPE);
        Component senderName = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(regBuf);
        Component chatContent = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(regBuf);
        synchronizer.handleRemoteChat(senderUuid, chatTypeKey, senderName, chatContent);
    }

    @Override
    public String channel() {
        return CHANNEL;
    }
}
