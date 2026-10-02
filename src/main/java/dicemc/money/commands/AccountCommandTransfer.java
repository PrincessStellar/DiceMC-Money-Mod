package dicemc.money.commands;

import java.util.UUID;

import com.mojang.brigadier.Command;
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
import dicemc.money.setup.Profiles;
import dicemc.money.storage.MoneyWSD;
import net.minecraft.server.players.NameAndId;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

public class AccountCommandTransfer implements Command<CommandSourceStack>{
private static final AccountCommandTransfer CMD = new AccountCommandTransfer();
	
	public static ArgumentBuilder<CommandSourceStack, ?> register(CommandDispatcher<CommandSourceStack> dispatcher) {
		return Commands.literal("transfer")
				.then(Commands.argument("value", DoubleArgumentType.doubleArg(0d))
						.then(Commands.argument("recipient", StringArgumentType.word())
								.executes(CMD)));

	}

	@Override
	public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		double value = DoubleArgumentType.getDouble(context, "value");
		String recipientName = StringArgumentType.getString(context, "recipient");
		NameAndId recipient = Profiles.byName(context.getSource().getServer(), recipientName).orElse(null);
		if (recipient != null && !KubeHooks.allowCommand(context.getSource(), "transfer", value)) {
			context.getSource().sendSuccess(() -> ServerText.to(context.getSource(), "message.command.transfer.failure"), false);
			return 0;
		}
		if (recipient != null && MoneyWSD.get().transferFunds(AcctTypes.PLAYER.key, player.getUUID(), AcctTypes.PLAYER.key, recipient.id(), value)) {
			if (Config.ENABLE_HISTORY.get() && MoneyMod.dbm != null) {
				MoneyMod.dbm.postEntry(System.currentTimeMillis(), player.getUUID(), AcctTypes.PLAYER.key, player.getName().getString()
						, recipient.id(), AcctTypes.PLAYER.key, recipient.name()
						, value, "Player Transfer Command. From is who executed");
			}
			context.getSource().sendSuccess(() -> ServerText.to(context.getSource(), "message.command.transfer.success", Config.getFormattedCurrency(Math.abs(value)), recipientName), true);
		}
		else 
			context.getSource().sendSuccess(() -> ServerText.to(context.getSource(), "message.command.transfer.failure"), false);
		return 0;
	}
}
