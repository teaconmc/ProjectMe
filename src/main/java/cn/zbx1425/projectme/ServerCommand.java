package cn.zbx1425.projectme;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public class ServerCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("projectmes").then(
            Commands.literal("info").executes(ServerCommand::info)
        ));
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (ProjectMe.synchronizer == null) {
            source.sendFailure(Component.translatable("project_me.command.info.not_configured"));
            return 0;
        }

        String localPeerId = ProjectMe.CONFIG.peerId.value;
        Map<String, Map<UUID, String>> peers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        peers.putAll(ProjectMe.synchronizer.snapshotRemotePlayers());

        Map<UUID, String> localPlayers = new HashMap<>();
        for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
            localPlayers.put(player.getGameProfile().id(), player.getGameProfile().name());
        }
        peers.put(localPeerId, localPlayers);

        source.sendSystemMessage(
            Component.translatable("project_me.command.info.welcome").withStyle(ChatFormatting.YELLOW));

        Set<UUID> uniquePlayers = new HashSet<>();
        for (var entry : peers.entrySet()) {
            sendServerPlayers(source, entry.getKey(), entry.getValue());
            uniquePlayers.addAll(entry.getValue().keySet());
        }

        source.sendSystemMessage(countMessage(
            uniquePlayers.size(),
            "project_me.command.info.total_singular",
            "project_me.command.info.total_plural"));
        source.sendSystemMessage(countMessage(
            ProjectMe.synchronizer.getPeerCount(),
            "project_me.command.info.peers_singular",
            "project_me.command.info.peers_plural"));
        source.sendSystemMessage(Component.translatable(
            "project_me.command.info.you_are_at",
            Component.literal("[" + localPeerId + "]").withStyle(ChatFormatting.DARK_AQUA)
        ).withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    private static void sendServerPlayers(CommandSourceStack source, String peerId, Map<UUID, String> players) {
        List<String> names = new ArrayList<>(players.values());
        names.sort(String.CASE_INSENSITIVE_ORDER);

        MutableComponent line = Component.literal("[" + peerId + "] ").withStyle(ChatFormatting.DARK_AQUA)
            .append(Component.literal("(" + names.size() + ")").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(": ").withStyle(ChatFormatting.RESET));
        for (int i = 0; i < names.size(); i++) {
            line.append(Component.literal(names.get(i)));
            if (i + 1 < names.size()) {
                line.append(Component.literal(", "));
            }
        }
        source.sendSystemMessage(line);
    }

    private static Component countMessage(int count, String singularKey, String pluralKey) {
        return Component.translatable(count == 1 ? singularKey : pluralKey,
            Component.literal(Integer.toString(count)).withStyle(ChatFormatting.GREEN)
        ).withStyle(ChatFormatting.YELLOW);
    }
}
