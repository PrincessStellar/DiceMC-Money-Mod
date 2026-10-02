package dicemc.money.setup;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.text.DecimalFormat;

public class Config {
	public static ModConfigSpec SERVER_CONFIG;

	public static ModConfigSpec.LongValue STARTING_FUNDS;
	public static ModConfigSpec.ConfigValue<String> CURRENCY_SYMBOL;
	public static ModConfigSpec.ConfigValue<Boolean> CURRENCY_POSITION;
	public static ModConfigSpec.IntValue ADMIN_LEVEL;
	public static ModConfigSpec.IntValue SHOP_LEVEL;
	public static ModConfigSpec.DoubleValue LOSS_ON_DEATH;
	public static ModConfigSpec.IntValue TOP_SIZE;
	public static ModConfigSpec.ConfigValue<Boolean> ENABLE_HISTORY;

	static {
		ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
		setup(builder);
		SERVER_CONFIG = builder.build();
	}

	private static void setup(ModConfigSpec.Builder builder) {
		builder.comment("Dinheiro.").push("money");
		STARTING_FUNDS = builder
				.comment("Dinheiro inicial de uma conta nova.", "De 0 a 1000000000.")
				.defineInRange("starting_funds", 1000L, 0L, 1_000_000_000L);
		builder.pop();

		builder.comment("Exibição.").push("display");
		CURRENCY_SYMBOL = builder
				.comment("Símbolo ao lado do valor.")
				.define("currency_symbol", "$");
		CURRENCY_POSITION = builder
				.comment("true coloca o símbolo à esquerda.")
				.define("currency_symbol_on_left", true);
		builder.pop();

		builder.comment("Permissões.").push("permissions");
		ADMIN_LEVEL = builder
				.comment("Nível de operador dos comandos de admin.", "De 0 a 4.")
				.defineInRange("admin_level", 2, 0, 4);
		SHOP_LEVEL = builder
				.comment("Nível de operador para criar uma loja.", "De 0 a 4.")
				.defineInRange("shop_level", 0, 0, 4);
		builder.pop();

		builder.comment("Morte.").push("death");
		LOSS_ON_DEATH = builder
				.comment("Fração do saldo perdida ao morrer.", "De 0 a 1.")
				.defineInRange("loss_on_death", 0D, 0D, 1D);
		builder.pop();

		builder.comment("Ranking.").push("ranking");
		TOP_SIZE = builder
				.comment("Quantidade de nomes no /top.", "De 0 a 100.")
				.defineInRange("top_size", 3, 0, 100);
		builder.pop();

		builder.comment("O histórico fica desligado.").push("history");
		ENABLE_HISTORY = builder
				.comment("O histórico fica desligado.")
				.define("enable_history", false);
		builder.pop();
	}

	public static String getFormattedCurrency(double value) {
		return CURRENCY_POSITION.get() ? CURRENCY_SYMBOL.get() + value : value + CURRENCY_SYMBOL.get();
	}

	public static String getFormattedCurrency(DecimalFormat df, double value) {
		return CURRENCY_POSITION.get() ? CURRENCY_SYMBOL.get() + df.format(value) : df.format(value) + CURRENCY_SYMBOL.get();
	}
}
