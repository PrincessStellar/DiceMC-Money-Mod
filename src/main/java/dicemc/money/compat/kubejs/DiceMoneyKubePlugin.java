package dicemc.money.compat.kubejs;

import dev.latvian.mods.kubejs.event.EventGroupRegistry;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingRegistry;
import dicemc.money.api.DiceMoney;

/** Loaded by KubeJS from kubejs.plugins.txt. Absent KubeJS never loads this class. */
public class DiceMoneyKubePlugin implements KubeJSPlugin {
	@Override
	public void registerEvents(EventGroupRegistry registry) {
		registry.register(DiceMoneyEvents.GROUP);
	}

	@Override
	public void registerBindings(BindingRegistry bindings) {
		bindings.add("DiceMoney", DiceMoney.class);
	}
}
