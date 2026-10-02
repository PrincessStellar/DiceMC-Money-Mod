package dicemc.money.compat.kubejs;

import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventHandler;

public final class DiceMoneyEvents {
	public static final EventGroup GROUP = EventGroup.of("DiceMoneyEvents");
	public static final EventHandler SHOP = GROUP.server("shop", () -> ShopTradeKubeEvent.class).hasResult();
	public static final EventHandler COMMAND = GROUP.server("command", () -> MoneyCommandKubeEvent.class).hasResult();

	private DiceMoneyEvents() {}
}
