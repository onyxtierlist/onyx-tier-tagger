package com.onyx.tiertracker.mixin;

import com.onyx.tiertracker.OnyxTierTaggerClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.PlayerLikeEntity;
import net.minecraft.text.Text;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntityRenderer.class)
public class PlayerEntityRendererMixin {
    private static final Identifier ICON_FONT = Identifier.of("onyx_tagger", "icons");

    @Inject(method = "hasLabel", at = @At("RETURN"), cancellable = true)
    private void onHasLabel(PlayerLikeEntity player, double squaredDistanceToCamera,
                            CallbackInfoReturnable<Boolean> cir) {
        if (!OnyxTierTaggerClient.showAboveHead || !OnyxTierTaggerClient.showSelfName) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null
                && player == client.player
                && !client.options.getPerspective().isFirstPerson()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "renderLabelIfPresent", at = @At("HEAD"))
    private void onRenderLabel(PlayerEntityRenderState state,
                               net.minecraft.client.util.math.MatrixStack matrices,
                               net.minecraft.client.render.command.OrderedRenderCommandQueue queue,
                               net.minecraft.client.render.state.CameraRenderState camera,
                               CallbackInfo ci) {
        if (!OnyxTierTaggerClient.showAboveHead || state.id < 0) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;

        var entity = client.world.getEntityById(state.id);
        if (!(entity instanceof net.minecraft.entity.player.PlayerEntity player)) return;

        var info = OnyxTierTaggerClient.get(player);
        if (info == null || info.topTiers().isEmpty()) return;

        Text baseName = state.playerName;
        if (baseName == null) {
            String profileName = player.getGameProfile().name();
            if (profileName == null || profileName.isBlank()) return;
            baseName = Text.literal(profileName);
        }

        // The vanilla renderer centers the entire label. By constructing the
        // label as LEFT + NAME + RIGHT, the player's name remains visually in
        // the middle without touching Minecraft's label positioning code.
        Text label = buildCenteredLabel(baseName, info);
        state.playerName = label;
    }

    public static Text buildCenteredLabel(Text name, OnyxTierTaggerClient.TierInfo info) {
        var tiers = info.topTiers();
        if (tiers.isEmpty()) return name;

        var first = tiers.get(0);
        Text left = tierWithIcon(first, true);
        if (tiers.size() == 1) {
            return Text.empty()
                    .append(left)
                    .append(Text.literal("    "))
                    .append(name.copy());
        }

        var second = tiers.get(1);
        Text right = tierWithIcon(second, false);
        return Text.empty()
                .append(left)
                .append(Text.literal("    "))
                .append(name.copy())
                .append(Text.literal("    "))
                .append(right);
    }

    private static Text tierWithIcon(OnyxTierTaggerClient.TierEntry tier, boolean iconFirst) {
        String icon = iconChar(tier.gamemode());
        Text iconText = Text.literal(icon).setStyle(Style.EMPTY.withFont(new StyleSpriteSource.Font(ICON_FONT)));
        Text tierText = Text.literal(tier.tier());
        if (iconFirst) {
            return Text.empty().append(iconText).append(Text.literal(" ")).append(tierText);
        }
        return Text.empty().append(tierText).append(Text.literal(" ")).append(iconText);
    }

    private static String iconChar(String gamemode) {
        if (gamemode == null) return "\uE008";
        String mode = gamemode.toLowerCase(java.util.Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");
        return switch (mode) {
            case "sword" -> "\uE001";
            case "uhc" -> "\uE002";
            case "smp" -> "\uE003";
            case "pot", "potion" -> "\uE004";
            case "nethop", "netheriteop", "netherite" -> "\uE005";
            case "mace" -> "\uE006";
            case "axe" -> "\uE007";
            case "vanilla" -> "\uE008";
            default -> "\uE008";
        };
    }
}
