package dicemc.money.api;

import java.util.UUID;

import dicemc.money.storage.MoneyWSD;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

public class MoneyManager implements IMoneyManager{
	//Singleton 
	private static final MoneyManager INSTANCE = new MoneyManager();
	private MoneyManager() {}
	public static MoneyManager get() {return INSTANCE;}
	
	@Override
	public double getBalance(Identifier type, UUID id) {
		return MoneyWSD.get().getBalance(type, id);
	}
	@Override
	public boolean setBalance(Identifier type, UUID id, double value) {
		return MoneyWSD.get().setBalance(type, id, value);
	}
	@Override
	public boolean changeBalance(Identifier type, UUID id, double value) {
		return MoneyWSD.get().changeBalance(type, id, value);
	}
	@Override
	public boolean transferFunds(Identifier fromType, UUID fromID, Identifier toType, UUID toID,
			double value) {
		return MoneyWSD.get().transferFunds(fromType, fromID, toType, toID, value);
	}

}
