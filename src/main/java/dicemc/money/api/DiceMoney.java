package dicemc.money.api;

import dicemc.money.MoneyMod.AcctTypes;
import dicemc.money.storage.MoneyWSD;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/** Server-script balance calls. They use the same accounts as the commands and do not move items. */
public final class DiceMoney {
	private DiceMoney() {}

	public static double getBalance(Player player) {
		if (!(player instanceof ServerPlayer serverPlayer)) return 0;
		return MoneyWSD.get().getBalance(AcctTypes.PLAYER.key, serverPlayer.getUUID());
	}

	/** Adds a finite amount of 0 or more. A negative or non-finite amount moves nothing. */
	public static boolean give(Player player, double amount) {
		if (!(player instanceof ServerPlayer serverPlayer)) return false;
		if (!Double.isFinite(amount) || amount < 0) return false;
		if (amount == 0) return true;
		return MoneyWSD.get().changeBalance(AcctTypes.PLAYER.key, serverPlayer.getUUID(), amount);
	}

	/** Removes a finite amount the player can pay. Otherwise moves nothing and returns false. */
	public static boolean take(Player player, double amount) {
		if (!(player instanceof ServerPlayer serverPlayer)) return false;
		return MoneyWSD.get().tryTake(AcctTypes.PLAYER.key, serverPlayer.getUUID(), amount);
	}
}
