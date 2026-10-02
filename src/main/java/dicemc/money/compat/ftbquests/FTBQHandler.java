package dicemc.money.compat.ftbquests;

import dev.ftb.mods.ftblibrary.icon.Icon;
import dev.ftb.mods.ftbquests.quest.reward.RewardType;
import dev.ftb.mods.ftbquests.quest.reward.RewardTypes;
import dicemc.money.MoneyMod;
import net.minecraft.resources.Identifier;

public final class FTBQHandler {
	public static final RewardType MONEY_REWARD = RewardTypes.register(
			Identifier.fromNamespaceAndPath(MoneyMod.MOD_ID, "moneyreward"),
			MoneyReward::new,
			() -> Icon.getIcon(MoneyMod.MOD_ID + ":textures/moneybag.png"));

	private FTBQHandler() {}

	public static void init() {
		MoneyMod.LOGGER.info("FTB Quests money reward is registered");
	}
}
