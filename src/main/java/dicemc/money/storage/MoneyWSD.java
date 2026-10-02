package dicemc.money.storage;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dicemc.money.MoneyMod;
import dicemc.money.MoneyMod.AcctTypes;
import dicemc.money.api.IMoneyManager;
import dicemc.money.setup.Config;
import dicemc.money.setup.Profiles;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

public class MoneyWSD extends SavedData implements IMoneyManager {
	public static final Codec<MoneyWSD> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.unboundedMap(Identifier.CODEC, Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.DOUBLE))
					.optionalFieldOf("accounts", Map.of())
					.forGetter(sd -> sd.accounts)
	).apply(instance, MoneyWSD::new));

	public static final SavedDataType<MoneyWSD> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath(MoneyMod.MOD_ID, "data"),
			MoneyWSD::new,
			CODEC);

	private final Map<Identifier, Map<UUID, Double>> accounts = new HashMap<>();

	public MoneyWSD() {}

	public MoneyWSD(Map<Identifier, Map<UUID, Double>> loaded) {
		loaded.forEach((type, balances) -> accounts.put(type, new HashMap<>(balances)));
	}

	public Map<UUID, Double> getAccountMap(Identifier res) {
		return accounts.getOrDefault(res, new HashMap<>());
	}

	@Override
	public double getBalance(Identifier type, UUID owner) {
		if (type == null || owner == null) return 0;
		accountChecker(type, owner);
		Map<UUID, Double> map = accounts.get(type);
		if (map == null) return 0;
		Double value = map.get(owner);
		if (value == null || !Double.isFinite(value)) return 0;
		return value;
	}

	@Override
	public boolean setBalance(Identifier type, UUID id, double value) {
		if (type == null || id == null || !Double.isFinite(value)) return false;
		accountChecker(type, id);
		Map<UUID, Double> map = accounts.get(type);
		if (map == null) return false;
		map.put(id, value);
		this.setDirty();
		return true;
	}

	@Override
	public boolean changeBalance(Identifier type, UUID id, double value) {
		if (type == null || id == null || !Double.isFinite(value)) return false;
		double current = getBalance(type, id);
		double future = current + value;
		if (!Double.isFinite(future)) return false;
		return setBalance(type, id, future);
	}

	@Override
	public boolean transferFunds(Identifier fromType, UUID fromID, Identifier toType, UUID toID, double value) {
		if (fromType == null || fromID == null || toType == null || toID == null || !Double.isFinite(value)) return false;
		double funds = Math.abs(value);
		if (!Double.isFinite(funds)) return false;
		double fromBal = getBalance(fromType, fromID);
		if (fromBal < funds) return false;
		if (funds == 0 || (fromType.equals(toType) && fromID.equals(toID))) return true;
		double toBal = getBalance(toType, toID);
		double nextFrom = fromBal - funds;
		double nextTo = toBal + funds;
		if (!Double.isFinite(nextFrom) || !Double.isFinite(nextTo)) return false;
		Map<UUID, Double> fromMap = accounts.get(fromType);
		Map<UUID, Double> toMap = accounts.get(toType);
		if (fromMap == null || toMap == null) return false;
		fromMap.put(fromID, nextFrom);
		toMap.put(toID, nextTo);
		this.setDirty();
		return true;
	}

	/** Removes a finite amount the account can pay. Zero moves nothing and succeeds. A short balance is left as it was. */
	public boolean tryTake(Identifier type, UUID id, double amount) {
		if (type == null || id == null || !Double.isFinite(amount) || amount < 0) return false;
		if (amount == 0) return true;
		double balance = getBalance(type, id);
		if (balance < amount) return false;
		return changeBalance(type, id, -amount);
	}

	public void accountChecker(Identifier type, UUID owner) {
		if (type == null) return;
		if (!accounts.containsKey(type)) {
			accounts.put(type, new HashMap<>());
			this.setDirty();
		}
		Map<UUID, Double> map = accounts.get(type);
		if (owner == null || map == null || map.containsKey(owner)) return;
		double start = Config.STARTING_FUNDS.get();
		if (!Double.isFinite(start) || start < 0) start = 0;
		map.put(owner, start);
		if (Config.ENABLE_HISTORY.get() && MoneyMod.dbm != null && MoneyMod.dbm.server != null) {
			MoneyMod.dbm.postEntry(System.currentTimeMillis(), DatabaseManager.NIL, AcctTypes.SERVER.key, "Server",
					owner, type, Profiles.name(MoneyMod.dbm.server, owner),
					start, "Starting Funds Deposit");
		}
		this.setDirty();
	}

	public static MoneyWSD get() {
		if (ServerLifecycleHooks.getCurrentServer() != null) {
			return ServerLifecycleHooks.getCurrentServer().overworld().getDataStorage().computeIfAbsent(TYPE);
		}
		return new MoneyWSD();
	}
}
