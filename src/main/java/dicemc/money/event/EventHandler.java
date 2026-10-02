package dicemc.money.event;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.DynamicOps;

import dicemc.money.MoneyMod;
import dicemc.money.MoneyMod.AcctTypes;
import dicemc.money.compat.KubeHooks;
import dicemc.money.compat.ftbchunks.ShopClaimAccess;
import dicemc.money.setup.Config;
import dicemc.money.setup.ServerText;
import dicemc.money.setup.OpCheck;
import dicemc.money.setup.Profiles;
import dicemc.money.storage.DatabaseManager;
import dicemc.money.storage.MoneyWSD;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.util.TriState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.PlayerInventoryWrapper;
import net.neoforged.neoforge.transfer.transaction.Transaction;

@EventBusSubscriber(modid = MoneyMod.MOD_ID)
public class EventHandler {
	public static final String IS_SHOP = "is-shop";
	public static final String ACTIVATED = "shop-activated";
	public static final String OWNER = "owner";
	public static final String ITEMS = "items";
	public static final String TYPE = "shop-type";
	public static final String PRICE = "price";
	private static boolean claimCheckFailed;

	@SubscribeEvent
	public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
		if (!event.getEntity().level().isClientSide() && event.getEntity() instanceof ServerPlayer player) {
			double balP = MoneyWSD.get().getBalance(AcctTypes.PLAYER.key, player.getUUID());
			player.sendSystemMessage(ServerText.to(player, "message.command.balance", Config.getFormattedCurrency(balP)));
		}
	}

	@SubscribeEvent
	public static void onPlayerDeath(LivingDeathEvent event) {
		if (!event.getEntity().level().isClientSide() && event.getEntity() instanceof Player player) {
			double balp = MoneyWSD.get().getBalance(AcctTypes.PLAYER.key, player.getUUID());
			double loss = balp * Config.LOSS_ON_DEATH.get();
			if (loss > 0 && Double.isFinite(loss) && MoneyWSD.get().changeBalance(AcctTypes.PLAYER.key, player.getUUID(), -loss)) {
				postHistory(DatabaseManager.NIL, AcctTypes.SERVER.key, "Server", player.getUUID(), AcctTypes.PLAYER.key,
						player.getName().getString(), -loss, "Loss on Death Event");
				player.sendSystemMessage(ServerText.to(player, "message.death", Config.getFormattedCurrency(loss)));
			}
		}
	}

	/** Cancels a placement that would border a shop sign, so a container cannot be swapped onto a live shop. */
	@SubscribeEvent
	public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
		if (event.getLevel().isClientSide() || event.isCanceled()) return;
		boolean cancel = Arrays.stream(Direction.values()).anyMatch(direction ->
				event.getLevel().getBlockEntity(event.getPos().relative(direction)) instanceof BlockEntity be
						&& isShopContainer(be));
		event.setCanceled(cancel);
	}

	@SubscribeEvent
	public static void onShopBreak(BreakBlockEvent event) {
		if (event.getLevel().isClientSide()) return;
		if (event.getState().getBlock() instanceof WallSignBlock) {
			if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof SignBlockEntity tile)) return;
			CompoundTag nbt = tile.getPersistentData();
			if (!nbt.isEmpty() && nbt.contains(ACTIVATED)) {
				Player player = event.getPlayer();
				if (player == null) {
					event.setCanceled(true);
					event.setNotifyClient(true);
					return;
				}
				boolean hasPerms = OpCheck.has(player, Config.ADMIN_LEVEL.get());
				UUID owner = readUuid(nbt, OWNER);
				if (owner == null || !owner.equals(player.getUUID())) {
					if (!hasPerms) {
						event.setCanceled(true);
						event.setNotifyClient(true);
					}
				} else {
					BlockPos backBlock = event.getPos().relative(tile.getBlockState().getValue(WallSignBlock.FACING).getOpposite());
					BlockEntity back = event.getLevel().getBlockEntity(backBlock);
					if (back != null) clearShopMark(back);
				}
			}
			return;
		}
		BlockEntity storage = event.getLevel().getBlockEntity(event.getPos());
		if (storage != null && isShopContainer(storage)) {
			Player player = event.getPlayer();
			if (player == null || !OpCheck.has(player, Config.ADMIN_LEVEL.get())) {
				event.setCanceled(true);
				event.setNotifyClient(true);
			}
		}
	}

	/** Pistons must not push or destroy a shop chest or an active shop sign. */
	@SubscribeEvent
	public static void onPiston(PistonEvent.Pre event) {
		if (event.getLevel().isClientSide()) return;
		PistonStructureResolver helper = event.getStructureHelper();
		if (helper == null || !helper.resolve()) return;
		for (BlockPos pos : helper.getToPush()) {
			if (protectsShop(event.getLevel(), pos)) {
				event.setCanceled(true);
				return;
			}
		}
		for (BlockPos pos : helper.getToDestroy()) {
			if (protectsShop(event.getLevel(), pos)) {
				event.setCanceled(true);
				return;
			}
		}
	}

	/** Explosions must not break a shop chest or an active shop sign and drop the stock. */
	@SubscribeEvent
	public static void onExplosion(ExplosionEvent.Detonate event) {
		event.getAffectedBlocks().removeIf(pos -> protectsShop(event.getLevel(), pos));
	}

	@SubscribeEvent
	public static void onStorageOpen(PlayerInteractEvent.RightClickBlock event) {
		BlockEntity invTile = event.getLevel().getBlockEntity(event.getPos());
		if (invTile == null || !isShopContainer(invTile)) return;
		Player player = event.getEntity();
		UUID owner = shopOwner(invTile);
		if (player == null || owner == null || !owner.equals(player.getUUID())) {
			if (player == null || !OpCheck.has(player, Config.ADMIN_LEVEL.get())) {
				event.setCanceled(true);
			}
		}
	}

	@SubscribeEvent
	public static void onSignLeftClick(PlayerInteractEvent.LeftClickBlock event) {
		if (event.getLevel().isClientSide()
				|| event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START
				|| !(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof WallSignBlock)) {
			return;
		}
		if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof SignBlockEntity tile)) return;
		CompoundTag nbt = tile.getPersistentData();
		if (nbt.contains(ACTIVATED)) {
			getSaleInfo(nbt, event.getEntity(), event.getLevel());
		}
	}

	@SubscribeEvent
	public static void onSignLoad(ChunkWatchEvent.Watch event) {
		LevelChunk chunk = event.getChunk();
		for (var entry : chunk.getBlockEntities().entrySet()) {
			if (!(entry.getValue() instanceof SignBlockEntity sign)) continue;
			if (!(sign.getBlockState().getBlock() instanceof WallSignBlock)) continue;
			if (!sign.getPersistentData().contains(ACTIVATED)) continue;
			if (!Arrays.stream(sign.getFrontText().getMessages(false)).allMatch(CommonComponents.EMPTY::equals)) continue;
			Component[] text = sign.getFrontText().getMessages(true);
			sign.setText(new SignText(text, text, DyeColor.BLACK, false), true);
			sign.setChanged();
			MoneyMod.LOGGER.debug("Applied No-Profanity-Filter Fix to sign at {}", entry.getKey());
		}
	}

	/**
	 * Runs after claim protection. A denied click stays denied: the sign is not edited,
	 * the held item is not used on the block, and no other block is opened.
	 * An already activated shop sign can still buy or sell when FTB Chunks is what denied the click.
	 */
	@SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
	public static void onSignRightClick(PlayerInteractEvent.RightClickBlock event) {
		BlockState state = event.getLevel().getBlockState(event.getPos());
		if (!(state.getBlock() instanceof WallSignBlock)) return;
		if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof SignBlockEntity tile)) return;
		boolean activated = tile.getPersistentData().contains(ACTIVATED);
		if (event.getLevel().isClientSide()) {
			if (activated) {
				event.setCanceled(true);
				event.setCancellationResult(InteractionResult.FAIL);
			}
			return;
		}
		BlockPos backBlock = event.getPos().relative(state.getValue(WallSignBlock.FACING).getOpposite());
		if (!(event.getLevel().getBlockEntity(backBlock) instanceof BlockEntity invTile)) return;
		if (!activated) {
			if (event.isCanceled()) return;
			if (activateShop(invTile, tile, event.getLevel(), event.getPos(), event.getEntity())) {
				event.setUseBlock(TriState.FALSE);
			}
			return;
		}
		if (event.isCanceled()) {
			if (claimBlocksShopUse(event.getEntity(), event.getHand(), event.getPos())) {
				processTransaction(invTile, tile, event.getEntity());
			}
			return;
		}
		processTransaction(invTile, tile, event.getEntity());
		event.setUseBlock(TriState.FALSE);
	}

	/** True only when FTB Chunks would deny this right-click. Any other cancel stays denied. */
	private static boolean claimBlocksShopUse(Player player, InteractionHand hand, BlockPos pos) {
		if (!ModList.get().isLoaded("ftbchunks")) return false;
		try {
			return ShopClaimAccess.blocksShopUse(player, hand, pos);
		} catch (Throwable ex) {
			if (!claimCheckFailed) {
				claimCheckFailed = true;
				MoneyMod.LOGGER.warn("FTB Chunks shop check failed; that click stays blocked", ex);
			}
			return false;
		}
	}

	private static boolean activateShop(BlockEntity storage, SignBlockEntity tile, Level world, BlockPos pos, Player player) {
		Component actionEntry = tile.getFrontText().getMessage(0, true);
		double price;
		try {
			double parsed = Double.parseDouble(tile.getFrontText().getMessage(3, true).getString());
			if (!Double.isFinite(parsed)) throw new NumberFormatException("non-finite");
			price = Math.abs(parsed);
		} catch (NumberFormatException e) {
			player.sendSystemMessage(ServerText.to(player, "message.activate.failure.money"));
			world.destroyBlock(pos, true, player);
			return false;
		}
		String rawAction = actionEntry.getString().toLowerCase();
		boolean playerShop = rawAction.equals("[buy]") || rawAction.equals("[sell]");
		boolean serverShop = rawAction.equals("[server-buy]") || rawAction.equals("[server-sell]");
		if (playerShop && !OpCheck.has(player, Config.SHOP_LEVEL.get())) {
			player.sendSystemMessage(ServerText.to(player, "message.activate.failure.permission"));
			return false;
		}
		if (serverShop && !OpCheck.has(player, Config.ADMIN_LEVEL.get())) {
			player.sendSystemMessage(ServerText.to(player, "message.activate.failure.admin"));
			return false;
		}
		if (!playerShop && !serverShop) {
			player.sendSystemMessage(ServerText.to(player, "message.activate.failure.type"));
			return false;
		}
		String shopString = rawAction.substring(1, rawAction.length() - 1);
		if (deniedToOther(storage, player.getUUID())) {
			player.sendSystemMessage(ServerText.to(player, "message.activate.failure.owned"));
			return false;
		}
		ResourceHandler<ItemResource> inv = findStocked(world, storage.getBlockPos());
		if (inv == null) {
			player.sendSystemMessage(ServerText.to(player, "message.activate.failure.stock"));
			return false;
		}
		ListTag lnbt = new ListTag();
		List<ItemStack> pieces = new ArrayList<>();
		for (ItemStack slot : readContainerSlots(inv, world)) addOfferPieces(pieces, slot);
		for (ItemStack piece : pieces) {
			Tag saved = saveOfferStack(world, piece);
			if (saved instanceof CompoundTag) lnbt.add(saved);
		}
		if (lnbt.isEmpty()) {
			player.sendSystemMessage(ServerText.to(player, "message.activate.failure.stock"));
			return false;
		}

		tile.getPersistentData().putDouble(PRICE, price);
		Component[] signText = new Component[] {
				Component.literal(actionEntry.getString()).withStyle(ChatFormatting.BLUE),
				tile.getFrontText().getMessage(1, true),
				tile.getFrontText().getMessage(2, true),
				Component.literal(Config.getFormattedCurrency(price)).withStyle(ChatFormatting.GOLD)
		};
		tile.setText(new SignText(signText, signText, DyeColor.BLACK, false), true);
		tile.getPersistentData().putString(TYPE, shopString);
		tile.getPersistentData().putBoolean(ACTIVATED, true);
		putUuid(tile.getPersistentData(), OWNER, player.getUUID());
		tile.getPersistentData().put(ITEMS, lnbt);
		tile.setChanged();
		markShopStorage(storage, player.getUUID());
		BlockState state = world.getBlockState(pos);
		world.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
		return true;
	}

	/**
	 * One slot of a shop container. A vending book becomes the item it describes.
	 * A book that cannot be read stays the book.
	 */
	private static ItemStack offerStackFromSlot(ItemStack stack, Level level) {
		if (!(stack.getItem() instanceof net.minecraft.world.item.WritableBookItem)) return stack.copy();
		WritableBookContent book = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
		if (book == null) return stack.copy();
		String page = book.getPages(false).findFirst().orElse("");
		if (page.length() >= 8 && page.substring(0, 7).equalsIgnoreCase("vending")) {
			try {
				CompoundTag parsed = TagParser.parseCompoundFully(page.substring(8));
				ItemStack custom = loadStack(level, parsed);
				if (!custom.isEmpty() && custom.getCount() > 0) return custom;
			} catch (CommandSyntaxException | RuntimeException e) {
				MoneyMod.LOGGER.warn("Shop book was not a vending item", e);
			}
		}
		return stack.copy();
	}

	private static void getSaleInfo(CompoundTag nbt, Player player, Level level) {
		String type = nbt.getStringOr(TYPE, "");
		boolean isBuy = type.equalsIgnoreCase("buy") || type.equalsIgnoreCase("server-buy");
		List<ItemStack> transItems = readItems(nbt, level);
		double value = nbt.getDoubleOr(PRICE, 0);
		MutableComponent itemComponent = ServerText.items(player, transItems);
		if (isBuy) {
			player.sendSystemMessage(ServerText.to(player, "message.shop.info", itemComponent, Config.getFormattedCurrency(value)));
		} else {
			player.sendSystemMessage(ServerText.to(player, "message.shop.info", Config.getFormattedCurrency(value), itemComponent));
		}
	}

	private static String shopToken(String action) {
		if (action.equalsIgnoreCase("buy")) return "[buy]";
		if (action.equalsIgnoreCase("sell")) return "[sell]";
		if (action.equalsIgnoreCase("server-buy")) return "[server-buy]";
		if (action.equalsIgnoreCase("server-sell")) return "[server-sell]";
		return null;
	}

	private static void processTransaction(BlockEntity tile, SignBlockEntity sign, Player player) {
		CompoundTag nbt = sign.getPersistentData();
		String action = nbt.getStringOr(TYPE, "");
		double value = nbt.getDoubleOr(PRICE, 0);
		if (!Double.isFinite(value) || value < 0) {
			player.sendSystemMessage(ServerText.to(player, "message.shop.buy.failure.stock"));
			return;
		}
		boolean playerShop = action.equalsIgnoreCase("buy") || action.equalsIgnoreCase("sell");
		boolean serverShop = action.equalsIgnoreCase("server-buy") || action.equalsIgnoreCase("server-sell");
		if (!playerShop && !serverShop) {
			player.sendSystemMessage(ServerText.to(player, "message.shop.unknown"));
			return;
		}
		ResourceHandler<ItemResource> inv = findHandler(player.level(), tile.getBlockPos());
		List<ItemStack> transItems = refreshOffer(sign, inv, player.level());
		if (transItems == null) {
			player.sendSystemMessage(ServerText.to(player, "message.shop.buy.failure.stock"));
			return;
		}
		ListTag encoded = encodeOffer(player.level(), transItems);
		if (transItems.isEmpty() || encoded.isEmpty()) {
			if (transItems.isEmpty()) {
				nbt.put(ITEMS, new ListTag());
				sign.setChanged();
			}
			player.sendSystemMessage(ServerText.to(player, "message.shop.buy.failure.stock"));
			return;
		}
		nbt.put(ITEMS, encoded);
		sign.setChanged();
		transItems = readItems(nbt, player.level());
		if (transItems.isEmpty()) {
			player.sendSystemMessage(ServerText.to(player, "message.shop.buy.failure.stock"));
			return;
		}
		UUID shopOwner = readUuid(nbt, OWNER);
		if (player instanceof ServerPlayer serverPlayer) {
			String token = shopToken(action);
			if (token != null && !KubeHooks.allowShop(serverPlayer, token, value, transItems)) {
				player.sendSystemMessage(ServerText.to(player, "message.shop.cancelled"));
				return;
			}
		}
		if (action.equalsIgnoreCase("buy")) {
			buyFromShop(player, inv, transItems, shopOwner, value, encoded);
		} else if (action.equalsIgnoreCase("sell")) {
			sellToShop(player, inv, transItems, shopOwner, value, encoded);
		} else if (action.equalsIgnoreCase("server-buy")) {
			serverBuy(player, transItems, value, encoded);
		} else {
			serverSell(player, transItems, value, encoded);
		}
	}

	/** Largest count one saved offer line can store. Matches the item codec. */
	private static final int OFFER_LINE_MAX = 99;

	/**
	 * Rereads the container once, before any sale. Returns null when the container cannot be read.
	 * An empty list means the offer is gone and nothing may be paid.
	 */
	private static List<ItemStack> refreshOffer(SignBlockEntity sign, ResourceHandler<ItemResource> inv, Level level) {
		if (inv == null) return null;
		List<ItemStack> saved = readItems(sign.getPersistentData(), level);
		List<ItemStack> present = readContainerSlots(inv, level);
		List<ItemStack> next = new ArrayList<>();
		for (ItemStack line : saved) {
			if (line.isEmpty() || line.getCount() <= 0) continue;
			if (countOf(present, line) <= 0) continue;
			addOfferPieces(next, line);
		}
		List<ItemStack> added = new ArrayList<>();
		for (ItemStack slot : present) {
			if (slot.isEmpty() || slot.getCount() <= 0) continue;
			if (countOf(saved, slot) > 0) continue;
			mergeOffer(added, slot);
		}
		for (ItemStack stack : added) addOfferPieces(next, stack);
		return next;
	}

	private static List<ItemStack> readContainerSlots(ResourceHandler<ItemResource> inv, Level level) {
		List<ItemStack> slots = new ArrayList<>();
		for (int i = 0; i < inv.size(); i++) {
			if (inv.getAmountAsInt(i) <= 0 || inv.getResource(i).isEmpty()) continue;
			ItemStack inSlot = inv.getResource(i).toStack(inv.getAmountAsInt(i));
			ItemStack offer = offerStackFromSlot(inSlot, level);
			if (!offer.isEmpty() && offer.getCount() > 0) slots.add(offer);
		}
		return slots;
	}

	private static int countOf(List<ItemStack> stacks, ItemStack needle) {
		int total = 0;
		for (ItemStack stack : stacks) {
			if (sameItem(stack, needle)) total += stack.getCount();
		}
		return total;
	}

	private static void mergeOffer(List<ItemStack> into, ItemStack stack) {
		for (ItemStack existing : into) {
			if (!sameItem(existing, stack)) continue;
			if (existing.getCount() > Integer.MAX_VALUE - stack.getCount()) return;
			existing.grow(stack.getCount());
			return;
		}
		into.add(stack.copy());
	}

	/** Keeps the per-sale count. A count above the codec limit is split without changing the total. */
	private static void addOfferPieces(List<ItemStack> dest, ItemStack stack) {
		int left = stack.getCount();
		while (left > 0) {
			int piece = Math.min(OFFER_LINE_MAX, left);
			ItemStack part = stack.copy();
			part.setCount(piece);
			dest.add(part);
			left -= piece;
		}
	}

	private static ListTag encodeOffer(Level level, List<ItemStack> stacks) {
		ListTag tag = new ListTag();
		for (ItemStack stack : stacks) {
			Tag saved = saveOfferStack(level, stack);
			if (saved instanceof CompoundTag) tag.add(saved);
		}
		return tag;
	}

	private static Tag saveOfferStack(Level level, ItemStack stack) {
		if (stack.isEmpty() || stack.getCount() <= 0 || stack.getCount() > OFFER_LINE_MAX) return null;
		try {
			Tag saved = saveStack(level, stack);
			return saved instanceof CompoundTag ? saved : null;
		} catch (RuntimeException ex) {
			MoneyMod.LOGGER.warn("Shop offer item was not saved", ex);
			return null;
		}
	}

	private static void buyFromShop(Player player, ResourceHandler<ItemResource> inv, List<ItemStack> transItems, UUID shopOwner, double value, ListTag itemsList) {
		MoneyWSD wsd = MoneyWSD.get();
		if (shopOwner == null || inv == null) {
			player.sendSystemMessage(ServerText.to(player, "message.shop.buy.failure.stock"));
			return;
		}
		if (value > wsd.getBalance(AcctTypes.PLAYER.key, player.getUUID())) {
			player.sendSystemMessage(ServerText.to(player, "message.shop.buy.failure.funds"));
			return;
		}
		List<ItemStack> given = new ArrayList<>();
		try (Transaction tx = Transaction.openRoot()) {
			if (!extractAll(inv, transItems, given, tx)) {
				player.sendSystemMessage(ServerText.to(player, "message.shop.buy.failure.stock"));
				return;
			}
			if (!wsd.transferFunds(AcctTypes.PLAYER.key, player.getUUID(), AcctTypes.PLAYER.key, shopOwner, value)) {
				player.sendSystemMessage(ServerText.to(player, "message.shop.buy.failure.funds"));
				return;
			}
			tx.commit();
		}
		giveToPlayer(player, given);
		postHistory(player.getUUID(), AcctTypes.PLAYER.key, player.getName().getString(), shopOwner, AcctTypes.PLAYER.key,
				Profiles.name(player.level().getServer(), shopOwner), value, itemsList.toString());
		String price = Config.getFormattedCurrency(value);
		player.sendOverlayMessage(ServerText.to(player, "message.shop.buy.success", ServerText.items(player, transItems), price));
		if (player.level().getServer() != null) {
			ServerText.broadcast(player.level().getServer(), "message.shop.buy.broadcast", lang -> new Object[] {
					player.getName(), ServerText.items(lang, transItems), price
			});
		}
	}

	private static void sellToShop(Player player, ResourceHandler<ItemResource> inv, List<ItemStack> transItems, UUID shopOwner, double value, ListTag itemsList) {
		MoneyWSD wsd = MoneyWSD.get();
		if (shopOwner == null || inv == null) {
			player.sendSystemMessage(ServerText.to(player, "message.shop.sell.failure.space"));
			return;
		}
		if (value > wsd.getBalance(AcctTypes.PLAYER.key, shopOwner)) {
			player.sendSystemMessage(ServerText.to(player, "message.shop.sell.failure.funds"));
			return;
		}
		ResourceHandler<ItemResource> playerInv = PlayerInventoryWrapper.of(player);
		try (Transaction tx = Transaction.openRoot()) {
			List<ItemStack> taken = new ArrayList<>();
			if (!extractAll(playerInv, transItems, taken, tx)) {
				player.sendSystemMessage(ServerText.to(player, "message.shop.sell.failure.stock"));
				return;
			}
			if (!insertAll(inv, taken, tx)) {
				player.sendSystemMessage(ServerText.to(player, "message.shop.sell.failure.space"));
				return;
			}
			if (!wsd.transferFunds(AcctTypes.PLAYER.key, shopOwner, AcctTypes.PLAYER.key, player.getUUID(), value)) {
				player.sendSystemMessage(ServerText.to(player, "message.shop.sell.failure.funds"));
				return;
			}
			tx.commit();
		}
		postHistory(shopOwner, AcctTypes.PLAYER.key, Profiles.name(player.level().getServer(), shopOwner),
				player.getUUID(), AcctTypes.PLAYER.key, player.getName().getString(), value, itemsList.toString());
		player.sendSystemMessage(ServerText.to(player, "message.shop.sell.success",
				Config.getFormattedCurrency(value), ServerText.items(player, transItems)));
	}

	private static void serverBuy(Player player, List<ItemStack> transItems, double value, ListTag itemsList) {
		MoneyWSD wsd = MoneyWSD.get();
		if (value > wsd.getBalance(AcctTypes.PLAYER.key, player.getUUID())) {
			player.sendSystemMessage(ServerText.to(player, "message.shop.buy.failure.funds"));
			return;
		}
		if (!wsd.changeBalance(AcctTypes.PLAYER.key, player.getUUID(), -value)) {
			player.sendSystemMessage(ServerText.to(player, "message.shop.buy.failure.funds"));
			return;
		}
		List<ItemStack> copies = new ArrayList<>();
		for (ItemStack stack : transItems) copies.add(stack.copy());
		giveToPlayer(player, copies);
		postHistory(DatabaseManager.NIL, AcctTypes.SERVER.key, "Server", player.getUUID(), AcctTypes.PLAYER.key,
				player.getName().getString(), -value, itemsList.toString());
		player.sendSystemMessage(ServerText.to(player, "message.shop.buy.success",
				ServerText.items(player, transItems), Config.getFormattedCurrency(value)));
	}

	private static void serverSell(Player player, List<ItemStack> transItems, double value, ListTag itemsList) {
		ResourceHandler<ItemResource> playerInv = PlayerInventoryWrapper.of(player);
		try (Transaction tx = Transaction.openRoot()) {
			if (!extractAll(playerInv, transItems, new ArrayList<>(), tx)) {
				player.sendSystemMessage(ServerText.to(player, "message.shop.sell.failure.stock"));
				return;
			}
			if (!MoneyWSD.get().changeBalance(AcctTypes.PLAYER.key, player.getUUID(), value)) {
				player.sendSystemMessage(ServerText.to(player, "message.shop.sell.failure.funds"));
				return;
			}
			tx.commit();
		}
		postHistory(DatabaseManager.NIL, AcctTypes.SERVER.key, "Server", player.getUUID(), AcctTypes.PLAYER.key,
				player.getName().getString(), value, itemsList.toString());
		player.sendSystemMessage(ServerText.to(player, "message.shop.sell.success",
				Config.getFormattedCurrency(value), ServerText.items(player, transItems)));
	}

	/** Pulls every requested stack. Returns false without committing if any amount is short. */
	private static boolean extractAll(ResourceHandler<ItemResource> handler, List<ItemStack> wanted, List<ItemStack> out, Transaction tx) {
		for (ItemStack want : wanted) {
			if (want.isEmpty() || want.getCount() <= 0) return false;
			ItemResource resource = ItemResource.of(want);
			int need = want.getCount();
			for (int slot = 0; slot < handler.size() && need > 0; slot++) {
				if (!handler.getResource(slot).equals(resource)) continue;
				int got = handler.extract(slot, resource, need, tx);
				if (got > 0) {
					out.add(resource.toStack(got));
					need -= got;
				}
			}
			if (need > 0) return false;
		}
		return true;
	}

	/** Inserts every stack. Returns false if any remainder cannot fit. */
	private static boolean insertAll(ResourceHandler<ItemResource> handler, List<ItemStack> stacks, Transaction tx) {
		for (ItemStack stack : stacks) {
			ItemResource resource = ItemResource.of(stack);
			int left = stack.getCount();
			for (int slot = 0; slot < handler.size() && left > 0; slot++) {
				int inserted = handler.insert(slot, resource, left, tx);
				left -= inserted;
			}
			if (left > 0) return false;
		}
		return true;
	}

	private static void giveToPlayer(Player player, List<ItemStack> stacks) {
		for (ItemStack stack : stacks) {
			if (!player.addItem(stack)) player.drop(stack, false);
		}
	}

	private static ResourceHandler<ItemResource> findStocked(Level world, BlockPos pos) {
		for (Direction side : sides()) {
			ResourceHandler<ItemResource> inv = world.getCapability(Capabilities.Item.BLOCK, pos, side);
			if (inv == null) continue;
			for (int i = 0; i < inv.size(); i++) {
				if (inv.getAmountAsInt(i) > 0 && !inv.getResource(i).isEmpty()) return inv;
			}
		}
		return null;
	}

	private static ResourceHandler<ItemResource> findHandler(Level world, BlockPos pos) {
		for (Direction side : sides()) {
			ResourceHandler<ItemResource> inv = world.getCapability(Capabilities.Item.BLOCK, pos, side);
			if (inv != null) return inv;
		}
		return null;
	}

	private static List<Direction> sides() {
		List<Direction> sides = new ArrayList<>();
		sides.add(null);
		sides.addAll(Arrays.asList(Direction.values()));
		return sides;
	}

	private static List<ItemStack> readItems(CompoundTag nbt, Level level) {
		List<ItemStack> items = new ArrayList<>();
		ListTag list = nbt.getListOrEmpty(ITEMS);
		for (int i = 0; i < list.size(); i++) {
			CompoundTag tag = list.getCompoundOrEmpty(i);
			if (tag.isEmpty()) continue;
			ItemStack stack = loadStack(level, tag);
			if (!stack.isEmpty()) items.add(stack);
		}
		return items;
	}

	private static boolean sameItem(ItemStack a, ItemStack b) {
		return ItemStack.isSameItemSameComponents(a, b);
	}

	private static Tag saveStack(Level level, ItemStack stack) {
		DynamicOps<Tag> ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
		return ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
	}

	private static ItemStack loadStack(Level level, Tag tag) {
		DynamicOps<Tag> ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
		return ItemStack.CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY);
	}

	private static void putUuid(CompoundTag tag, String key, UUID id) {
		tag.putString(key, id.toString());
	}

	private static UUID readUuid(CompoundTag tag, String key) {
		String raw = tag.getStringOr(key, "");
		if (!raw.isEmpty()) {
			try {
				return UUID.fromString(raw);
			} catch (IllegalArgumentException ignored) {
				return null;
			}
		}
		Optional<int[]> legacy = tag.getIntArray(key);
		if (legacy.isPresent() && legacy.get().length == 4) {
			return UUIDUtil.uuidFromIntArray(legacy.get());
		}
		return null;
	}

	/** A shop chest, including the other half of a double chest, or an activated shop sign. */
	public static boolean isShopContainer(BlockEntity block) {
		if (block == null) return false;
		if (block.getPersistentData().contains(IS_SHOP)) return true;
		BlockEntity other = connectedChest(block);
		return other != null && other.getPersistentData().contains(IS_SHOP);
	}

	private static boolean protectsShop(LevelAccessor level, BlockPos pos) {
		BlockEntity block = level.getBlockEntity(pos);
		if (block == null) return false;
		if (isShopContainer(block)) return true;
		return block instanceof SignBlockEntity sign
				&& sign.getBlockState().getBlock() instanceof WallSignBlock
				&& sign.getPersistentData().contains(ACTIVATED);
	}

	private static boolean deniedToOther(BlockEntity storage, UUID playerId) {
		if (!isShopContainer(storage)) return false;
		UUID existing = shopOwner(storage);
		return existing == null || playerId == null || !existing.equals(playerId);
	}

	private static UUID shopOwner(BlockEntity storage) {
		if (storage.getPersistentData().contains(IS_SHOP)) return readUuid(storage.getPersistentData(), OWNER);
		BlockEntity other = connectedChest(storage);
		if (other != null && other.getPersistentData().contains(IS_SHOP)) {
			return readUuid(other.getPersistentData(), OWNER);
		}
		return null;
	}

	private static void markShopStorage(BlockEntity storage, UUID owner) {
		markOneShop(storage, owner);
		BlockEntity other = connectedChest(storage);
		if (other != null) markOneShop(other, owner);
	}

	private static void markOneShop(BlockEntity storage, UUID owner) {
		storage.getPersistentData().putBoolean(IS_SHOP, true);
		putUuid(storage.getPersistentData(), OWNER, owner);
		storage.setChanged();
	}

	private static void clearShopMark(BlockEntity storage) {
		clearOneShop(storage);
		BlockEntity other = connectedChest(storage);
		if (other != null) clearOneShop(other);
	}

	private static void clearOneShop(BlockEntity storage) {
		if (!storage.getPersistentData().contains(IS_SHOP)) return;
		storage.getPersistentData().remove(IS_SHOP);
		storage.setChanged();
	}

	/** The other half of a vanilla double chest. Barrels and single chests have none. */
	private static BlockEntity connectedChest(BlockEntity block) {
		BlockState state = block.getBlockState();
		if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return null;
		if (block.getLevel() == null) return null;
		BlockEntity other = block.getLevel().getBlockEntity(ChestBlock.getConnectedBlockPos(block.getBlockPos(), state));
		return other == block ? null : other;
	}

	private static void postHistory(UUID fromId, net.minecraft.resources.Identifier fromType, String fromName,
			UUID toId, net.minecraft.resources.Identifier toType, String toName, double price, String item) {
		if (!Config.ENABLE_HISTORY.get() || MoneyMod.dbm == null) return;
		try {
			MoneyMod.dbm.postEntry(System.currentTimeMillis(), fromId, fromType, fromName, toId, toType, toName, price, item);
		} catch (RuntimeException ex) {
			MoneyMod.LOGGER.warn("History entry was not recorded", ex);
		}
	}
}
