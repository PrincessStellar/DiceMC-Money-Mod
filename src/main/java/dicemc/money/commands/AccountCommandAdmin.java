package dicemc.money.commands;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dicemc.money.MoneyMod;
import dicemc.money.compat.KubeHooks;
import dicemc.money.MoneyMod.AcctTypes;
import dicemc.money.setup.Config;
import dicemc.money.setup.ServerText;
import dicemc.money.setup.OpCheck;
import dicemc.money.setup.Profiles;
import dicemc.money.storage.DatabaseManager;
import dicemc.money.storage.MoneyWSD;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

public class AccountCommandAdmin {
	private static final int MAX_TARGETS = 100;

	public static ArgumentBuilder<CommandSourceStack, ?> register(CommandDispatcher<CommandSourceStack> dispatcher) {
		return Commands.literal("admin")
				.requires(source -> OpCheck.has(source, Config.ADMIN_LEVEL.get()))
				.then(Commands.literal("byName")
					.then(Commands.literal("balance")
						.then(Commands.argument("player", StringArgumentType.word())
							.executes(AccountCommandAdmin::balance)
							.then(Commands.argument("more", StringArgumentType.greedyString())
								.executes(AccountCommandAdmin::balance))))
					.then(Commands.argument("action", StringArgumentType.word())
						.suggests((c, b) -> b.suggest("set").suggest("give").suggest("take").buildFuture())
						.then(Commands.argument("player", StringArgumentType.word())
							.then(Commands.argument("amount", DoubleArgumentType.doubleArg(0d))
								.executes(AccountCommandAdmin::process)
								.then(Commands.argument("more", StringArgumentType.greedyString())
									.executes(AccountCommandAdmin::process)))))
					.then(Commands.literal("transfer")
						.then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
							.then(Commands.argument("from", StringArgumentType.word())
								.then(Commands.argument("to", StringArgumentType.word())
									.executes(AccountCommandAdmin::transfer)
									.then(Commands.argument("more", StringArgumentType.greedyString())
										.executes(AccountCommandAdmin::transfer)))))))
				.then(Commands.literal("online")
					.then(Commands.literal("balance")
						.then(Commands.argument("player", EntityArgument.players())
							.executes(AccountCommandAdmin::balance)
							.then(Commands.argument("more", StringArgumentType.greedyString())
								.executes(AccountCommandAdmin::balance))))
					.then(Commands.argument("action", StringArgumentType.word())
						.suggests((c, b) -> b.suggest("set").suggest("give").suggest("take").buildFuture())
						.then(Commands.argument("player", EntityArgument.players())
							.then(Commands.argument("amount", DoubleArgumentType.doubleArg(0d))
								.executes(AccountCommandAdmin::process)
								.then(Commands.argument("more", StringArgumentType.greedyString())
									.executes(AccountCommandAdmin::process)))))
					.then(Commands.literal("transfer")
						.then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
							.then(Commands.argument("from", EntityArgument.player())
								.then(Commands.argument("to", EntityArgument.players())
									.executes(AccountCommandAdmin::transfer)
									.then(Commands.argument("more", StringArgumentType.greedyString())
										.executes(AccountCommandAdmin::transfer)))))));
	}

	private record Target(String label, NameAndId profile) {}

	public static int process(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		double value = DoubleArgumentType.getDouble(context, "amount");
		if (!Double.isFinite(value)) {
			context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.change.failure"));
			return 1;
		}
		String option = StringArgumentType.getString(context, "action");
		if (!option.equals("set") && !option.equals("give") && !option.equals("take")) {
			context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.change.failure"));
			return 1;
		}
		List<Target> targets = targetsOf(context, "player", true);
		if (targets == null) {
			context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.too_many"));
			return 1;
		}
		if (!KubeHooks.allowCommand(context.getSource(), option, value)) {
			context.getSource().sendFailure(ServerText.to(context.getSource(), "message.commandfailed"));
			return 1;
		}
		MoneyWSD wsd = MoneyWSD.get();
		Set<UUID> seen = new HashSet<>();
		int ok = 0;
		for (Target target : targets) {
			if (target.profile == null) {
				context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.playernotfound.named", target.label));
				continue;
			}
			String shown = target.profile.name();
			if (!seen.add(target.profile.id())) {
				context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.duplicate", shown));
				continue;
			}
			UUID pid = target.profile.id();
			boolean result = switch (option) {
				case "set" -> wsd.setBalance(AcctTypes.PLAYER.key, pid, value);
				case "give" -> wsd.changeBalance(AcctTypes.PLAYER.key, pid, value);
				case "take" -> wsd.changeBalance(AcctTypes.PLAYER.key, pid, -value);
				default -> false;
			};
			if (!result) {
				String key = option.equals("set") ? "message.command.set.failure.player" : "message.command.change.failure.player";
				context.getSource().sendFailure(ServerText.to(context.getSource(), key, shown));
				continue;
			}
			history(context, pid, shown, value, switch (option) {
				case "set" -> "Admin Set Command";
				case "give" -> "Admin Give Command";
				default -> "Admin Take Command";
			});
			String money = Config.getFormattedCurrency(value);
			if (option.equals("set")) {
				context.getSource().sendSuccess(() -> ServerText.to(context.getSource(), "message.command.set.success", shown, money), true);
			} else if (option.equals("give")) {
				context.getSource().sendSuccess(() -> ServerText.to(context.getSource(), "message.command.give.success", money, shown), true);
			} else {
				context.getSource().sendSuccess(() -> ServerText.to(context.getSource(), "message.command.take.success", money, shown), true);
			}
			ok++;
		}
		return ok > 0 ? ok : 1;
	}

	public static int balance(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		List<Target> targets = targetsOf(context, "player", true);
		if (targets == null) {
			context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.too_many"));
			return 1;
		}
		Set<UUID> seen = new HashSet<>();
		int ok = 0;
		for (Target target : targets) {
			if (target.profile == null) {
				context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.playernotfound.named", target.label));
				continue;
			}
			String shown = target.profile.name();
			if (!seen.add(target.profile.id())) {
				context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.duplicate", shown));
				continue;
			}
			double balP = MoneyWSD.get().getBalance(AcctTypes.PLAYER.key, target.profile.id());
			String money = Config.getFormattedCurrency(balP);
			context.getSource().sendSuccess(() -> ServerText.to(context.getSource(), "message.command.balance.other", shown, money), true);
			ok++;
		}
		return ok > 0 ? ok : 1;
	}

	public static int transfer(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		double value = DoubleArgumentType.getDouble(context, "amount");
		double funds = Math.abs(value);
		if (!Double.isFinite(value) || !Double.isFinite(funds)) {
			context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.change.failure"));
			return 1;
		}
		boolean byName = isStringArg(context, "from");
		NameAndId fromPlayer = byName
				? Profiles.byName(context.getSource().getServer(), StringArgumentType.getString(context, "from")).orElse(null)
				: EntityArgument.getPlayer(context, "from").nameAndId();
		if (fromPlayer == null) {
			String label = byName ? StringArgumentType.getString(context, "from") : "?";
			context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.playernotfound.named", label));
			return 1;
		}
		List<Target> recipients = targetsOf(context, "to", false);
		if (recipients == null) {
			context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.too_many"));
			return 1;
		}
		List<NameAndId> pay = new ArrayList<>();
		List<NameAndId> same = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		List<String> duplicate = new ArrayList<>();
		Set<UUID> seen = new HashSet<>();
		for (Target target : recipients) {
			if (target.profile == null) {
				missing.add(target.label);
				continue;
			}
			if (!seen.add(target.profile.id())) {
				duplicate.add(target.profile.name());
				continue;
			}
			if (target.profile.id().equals(fromPlayer.id())) same.add(target.profile);
			else pay.add(target.profile);
		}
		if (!KubeHooks.allowCommand(context.getSource(), "transfer", funds)) {
			context.getSource().sendFailure(ServerText.to(context.getSource(), "message.commandfailed"));
			return 1;
		}
		double total = funds * pay.size();
		double fromBal = MoneyWSD.get().getBalance(AcctTypes.PLAYER.key, fromPlayer.id());
		boolean covered = Double.isFinite(total) && fromBal >= total;
		if (!covered) {
			for (String name : missing) {
				context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.playernotfound.named", name));
			}
			for (String name : duplicate) {
				context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.duplicate", name));
			}
			for (NameAndId ignored : same) {
				context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.transfer.failure.player", fromPlayer.name()));
			}
			for (NameAndId target : pay) {
				context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.transfer.failure.player", target.name()));
			}
			return 1;
		}
		for (String name : missing) {
			context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.playernotfound.named", name));
		}
		for (String name : duplicate) {
			context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.duplicate", name));
		}
		List<NameAndId> moved = new ArrayList<>();
		for (NameAndId target : pay) {
			boolean result = MoneyWSD.get().transferFunds(AcctTypes.PLAYER.key, fromPlayer.id(), AcctTypes.PLAYER.key, target.id(), funds);
			if (!result) {
				context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.transfer.failure.player", target.name()));
				boolean restored = undoTransfers(fromPlayer, moved, funds);
				if (restored) {
					context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.transfer.reverted"));
				} else {
					context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.transfer.undo"));
				}
				return 1;
			}
			moved.add(target);
			postTransfer(context, fromPlayer, target, funds);
			String money = Config.getFormattedCurrency(funds);
			String shown = target.name();
			context.getSource().sendSuccess(() -> ServerText.to(context.getSource(), "message.command.transfer.success", money, shown), true);
		}
		for (NameAndId target : same) {
			MoneyWSD.get().transferFunds(AcctTypes.PLAYER.key, fromPlayer.id(), AcctTypes.PLAYER.key, target.id(), funds);
			String money = Config.getFormattedCurrency(funds);
			String shown = target.name();
			context.getSource().sendSuccess(() -> ServerText.to(context.getSource(), "message.command.transfer.success", money, shown), true);
		}
		return pay.isEmpty() && same.isEmpty() ? 1 : pay.size() + same.size();
	}

	/** Puts a refused batch back. Returns false when a recipient could not be reversed. */
	private static boolean undoTransfers(NameAndId fromPlayer, List<NameAndId> moved, double funds) {
		boolean all = true;
		for (int i = moved.size() - 1; i >= 0; i--) {
			NameAndId target = moved.get(i);
			boolean back = MoneyWSD.get().transferFunds(AcctTypes.PLAYER.key, target.id(), AcctTypes.PLAYER.key, fromPlayer.id(), funds);
			if (!back) all = false;
		}
		return all;
	}

	private static void postTransfer(CommandContext<CommandSourceStack> context, NameAndId fromPlayer, NameAndId toPlayer, double value) {
		if (!Config.ENABLE_HISTORY.get() || MoneyMod.dbm == null) return;
		if (fromPlayer.id().equals(toPlayer.id())) return;
		boolean isPlayer = context.getSource().getEntity() instanceof ServerPlayer;
		UUID srcID = isPlayer ? context.getSource().getEntity().getUUID() : DatabaseManager.NIL;
		String srcName = isPlayer ? Profiles.name(context.getSource().getServer(), srcID) : "Console";
		MoneyMod.dbm.postEntry(System.currentTimeMillis(), fromPlayer.id(), AcctTypes.PLAYER.key, fromPlayer.name(),
				toPlayer.id(), AcctTypes.PLAYER.key, toPlayer.name(), value, "Admin Transfer Command Executed by: " + srcName);
	}

	/**
	 * @return null when the list is too long. Nothing has been paid in that case.
	 *         Online targets are players in the world. byName targets come from the name cache.
	 */
	private static List<Target> targetsOf(CommandContext<CommandSourceStack> context, String argument, boolean argumentSelectsOnline) throws CommandSyntaxException {
		MinecraftServer server = context.getSource().getServer();
		boolean byName = isStringArg(context, argument);
		List<Target> targets = new ArrayList<>();
		if (byName) {
			targets.add(named(server, StringArgumentType.getString(context, argument), false));
		} else if (argumentSelectsOnline || "to".equals(argument)) {
			for (ServerPlayer player : EntityArgument.getPlayers(context, argument)) {
				targets.add(new Target(player.getGameProfile().name(), player.nameAndId()));
			}
		}
		for (String extra : extraWords(context)) {
			targets.add(named(server, extra, !byName));
		}
		if (targets.size() > MAX_TARGETS) return null;
		return targets;
	}

	private static Target named(MinecraftServer server, String name, boolean onlineOnly) {
		if (onlineOnly) {
			ServerPlayer player = server.getPlayerList().getPlayerByName(name);
			if (player == null) return new Target(name, null);
			return new Target(name, player.nameAndId());
		}
		return Profiles.byName(server, name).map(profile -> new Target(name, profile)).orElseGet(() -> new Target(name, null));
	}

	private static List<String> extraWords(CommandContext<CommandSourceStack> context) {
		try {
			String more = StringArgumentType.getString(context, "more");
			String[] parts = more.trim().split("\\s+");
			List<String> out = new ArrayList<>();
			for (String part : parts) {
				if (!part.isEmpty()) out.add(part);
			}
			return out;
		} catch (IllegalArgumentException ex) {
			return List.of();
		}
	}

	private static boolean isStringArg(CommandContext<CommandSourceStack> context, String name) {
		try {
			StringArgumentType.getString(context, name);
			return true;
		} catch (IllegalArgumentException ex) {
			return false;
		}
	}

	private static void history(CommandContext<CommandSourceStack> context, UUID target, String targetName, double value, String reason) {
		if (!Config.ENABLE_HISTORY.get() || MoneyMod.dbm == null) return;
		boolean isPlayer = context.getSource().getEntity() instanceof ServerPlayer;
		UUID srcID = isPlayer ? context.getSource().getEntity().getUUID() : DatabaseManager.NIL;
		Identifier srcType = isPlayer ? AcctTypes.PLAYER.key : AcctTypes.SERVER.key;
		String srcName = isPlayer ? Profiles.name(context.getSource().getServer(), srcID) : "Console";
		MoneyMod.dbm.postEntry(System.currentTimeMillis(), srcID, srcType, srcName, target, AcctTypes.PLAYER.key, targetName, value, reason);
	}
}
