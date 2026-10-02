package dicemc.money.compat;

import java.lang.reflect.Method;
import java.util.List;

import dicemc.money.MoneyMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/**
 * Shop and money-command hooks. KubeJS classes load only when that mod is present.
 * A hook that fails leaves the action allowed.
 */
public final class KubeHooks {
	private static final String BRIDGE = "dicemc.money.compat.kubejs.KubeBridge";
	private static volatile Method shop;
	private static volatile Method command;
	private static volatile boolean broken;

	private KubeHooks() {}

	public static boolean allowShop(ServerPlayer player, String token, double price, List<ItemStack> items) {
		Object result = invoke("allowShop", new Class<?>[] {ServerPlayer.class, String.class, double.class, List.class}, new Object[] {player, token, price, items});
		return !(result instanceof Boolean allowed) || allowed;
	}

	public static boolean allowCommand(CommandSourceStack source, String action, double amount) {
		Object result = invoke("allowCommand", new Class<?>[] {CommandSourceStack.class, String.class, double.class}, new Object[] {source, action, amount});
		return !(result instanceof Boolean allowed) || allowed;
	}

	private static Object invoke(String name, Class<?>[] types, Object[] args) {
		if (broken || !ModList.get().isLoaded("kubejs")) return Boolean.TRUE;
		try {
			Method method = method(name, types);
			return method.invoke(null, args);
		} catch (Throwable ex) {
			broken = true;
			MoneyMod.LOGGER.warn("KubeJS money hook failed; the action continues", ex);
			return Boolean.TRUE;
		}
	}

	private static Method method(String name, Class<?>[] types) throws ReflectiveOperationException {
		if ("allowShop".equals(name) && shop != null) return shop;
		if ("allowCommand".equals(name) && command != null) return command;
		Class<?> bridge = Class.forName(BRIDGE);
		Method method = bridge.getMethod(name, types);
		if ("allowShop".equals(name)) shop = method;
		else command = method;
		return method;
	}
}
