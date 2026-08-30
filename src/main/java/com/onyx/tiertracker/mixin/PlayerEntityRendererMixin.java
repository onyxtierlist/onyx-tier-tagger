package com.onyx.tiertracker.mixin;

import com.onyx.tiertracker.OnyxTierTaggerClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.Style;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntityRenderer.class)
public class PlayerEntityRendererMixin {
    private static final Identifier ICON_FONT = Identifier.of("onyx_tagger", "icons");

    @Inject(method = "hasLabel", at = @At("RETURN"), cancellable = true, require = 0)
    private void onHasLabel(net.minecraft.entity.PlayerLikeEntity player, double squaredDistanceToCamera, CallbackInfoReturnable<Boolean> cir) {
        if (!OnyxTierTaggerClient.showAboveHead || !OnyxTierTaggerClient.showSelfName) return;
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null && player == client.player && !client.options.getEntityShadows().getValue()) {
                cir.setReturnValue(true);
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "renderLabelIfPresent", at = @At("HEAD"), require = 0)
    private void onRenderLabel(net.minecraft.client.render.entity.state.PlayerEntityRenderState state, MatrixStack matrices, net.minecraft.client.render.command.OrderedRenderCommandQueue queue, net.minecraft.client.render.state.CameraRenderState camera, CallbackInfo ci) {
        if (!OnyxTierTaggerClient.showAboveHead || state.id < 0) return;
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) return;
            var entity = client.world.getEntityById(state.id);
            if (!(entity instanceof net.minecraft.entity.player.PlayerEntity player)) return;

            var info = OnyxTierTaggerClient.get(player);
            if (info == null || info.topTiers() == null || info.topTiers().isEmpty()) return;

            Text base = state.playerName;
            if (base == null) {
                String name = player.getGameProfile().name();
                if (name == null || name.isBlank()) return;
                base = Text.literal(name);
            }
            state.playerName = buildCenteredLabel(base, info);
        } catch (Throwable ignored) {}
    }

    /**
     * Builds the player label using every tier returned by the Onyx API.
     *
     * Previously only the first two entries were displayed, even when the API
     * returned tiers for additional game modes.  We now preserve the complete
     * list and render it in order, separated by the configured separator.
     */
    public static Text buildCenteredLabel(Text base, OnyxTierTaggerClient.TierInfo info) {
        if (base == null || info == null || info.topTiers() == null || info.topTiers().isEmpty()) return base;

        MutableText label = Text.empty();
        boolean first = true;
        for (OnyxTierTaggerClient.TierEntry entry : info.topTiers()) {
            if (entry == null || entry.tier() == null || entry.tier().isBlank()) continue;
            if (!first) label.append(Text.literal(OnyxTierTaggerClient.separator));
            label.append(tierWithIcon(entry, false));
            first = false;
        }

        if (first) return base;
        return label.append(Text.literal(" ")).append(base.copy());
    }

    private static MutableText tierWithIcon(OnyxTierTaggerClient.TierEntry entry, boolean iconFirst) {
        String icon = iconChar(entry.gamemode());
        MutableText iconText = Text.literal(icon).setStyle(Style.EMPTY.withFont(ICON_FONT));
        MutableText tier = Text.literal(entry.tier());
        return iconFirst ? Text.empty().append(iconText).append(Text.literal(" ")).append(tier)
                         : Text.empty().append(tier).append(Text.literal(" ")).append(iconText);
    }

    private static String iconChar(String gamemode) {
        if (gamemode == null) return "?";
        return switch (gamemode.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "")) {
            case "sword" -> "\uE001";
            case "uhc" -> "\uE002";
            case "smp" -> "\uE003";
            case "pot", "potion" -> "\uE004";
            case "nethop", "netheriteop", "netherite" -> "\uE005";
            case "mace" -> "\uE006";
            case "axe" -> "\uE007";
            case "vanilla" -> "\uE008";
            default -> "?";
        };
    }
}
