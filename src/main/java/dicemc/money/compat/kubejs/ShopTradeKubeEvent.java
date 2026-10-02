package dicemc.money.compat.kubejs;

import java.util.ArrayList;
import java.util.List;

import dev.latvian.mods.kubejs.event.KubeEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Fired after the offer is known and before items or money move. The item list is a copy. */
public class ShopTradeKubeEvent implements KubeEvent {
	private final ServerPlayer player;
	private final String type;
	private final double price;
	private final List<ItemStack> items;

	public ShopTradeKubeEvent(ServerPlayer player, String type, double price, List<ItemStack> items) {
		this.player = player;
		this.type = type;
		this.price = price;
		List<ItemStack> copies = new ArrayList<>();
		if (items != null) {
			for (ItemStack stack : items) {
				if (stack != null) copies.add(stack.copy());
			}
		}
		this.items = List.copyOf(copies);
	}

	public ServerPlayer getPlayer() {
		return player;
	}

	public String getType() {
		return type;
	}

	public double getPrice() {
		return price;
	}

	public List<ItemStack> getItems() {
		return items;
	}
}
