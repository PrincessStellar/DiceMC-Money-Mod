package dicemc.money.api;

import java.util.UUID;

import net.minecraft.resources.Identifier;

public interface IMoneyManager {
	double getBalance(Identifier type, UUID id);
	boolean setBalance(Identifier type, UUID id, double value);
	boolean changeBalance(Identifier type, UUID id, double value);
	boolean transferFunds(Identifier fromType, UUID fromID, Identifier toType, UUID toID, double value);
}
