package dicemc.money.setup;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.player.Player;

/** Maps the config op level (0-4) onto 26.1 command permissions without lowering it. */
public final class OpCheck {
	private OpCheck() {}

	public static boolean has(PermissionSet permissions, int level) {
		int clamped = Math.max(0, Math.min(4, level));
		return permissions.hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(clamped)));
	}

	public static boolean has(Player player, int level) {
		return has(player.permissions(), level);
	}

	public static boolean has(CommandSourceStack source, int level) {
		return has(source.permissions(), level);
	}
}
