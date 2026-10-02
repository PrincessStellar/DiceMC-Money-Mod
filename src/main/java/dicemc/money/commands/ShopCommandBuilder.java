package dicemc.money.commands;

import dicemc.money.setup.ServerText;
import java.util.List;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.DynamicOps;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.WritableBookItem;
import net.minecraft.world.item.component.WritableBookContent;

/**
 * Writes the held book and quill in the format {@code EventHandler} already reads.
 * The page is {@code vending}, one separator character, then the held sample as item data.
 * Neither stack is removed, split, or given again.
 */
public class ShopCommandBuilder implements Command<CommandSourceStack> {
	public static final ShopCommandBuilder CMD = new ShopCommandBuilder();

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("shop")
				.then(Commands.literal("builder")
						.executes(CMD)));
	}

	@Override
	public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
			context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.shop.builder.player"));
			return 0;
		}
		ItemStack main = player.getItemInHand(InteractionHand.MAIN_HAND);
		ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);
		boolean mainBook = main.getItem() instanceof WritableBookItem;
		boolean offBook = off.getItem() instanceof WritableBookItem;
		if (mainBook == offBook) {
			context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.shop.builder"));
			return 0;
		}
		InteractionHand bookHand = mainBook ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
		ItemStack book = mainBook ? main : off;
		ItemStack sample = mainBook ? off : main;
		if (sample.isEmpty()) {
			context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.shop.builder"));
			return 0;
		}
		if (book.getCount() != 1) {
			context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.shop.builder.stack"));
			return 0;
		}
		DynamicOps<Tag> ops = player.level().registryAccess().createSerializationContext(NbtOps.INSTANCE);
		Tag encoded = ItemStack.CODEC.encodeStart(ops, sample.copy()).result().orElse(null);
		if (!(encoded instanceof CompoundTag)) {
			context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.shop.builder.fail"));
			return 0;
		}
		String page = "vending " + encoded;
		if (page.length() < 8 || page.length() > WritableBookContent.PAGE_EDIT_LENGTH) {
			context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.shop.builder.long"));
			return 0;
		}
		ItemStack checked;
		try {
			CompoundTag parsed = TagParser.parseCompoundFully(page.substring(8));
			checked = ItemStack.CODEC.parse(ops, parsed).result().orElse(ItemStack.EMPTY);
		} catch (CommandSyntaxException | RuntimeException ex) {
			checked = ItemStack.EMPTY;
		}
		if (checked.isEmpty() || checked.getCount() != sample.getCount() || !ItemStack.isSameItemSameComponents(checked, sample)) {
			context.getSource().sendFailure(ServerText.to(context.getSource(), "message.command.shop.builder.fail"));
			return 0;
		}
		book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(Filterable.passThrough(page))));
		player.setItemInHand(bookHand, book);
		Component shown = sample.getDisplayName();
		context.getSource().sendSuccess(() -> ServerText.to(context.getSource(), "message.command.shop.builder.success", shown), true);
		return 1;
	}
}
