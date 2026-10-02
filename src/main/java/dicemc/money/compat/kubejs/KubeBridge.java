package dicemc.money.compat.kubejs;

import java.util.List;

import dev.latvian.mods.kubejs.event.EventResult;
import dev.latvian.mods.kubejs.script.ScriptType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class KubeBridge {
	private KubeBridge() {}

	public static boolean allowShop(ServerPlayer player, String token, double price, List<ItemStack> items) {
		if (!DiceMoneyEvents.SHOP.hasListeners()) return true;
		ShopTradeKubeEvent event = new ShopTradeKubeEvent(player, token, price, items);
		EventResult result = DiceMoneyEvents.SHOP.post(ScriptType.SERVER, event);
		return !result.interruptFalse();
	}

	public static boolean allowCommand(CommandSourceStack source, String action, double amount) {
		if (!DiceMoneyEvents.COMMAND.hasListeners()) return true;
		MoneyCommandKubeEvent event = new MoneyCommandKubeEvent(source, action, amount);
		EventResult result = DiceMoneyEvents.COMMAND.post(ScriptType.SERVER, event);
		return !result.interruptFalse();
	}
}
