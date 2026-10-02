package dicemc.money.setup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dicemc.money.MoneyMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Writes money sentences on the server from en_us.json and pt_br.json.
 * The client does not need this mod. Item arguments stay components.
 */
public final class ServerText {
	private static final String FALLBACK = "The command could not be completed.";
	private static final Map<String, String> EN = load("en_us");
	private static final Map<String, String> PT = load("pt_br");
	private static final Set<String> MISSING = new HashSet<>();

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

	public static MutableComponent items(Player player, List<ItemStack> list) {
		String lang = player instanceof ServerPlayer serverPlayer ? language(serverPlayer) : "en_us";
		return items(lang, list);
	}

	public static MutableComponent items(String lang, List<ItemStack> list) {
		String separator = text(lang, "message.shop.separator");
		if (separator == null) separator = ", ";
		String stack = text(lang, "message.shop.stack");
		if (stack == null) stack = "%s x %s";
		MutableComponent out = Component.empty();
		boolean first = true;
		for (ItemStack item : merged(list)) {
			if (!first && !separator.isEmpty()) out.append(Component.literal(separator));
			first = false;
			out.append(fill(stack, Integer.toString(item.getCount()), item.getDisplayName()));
		}
		return out;
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
		return fill(pattern, args);
	}

	/** Portuguese falls back to English. A missing English line returns null. */
	private static String text(String lang, String key) {
		if ("pt_br".equals(lang)) {
			String portuguese = PT.get(key);
			if (portuguese != null) return portuguese;
		}
		return EN.get(key);
	}

	private static MutableComponent fill(String pattern, Object... args) {
		MutableComponent out = Component.empty();
		int arg = 0;
		int cursor = 0;
		while (cursor < pattern.length()) {
			int mark = pattern.indexOf("%s", cursor);
			if (mark < 0) {
				out.append(Component.literal(pattern.substring(cursor)));
				break;
			}
			if (mark > cursor) out.append(Component.literal(pattern.substring(cursor, mark)));
			Object value = arg < args.length ? args[arg++] : "";
			if (value instanceof Component component) out.append(component);
			else out.append(Component.literal(String.valueOf(value)));
			cursor = mark + 2;
		}
		return out;
	}

	private static List<ItemStack> merged(List<ItemStack> list) {
		List<ItemStack> items = new ArrayList<>();
		if (list == null) return items;
		for (ItemStack incoming : list) {
			if (incoming == null || incoming.isEmpty()) continue;
			boolean hadMatch = false;
			for (ItemStack existing : items) {
				if (ItemStack.isSameItemSameComponents(incoming, existing)) {
					existing.grow(incoming.getCount());
					hadMatch = true;
					break;
				}
			}
			if (!hadMatch) items.add(incoming.copy());
		}
		return items;
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
