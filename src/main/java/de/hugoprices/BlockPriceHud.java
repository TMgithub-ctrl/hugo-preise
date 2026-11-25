package de.hugoprices;

import de.hugoprices.logic.Settings;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Kleine Anzeige oben rechts (wie WAILA): Name und Preise des Blocks, auf den das Fadenkreuz zeigt.
 */
final class BlockPriceHud {
    private static final int PADDING = 4;
    private static final int ICON = 16;
    private static final int LINE_HEIGHT = 10;
    private static final int MARGIN_RIGHT = 4;
    private static final int BACKGROUND = 0xC0101010;
    private static final int BORDER = 0xFF5A2D82;
    private static final int WHITE = 0xFFFFFFFF;

    private BlockPriceHud() {
    }

    static void register(Settings settings) {
        if (!settings.hudEnabled) {
            return;
        }
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("hugoprices", "block_price"),
                (graphics, delta) -> render(graphics, delta, settings));
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta, Settings settings) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (mc.debugEntries != null && mc.debugEntries.isOverlayVisible()) {
            return; // F3 offen: rechts steht schon genug
        }
        HitResult hit = mc.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult blockHit)) {
            return;
        }
        BlockState state = mc.level.getBlockState(blockHit.getBlockPos());
        Item item = state.getBlock().asItem();
        if (item == Items.AIR) {
            return; // Wasser, Feuer, Luft ... haben kein Item
        }
        ItemStack stack = item.getDefaultInstance();

        List<Component> prices = new ArrayList<>();
        HugoPricesClient.addPriceLines(stack, prices, true);
        if (prices.isEmpty()) {
            return;
        }

        Font font = mc.font;
        Component name = stack.getHoverName();
        int textWidth = font.width(name);
        for (Component line : prices) {
            textWidth = Math.max(textWidth, font.width(line));
        }
        int textLines = 1 + prices.size();
        int width = PADDING + ICON + 4 + textWidth + PADDING;
        int height = PADDING + Math.max(ICON, textLines * LINE_HEIGHT - 1) + PADDING;
        int x = g.guiWidth() - width - MARGIN_RIGHT;
        int y = settings.hudOffsetY;

        // Rahmen + Hintergrund
        g.fill(x - 1, y - 1, x + width + 1, y + height + 1, BORDER);
        g.fill(x, y, x + width, y + height, BACKGROUND);

        g.item(stack, x + PADDING, y + PADDING);

        int tx = x + PADDING + ICON + 4;
        int ty = y + PADDING;
        g.text(font, name, tx, ty, WHITE);
        for (Component line : prices) {
            ty += LINE_HEIGHT;
            g.text(font, line, tx, ty, WHITE);
        }
    }
}
