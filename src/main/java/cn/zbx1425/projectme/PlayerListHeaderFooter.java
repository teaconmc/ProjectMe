package cn.zbx1425.projectme;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class PlayerListHeaderFooter {

    private static final String TAB_PAD = "    ";

    private static final Component TAB_HEADER = Component.literal("\n" + TAB_PAD)
            .append(Component.translatable("project_me.tab.header"))
            .append(Component.literal(TAB_PAD + "\n"));

    private static final Component TAB_FOOTER = Component.literal(TAB_PAD)
            .append(Component.translatable("project_me.tab.footer",
                    Component.translatable("project_me.tab.footer.style")
                            .withStyle(ChatFormatting.ITALIC)
                            .withStyle(ChatFormatting.GRAY),
                    Component.translatable("project_me.tab.footer.command")
                            .withStyle(ChatFormatting.GOLD)))
            .append(Component.literal(TAB_PAD));

    private PlayerListHeaderFooter() {
    }

    public static void apply(ServerPlayer player) {
        player.setTabListHeaderFooter(TAB_HEADER, TAB_FOOTER);
    }
}
