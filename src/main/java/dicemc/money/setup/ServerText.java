package dicemc.money.setup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dicemc.money.MoneyMod;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Writes money sentences on the server from en_us.json and pt_br.json.
 * The client does not need this mod. Chat lines do not name items.
 */
public final class ServerText {
	private static final String FALLBACK = "The command could not be completed.";
	private static final Map<String, String> EN = load("en_us");
	private static final Map<String, String> PT = load("pt_br");
	private static final Set<String> MISSING = new HashSet<>();
	/** Named "white" is not used. A client that substitutes that name would paint the whole sentence. */
	private static final Style PURCHASE_WORDS = Style.EMPTY
			.withColor(TextColor.fromRgb(0xFFFFFF))
			.withBold(Boolean.FALSE)
			.withItalic(Boolean.FALSE)
			.withUnderlined(Boolean.FALSE)
			.withStrikethrough(Boolean.FALSE)
			.withObfuscated(Boolean.FALSE);
	private static final Style PURCHASE_PRICE = Style.EMPTY
			.withColor(TextColor.fromRgb(0x55FF55))
			.withBold(Boolean.FALSE)
			.withItalic(Boolean.FALSE)
			.withUnderlined(Boolean.FALSE)
			.withStrikethrough(Boolean.FALSE)
			.withObfuscated(Boolean.FALSE);

	private ServerText() {}

	/** pt_br uses pt_br.json. Every other reported language uses en_us. */
	public static String language(ServerPlayer player) {
		if (player == null) return "en_us";
		String reported = player.getLanguage();
		if (reported != null && reported.equalsIgnoreCase("pt_br")) return "pt_br";
		return "en_us";
	}

	public static MutableComponent to(Player player, String key, Object... args) {
		String lang = player instanceof ServerPlayer serverPlayer ? language(serverPlayer) : "en_us";
		return format(lang, key, args);
	}

	public static MutableComponent to(CommandSourceStack source, String key, Object... args) {
		if (source != null && source.getEntity() instanceof ServerPlayer serverPlayer) return to(serverPlayer, key, args);
		return english(key, args);
	}

	public static MutableComponent english(String key, Object... args) {
		return format("en_us", key, args);
	}

	/** Plain container word for a finished sentence. Not an item component. */
	public static String containerWord(Player player, String kind) {
		String lang = player instanceof ServerPlayer serverPlayer ? language(serverPlayer) : "en_us";
		return containerWord(lang, kind);
	}

	public static String containerWord(String lang, String kind) {
		String name = kind == null ? "other" : kind;
		if (!name.equals("chest") && !name.equals("barrel") && !name.equals("other")) name = "other";
		String word = text(lang, "message.shop.container." + name);
		if (word == null) word = text("en_us", "message.shop.container." + name);
		if (word == null) return "container";
		return word;
	}

	/** Logs the English sentence, then sends each player the sentence for their language. */
	public static void broadcast(MinecraftServer server, String key, Function<String, Object[]> argsForLang) {
		if (server == null || argsForLang == null) return;
		MutableComponent logged = format("en_us", key, argsForLang.apply("en_us"));
		server.getPlayerList().broadcastSystemMessage(logged, player -> format(language(player), key, argsForLang.apply(language(player))), false);
	}

	private static MutableComponent format(String lang, String key, Object... args) {
		String pattern = text(lang, key);
		if (pattern == null) {
			if (MISSING.add(key)) MoneyMod.LOGGER.warn("Missing translation {}", key);
			pattern = text("en_us", "message.commandfailed");
			if (pattern == null) pattern = FALLBACK;
			args = new Object[0];
		}
		boolean purchase = "message.shop.buy.success".equals(key) || "message.shop.buy.broadcast".equals(key);
		MutableComponent body = fill(pattern, args, purchase);
		if (purchase) return body.withStyle(PURCHASE_WORDS);
		ChatFormatting tone = tone(key);
		if (tone == null) return body;
		return Component.empty().withStyle(tone).append(body);
	}

	/** The money amount in a purchase sentence. The other words stay white. */
	public static MutableComponent greenMoney(String formatted) {
		return Component.literal(formatted == null ? "" : formatted).withStyle(PURCHASE_PRICE);
	}

	/** Sell success is green. Failure is red. A purchase sentence is not colored here. */
	private static ChatFormatting tone(String key) {
		if (key == null || key.equals("message.shop.info")) return null;
		if (key.equals("message.shop.sell.success")
				|| key.equals("message.command.shop.builder.success")) {
			return ChatFormatting.GREEN;
		}
		if (key.startsWith("message.activate.failure.")
				|| key.startsWith("message.shop.buy.failure.")
				|| key.startsWith("message.shop.sell.failure.")
				|| key.equals("message.shop.unknown")
				|| key.equals("message.shop.cancelled")) {
			return ChatFormatting.RED;
		}
		return null;
	}

	/** Portuguese falls back to English. A missing English line returns null. */
	private static String text(String lang, String key) {
		if ("pt_br".equals(lang)) {
			String portuguese = PT.get(key);
			if (portuguese != null) return portuguese;
		}
		return EN.get(key);
	}

	private static MutableComponent fill(String pattern, Object[] args, boolean whiteWords) {
		MutableComponent out = whiteWords ? Component.literal("").withStyle(PURCHASE_WORDS) : Component.empty();
		int arg = 0;
		int cursor = 0;
		while (cursor < pattern.length()) {
			int mark = pattern.indexOf("%s", cursor);
			if (mark < 0) {
				out.append(words(pattern.substring(cursor), whiteWords));
				break;
			}
			if (mark > cursor) out.append(words(pattern.substring(cursor, mark), whiteWords));
			Object value = arg < args.length ? args[arg++] : "";
			if (value instanceof Component component) out.append(component);
			else out.append(words(String.valueOf(value), whiteWords));
			cursor = mark + 2;
		}
		return out;
	}

	private static MutableComponent words(String text, boolean white) {
		MutableComponent literal = Component.literal(text);
		return white ? literal.withStyle(PURCHASE_WORDS) : literal;
	}

	private static Map<String, String> load(String code) {
		Map<String, String> map = new HashMap<>();
		String path = "/assets/dicemcmm/lang/" + code + ".json";
		try (InputStream in = ServerText.class.getResourceAsStream(path)) {
			if (in == null) {
				MoneyMod.LOGGER.warn("Missing translation file {}", code);
				return map;
			}
			JsonElement parsed = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			if (!parsed.isJsonObject()) return map;
			JsonObject object = parsed.getAsJsonObject();
			for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
				if (entry.getValue().isJsonPrimitive()) map.put(entry.getKey(), entry.getValue().getAsString());
			}
		} catch (IOException | RuntimeException ex) {
			MoneyMod.LOGGER.warn("Missing translation file {}", code, ex);
		}
		return map;
	}
}
