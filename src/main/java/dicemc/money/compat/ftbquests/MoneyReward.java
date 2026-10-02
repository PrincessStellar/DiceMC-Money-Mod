package dicemc.money.compat.ftbquests;

import de.marhali.json5.Json5Object;
import dev.ftb.mods.ftblibrary.client.config.EditableConfigGroup;
import dev.ftb.mods.ftblibrary.json5.Json5Util;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.reward.RewardType;
import dicemc.money.MoneyMod.AcctTypes;
import dicemc.money.setup.Config;
import dicemc.money.setup.ServerText;
import dicemc.money.storage.MoneyWSD;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/** Quest reward that pays the claimant from the money mod. Negative and non-finite amounts are ignored. */
public class MoneyReward extends Reward {
	public double amount = 0;

	public MoneyReward(long id, Quest quest) {
		super(id, quest);
	}

	@Override
	public RewardType getType() {
		return FTBQHandler.MONEY_REWARD;
	}

	@Override
	public void claim(ServerPlayer player, boolean notify) {
		if (player == null || !Double.isFinite(amount) || amount <= 0) return;
		if (MoneyWSD.get().changeBalance(AcctTypes.PLAYER.key, player.getUUID(), amount)) {
			player.sendSystemMessage(ServerText.to(player, "message.reward.money", Config.getFormattedCurrency(amount)));
		} else {
			player.sendSystemMessage(ServerText.to(player, "message.reward.money.failure"));
		}
	}

	@Override
	public void writeData(Json5Object json, HolderLookup.Provider provider) {
		super.writeData(json, provider);
		json.addProperty("amount", amount);
	}

	@Override
	public void readData(Json5Object json, HolderLookup.Provider provider) {
		super.readData(json, provider);
		amount = finiteAmount(Json5Util.getDouble(json, "amount").orElse(0d));
	}

	@Override
	public void writeNetData(RegistryFriendlyByteBuf buffer) {
		super.writeNetData(buffer);
		buffer.writeDouble(amount);
	}

	@Override
	public void readNetData(RegistryFriendlyByteBuf buffer) {
		super.readNetData(buffer);
		amount = finiteAmount(buffer.readDouble());
	}

	@Override
	public void fillConfigGroup(EditableConfigGroup config) {
		super.fillConfigGroup(config);
		config.addDouble("amount", amount, input -> amount = input == null ? 0 : finiteAmount(input), 1d, 0d, Double.MAX_VALUE)
				.setNameKey("ftbquests.reward.dicemcmm.moneyreward.amount");
	}

	@Override
	public MutableComponent getAltTitle() {
		return ServerText.english("ftbquests.reward.dicemcmm.moneyreward.title", Config.getFormattedCurrency(amount));
	}

	private static double finiteAmount(double value) {
		if (!Double.isFinite(value) || value < 0) return 0;
		return value;
	}
}
