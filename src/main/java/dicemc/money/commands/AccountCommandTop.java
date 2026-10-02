package dicemc.money.commands;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.util.Pair;

import dicemc.money.MoneyMod.AcctTypes;
import dicemc.money.setup.Config;
import dicemc.money.setup.ServerText;
import dicemc.money.setup.Profiles;
import dicemc.money.storage.MoneyWSD;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public class AccountCommandTop implements Command<CommandSourceStack>{
	private static final AccountCommandTop CMD = new AccountCommandTop();
	
	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("top").executes(CMD));
	}
	
	@Override
	public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		Map<UUID, Double> unsorted = MoneyWSD.get().getAccountMap(AcctTypes.PLAYER.key);
		List<Pair<UUID, Double>> sorted = new ArrayList<>();
		DecimalFormat df = new DecimalFormat("###,###,###,##0.00");
		
		unsorted.entrySet().stream()
			.sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
			.forEachOrdered(x -> sorted.add(Pair.of(x.getKey(), x.getValue())));
		
		int limit = sorted.size() > Config.TOP_SIZE.get() ? Config.TOP_SIZE.get() : sorted.size();
		
		String tkey = limit == 1 ? "message.command.top1" : "message.command.top";
		context.getSource().sendSuccess(() -> ServerText.to(context.getSource(), tkey, limit), false);
		for (int i = 0; i < limit; i++) {
			Pair<UUID, Double> p = sorted.get(i);
			String name = Profiles.name(context.getSource().getServer(), p.getFirst());
			String rank = Integer.toString(i + 1);
			String amount = Config.getFormattedCurrency(df, p.getSecond());
			context.getSource().sendSuccess(() -> ServerText.to(context.getSource(), "message.command.top.entry", rank, name, amount), false);
		}
		return 0;
	}
}
