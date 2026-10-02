package dicemc.money.setup;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;

public final class Profiles {
	private Profiles() {}

	public static Optional<NameAndId> byName(MinecraftServer server, String name) {
		return server.services().nameToIdCache().get(name);
	}

	public static Optional<NameAndId> byId(MinecraftServer server, UUID id) {
		return server.services().nameToIdCache().get(id);
	}

	public static String name(MinecraftServer server, UUID id) {
		if (server == null || id == null) return "unknown";
		return byId(server, id).map(NameAndId::name).orElse(id.toString());
	}
}
