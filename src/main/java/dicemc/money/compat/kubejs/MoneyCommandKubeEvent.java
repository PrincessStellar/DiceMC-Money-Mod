package dicemc.money.compat.kubejs;

import dicemc.money.api.DiceMoney;
import dev.latvian.mods.kubejs.event.KubeEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/** Fired before a money command writes a balance. Cancel does not undo a charge that already succeeded. */
public class MoneyCommandKubeEvent implements KubeEvent {
	private final CommandSourceStack source;
	private final String action;
	private final double amount;

	public MoneyCommandKubeEvent(CommandSourceStack source, String action, double amount) {
		this.source = source;
		this.action = action;
		this.amount = amount;
	}

	public ServerPlayer getPlayer() {
		return source.getPlayer();
	}

	public String getAction() {
		return action;
	}

	public String getCommand() {
		return "money";
	}

	public double getAmount() {
		return amount;
	}

	/** Takes the amount from the player who typed the command. The console cannot be charged. */
	public boolean charge(double value) {
		ServerPlayer player = source.getPlayer();
		if (player == null) return false;
		return DiceMoney.take(player, value);
	}
}
