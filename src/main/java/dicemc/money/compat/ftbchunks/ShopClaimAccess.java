package dicemc.money.compat.ftbchunks;

import dev.ftb.mods.ftbchunks.api.ClaimedChunkManager;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.api.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;

/**
 * Reads the same right-click decision FTB Chunks already made.
 * This class is loaded only when that mod is present. It does not grant claim access.
 */
public final class ShopClaimAccess {
	private ShopClaimAccess() {}

	public static boolean blocksShopUse(Player player, InteractionHand hand, BlockPos pos) {
		if (!FTBChunksAPI.api().isManagerLoaded()) return false;
		ClaimedChunkManager manager = FTBChunksAPI.api().getManager();
		if (manager.shouldPreventInteraction(player, hand, pos, Protection.INTERACT_BLOCK, null)) {
			return true;
		}
		return player.getItemInHand(hand).getItem() instanceof BlockItem
				&& manager.shouldPreventInteraction(player, hand, pos, Protection.EDIT_BLOCK, null);
	}
}
