package dicemc.money.event;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.util.TriState;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
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
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.PlayerInventoryWrapper;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
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
	private static int shopPulse;
	private static final List<WatchedShop> WATCHED = new ArrayList<>();

	/** An activated shop sign. The tick refresh only walks this list. */
	private static final class WatchedShop {
		final ResourceKey<Level> dimension;
		BlockPos container;
		final BlockPos sign;

		WatchedShop(ResourceKey<Level> dimension, BlockPos container, BlockPos sign) {
			this.dimension = dimension;
			this.container = container;
			this.sign = sign;
		}
	}

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
						return;
					}
				} else {
					BlockPos backBlock = event.getPos().relative(tile.getBlockState().getValue(WallSignBlock.FACING).getOpposite());
					BlockEntity back = event.getLevel().getBlockEntity(backBlock);
					if (back != null) clearShopMark(back);
				}
				unwatch(event.getLevel(), event.getPos());
			}
			return;
		}
		BlockEntity storage = event.getLevel().getBlockEntity(event.getPos());
		if (storage != null && isShopContainer(storage)) {
			Player player = event.getPlayer();
			if (player == null || !OpCheck.has(player, Config.ADMIN_LEVEL.get())) {
				event.setCanceled(true);
				event.setNotifyClient(true);
			} else {
				unwatch(event.getLevel(), event.getPos());
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
			BlockPos back = event.getPos().relative(event.getLevel().getBlockState(event.getPos()).getValue(WallSignBlock.FACING).getOpposite());
			getSaleInfo(event.getEntity(), containerKind(event.getLevel().getBlockEntity(back)), nbt.getDoubleOr(PRICE, 0));
		}
	}

	@SubscribeEvent
	public static void onSignLoad(ChunkWatchEvent.Watch event) {
		LevelChunk chunk = event.getChunk();
		Level chunkLevel = chunk.getLevel();
		for (var entry : chunk.getBlockEntities().entrySet()) {
			if (!(entry.getValue() instanceof SignBlockEntity sign)) continue;
			if (!(sign.getBlockState().getBlock() instanceof WallSignBlock)) continue;
			if (!sign.getPersistentData().contains(ACTIVATED)) continue;
			BlockPos signPos = entry.getKey();
			BlockPos back = signPos.relative(sign.getBlockState().getValue(WallSignBlock.FACING).getOpposite());
			watchShop(chunkLevel, back, signPos);
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
		// Both hands fire this event. The off hand must not buy, charge, or mint a second time.
		if (event.getHand() != InteractionHand.MAIN_HAND) {
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
		Container container = liveContainer(world, storage.getBlockPos(), storage);
		List<ItemStack> slots = readShopSlots(world, storage, container, player);
		if (slots == null || slots.isEmpty()) {
			player.sendSystemMessage(ServerText.to(player, "message.activate.failure.stock"));
			return false;
		}
		ListTag lnbt = new ListTag();
		List<ItemStack> pieces = new ArrayList<>();
		for (ItemStack slot : slots) addOfferPieces(pieces, slot);
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
		watchShop(world, storage.getBlockPos(), pos);
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

	private static void getSaleInfo(Player player, String kind, double value) {
		player.sendSystemMessage(ServerText.to(player, "message.shop.info",
				ServerText.containerWord(player, kind),
				Config.getFormattedCurrency(value)));
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
		Level level = player.level();
		Container container = liveContainer(level, tile.getBlockPos(), tile);
		List<ItemStack> present = readShopSlots(level, tile, container, player);
		List<ItemStack> transItems = refreshOffer(sign, present, level);
		if (transItems == null || transItems.isEmpty()) {
			if (transItems != null) rememberOffer(sign, level, transItems);
			player.sendSystemMessage(ServerText.to(player, "message.shop.buy.failure.stock"));
			return;
		}
		String kind = containerKind(tile);
		UUID shopOwner = readUuid(nbt, OWNER);
		if (player instanceof ServerPlayer serverPlayer) {
			String token = shopToken(action);
			if (token != null && !KubeHooks.allowShop(serverPlayer, token, value, transItems)) {
				player.sendSystemMessage(ServerText.to(player, "message.shop.cancelled"));
				return;
			}
		}
		boolean buying = action.equalsIgnoreCase("buy") || action.equalsIgnoreCase("server-buy");
		if (buying && !stockCovers(present, transItems)) {
			player.sendSystemMessage(ServerText.to(player, "message.shop.buy.failure.stock"));
			return;
		}
		ResourceHandler<ItemResource> inv = container != null ? VanillaContainerWrapper.of(container) : null;
		boolean sold;
		if (action.equalsIgnoreCase("buy")) {
			sold = buyFromShop(player, inv, transItems, shopOwner, value, kind);
		} else if (action.equalsIgnoreCase("sell")) {
			sold = sellToShop(player, inv, transItems, shopOwner, value);
		} else if (action.equalsIgnoreCase("server-buy")) {
			sold = serverBuy(player, transItems, value, kind);
		} else {
			sold = serverSell(player, transItems, value);
		}
		if (sold) rememberOffer(sign, level, transItems);
	}

	/** Largest count one saved offer line can store. Matches the item codec. */
	private static final int OFFER_LINE_MAX = 99;

	/**
	 * Rereads the container the player opens, before any sale.
	 * Returns null when that container cannot be read. An empty list pays nothing.
	 * Every live slot is its own stack, including a later stack of an item the sign already stored.
	 * The count stored on the sign is not reused. A missing type is left out. Slots are not added together.
	 */
	private static List<ItemStack> refreshOffer(SignBlockEntity sign, List<ItemStack> present, Level level) {
		if (present == null || sign == null || level == null) return null;
		List<ItemStack> next = new ArrayList<>();
		for (ItemStack slot : present) {
			if (slot == null || slot.isEmpty() || slot.getCount() <= 0) continue;
			addOfferPieces(next, slot);
		}
		return next;
	}

	/** True when a saved line already names this exact item id and component patch. */
	private static boolean listedType(List<ItemStack> saved, ItemStack slot) {
		for (ItemStack line : saved) {
			if (sameType(line, slot)) return true;
		}
		return false;
	}

	/**
	 * The chest or barrel inventory the menu writes. A double chest is both halves.
	 * This is not the first item capability, which can be a different handler.
	 */
	private static Container liveContainer(Level level, BlockPos pos, BlockEntity tile) {
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock chest) {
			Container combined = ChestBlock.getContainer(chest, state, level, pos, true);
			if (combined != null) return combined;
		}
		BlockEntity other = connectedChest(tile);
		if (tile instanceof Container self && other instanceof Container second && other != tile) {
			return new net.minecraft.world.CompoundContainer(self, second);
		}
		if (tile instanceof Container self) return self;
		return null;
	}

	/**
	 * The block entity's slots, plus a type that is only visible in an open menu or on a side.
	 * A type already in the block entity is not added again. Null when nothing can be read.
	 */
	private static List<ItemStack> readShopSlots(Level level, BlockEntity tile, Container container, Player actor) {
		flushOpenMenus(level, tile, container, actor);
		List<ItemStack> base = new ArrayList<>();
		if (container != null) base.addAll(readDirect(container, level));
		else if (tile instanceof Container self) base.addAll(readDirect(self, level));
		List<ItemStack> origin = new ArrayList<>(base);
		List<ItemStack> fromEntity = entityExtras(level, tile, container, origin);
		List<ItemStack> fromMenu = menuExtras(level, tile, container, actor);
		List<ItemStack> absentMenu = new ArrayList<>();
		for (ItemStack stack : fromMenu) {
			if (!listedType(origin, stack)) absentMenu.add(stack);
		}
		invalidateShopCapabilities(tile);
		List<ItemStack> fromSides = capabilityExtras(level, tile, origin);
		base.addAll(mergeMaxByType(mergeMaxByType(fromEntity, absentMenu), fromSides));
		boolean anyView = container != null || tile instanceof Container || !fromEntity.isEmpty() || !fromMenu.isEmpty() || !fromSides.isEmpty();
		if (!anyView) return null;
		return base;
	}

	/** Slots on the block entity, and the other chest half, that the combined container list does not have. */
	private static List<ItemStack> entityExtras(Level level, BlockEntity tile, Container combined, List<ItemStack> origin) {
		List<ItemStack> found = new ArrayList<>();
		collectEntitySlots(found, level, tile, combined, origin);
		BlockEntity other = connectedChest(tile);
		if (other != null) collectEntitySlots(found, level, other, combined, origin);
		return found;
	}

	private static void collectEntitySlots(List<ItemStack> found, Level level, BlockEntity block, Container combined, List<ItemStack> origin) {
		if (!(block instanceof Container self) || self == combined) return;
		for (ItemStack stack : readDirect(self, level)) {
			if (!listedType(origin, stack)) found.add(stack);
		}
	}

	/** Slot counts come from the container itself, not from an item-resource snapshot. */
	private static List<ItemStack> readDirect(Container container, Level level) {
		List<ItemStack> slots = new ArrayList<>();
		for (int i = 0; i < container.getContainerSize(); i++) {
			acceptSlot(slots, container.getItem(i), level);
		}
		return slots;
	}

	private static void acceptSlot(List<ItemStack> slots, ItemStack stack, Level level) {
		if (stack == null || stack.isEmpty() || stack.getCount() <= 0) return;
		ItemStack offer = offerStackFromSlot(stack, level);
		if (!offer.isEmpty() && offer.getCount() > 0) slots.add(offer);
	}

	/** Keeps one slot as its own stack. A count above the codec limit is split only within that stack. */
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

	private static boolean buyFromShop(Player player, ResourceHandler<ItemResource> inv, List<ItemStack> transItems, UUID shopOwner, double value, String kind) {
		MoneyWSD wsd = MoneyWSD.get();
		if (shopOwner == null || inv == null) {
			player.sendSystemMessage(ServerText.to(player, "message.shop.buy.failure.stock"));
			return false;
		}
		if (value > wsd.getBalance(AcctTypes.PLAYER.key, player.getUUID())) {
			player.sendSystemMessage(ServerText.to(player, "message.shop.buy.failure.funds"));
			return false;
		}
		List<ItemStack> given = new ArrayList<>();
		try (Transaction tx = Transaction.openRoot()) {
			if (!extractAll(inv, transItems, given, tx)) {
				player.sendSystemMessage(ServerText.to(player, "message.shop.buy.failure.stock"));
				return false;
			}
			if (totalCount(given) != totalCount(transItems)) {
				player.sendSystemMessage(ServerText.to(player, "message.shop.buy.failure.stock"));
				return false;
			}
			if (!wsd.transferFunds(AcctTypes.PLAYER.key, player.getUUID(), AcctTypes.PLAYER.key, shopOwner, value)) {
				player.sendSystemMessage(ServerText.to(player, "message.shop.buy.failure.funds"));
				return false;
			}
			tx.commit();
		}
		giveToPlayer(player, given);
		postHistory(player.getUUID(), AcctTypes.PLAYER.key, player.getName().getString(), shopOwner, AcctTypes.PLAYER.key,
				Profiles.name(player.level().getServer(), shopOwner), value, describe(player.level(), transItems));
		String price = Config.getFormattedCurrency(value);
		player.sendSystemMessage(ServerText.to(player, "message.shop.buy.success",
				ServerText.containerWord(player, kind), ServerText.greenMoney(price)));
		if (player.level().getServer() != null) {
			ServerText.broadcast(player.level().getServer(), "message.shop.buy.broadcast", lang -> new Object[] {
					player.getName().getString(), ServerText.containerWord(lang, kind), ServerText.greenMoney(price)
			});
		}
		return true;
	}

	private static boolean sellToShop(Player player, ResourceHandler<ItemResource> inv, List<ItemStack> transItems, UUID shopOwner, double value) {
		MoneyWSD wsd = MoneyWSD.get();
		if (shopOwner == null || inv == null) {
			player.sendSystemMessage(ServerText.to(player, "message.shop.sell.failure.space"));
			return false;
		}
		if (value > wsd.getBalance(AcctTypes.PLAYER.key, shopOwner)) {
			player.sendSystemMessage(ServerText.to(player, "message.shop.sell.failure.funds"));
			return false;
		}
		ResourceHandler<ItemResource> playerInv = PlayerInventoryWrapper.of(player);
		try (Transaction tx = Transaction.openRoot()) {
			List<ItemStack> taken = new ArrayList<>();
			if (!extractAll(playerInv, transItems, taken, tx) || totalCount(taken) != totalCount(transItems)) {
				player.sendSystemMessage(ServerText.to(player, "message.shop.sell.failure.stock"));
				return false;
			}
			if (!insertAll(inv, taken, tx)) {
				player.sendSystemMessage(ServerText.to(player, "message.shop.sell.failure.space"));
				return false;
			}
			if (!wsd.transferFunds(AcctTypes.PLAYER.key, shopOwner, AcctTypes.PLAYER.key, player.getUUID(), value)) {
				player.sendSystemMessage(ServerText.to(player, "message.shop.sell.failure.funds"));
				return false;
			}
			tx.commit();
		}
		postHistory(shopOwner, AcctTypes.PLAYER.key, Profiles.name(player.level().getServer(), shopOwner),
				player.getUUID(), AcctTypes.PLAYER.key, player.getName().getString(), value, describe(player.level(), transItems));
		player.sendSystemMessage(ServerText.to(player, "message.shop.sell.success",
				Config.getFormattedCurrency(value)));
		return true;
	}

	private static boolean serverBuy(Player player, List<ItemStack> transItems, double value, String kind) {
		if (!MoneyWSD.get().tryTake(AcctTypes.PLAYER.key, player.getUUID(), value)) {
			player.sendSystemMessage(ServerText.to(player, "message.shop.buy.failure.funds"));
			return false;
		}
		List<ItemStack> copies = new ArrayList<>();
		for (ItemStack stack : transItems) {
			if (stack == null || stack.isEmpty() || stack.getCount() <= 0 || stack.getCount() > OFFER_LINE_MAX) {
				MoneyWSD.get().changeBalance(AcctTypes.PLAYER.key, player.getUUID(), value);
				player.sendSystemMessage(ServerText.to(player, "message.shop.buy.failure.stock"));
				return false;
			}
			copies.add(stack.copy());
		}
		giveToPlayer(player, copies);
		postHistory(DatabaseManager.NIL, AcctTypes.SERVER.key, "Server", player.getUUID(), AcctTypes.PLAYER.key,
				player.getName().getString(), -value, describe(player.level(), transItems));
		player.sendSystemMessage(ServerText.to(player, "message.shop.buy.success",
				ServerText.containerWord(player, kind), ServerText.greenMoney(Config.getFormattedCurrency(value))));
		return true;
	}

	private static boolean serverSell(Player player, List<ItemStack> transItems, double value) {
		ResourceHandler<ItemResource> playerInv = PlayerInventoryWrapper.of(player);
		try (Transaction tx = Transaction.openRoot()) {
			if (!extractAll(playerInv, transItems, new ArrayList<>(), tx)) {
				player.sendSystemMessage(ServerText.to(player, "message.shop.sell.failure.stock"));
				return false;
			}
			if (!MoneyWSD.get().changeBalance(AcctTypes.PLAYER.key, player.getUUID(), value)) {
				player.sendSystemMessage(ServerText.to(player, "message.shop.sell.failure.funds"));
				return false;
			}
			tx.commit();
		}
		postHistory(DatabaseManager.NIL, AcctTypes.SERVER.key, "Server", player.getUUID(), AcctTypes.PLAYER.key,
				player.getName().getString(), value, describe(player.level(), transItems));
		player.sendSystemMessage(ServerText.to(player, "message.shop.sell.success",
				Config.getFormattedCurrency(value)));
		return true;
	}

	/**
	 * Pulls every requested stack from matching slots. The slot's own resource is what leaves,
	 * so a saved copy with a different component map cannot skip the real stack or mint another.
	 * Returns false without committing when any amount is short.
	 */
	private static boolean extractAll(ResourceHandler<ItemResource> handler, List<ItemStack> wanted, List<ItemStack> out, Transaction tx) {
		if (handler == null || wanted == null || wanted.isEmpty()) return false;
		int expected = totalCount(wanted);
		if (expected <= 0) return false;
		for (ItemStack want : wanted) {
			if (want.isEmpty() || want.getCount() <= 0 || want.getCount() > OFFER_LINE_MAX) return false;
			int need = want.getCount();
			for (int slot = 0; slot < handler.size() && need > 0; slot++) {
				int have = handler.getAmountAsInt(slot);
				if (have <= 0 || handler.getResource(slot).isEmpty()) continue;
				ItemStack live = handler.getResource(slot).toStack(Math.min(have, OFFER_LINE_MAX));
				if (!sameType(live, want)) continue;
				ItemResource resource = handler.getResource(slot);
				int got = handler.extract(slot, resource, need, tx);
				if (got <= 0) continue;
				ItemStack piece = resource.toStack(got);
				if (piece.isEmpty() || piece.getCount() != got || piece.getCount() > OFFER_LINE_MAX) return false;
				out.add(piece);
				need -= got;
			}
			if (need > 0) return false;
		}
		return totalCount(out) == expected;
	}

	private static int totalCount(List<ItemStack> stacks) {
		int total = 0;
		if (stacks == null) return 0;
		for (ItemStack stack : stacks) {
			if (stack == null || stack.isEmpty() || stack.getCount() <= 0) continue;
			total += stack.getCount();
		}
		return total;
	}

	private static String describe(Level level, List<ItemStack> stacks) {
		ListTag encoded = encodeOffer(level, stacks);
		return encoded.toString();
	}

	private static void rememberOffer(SignBlockEntity sign, Level level, List<ItemStack> stacks) {
		ListTag encoded = encodeOffer(level, stacks);
		if (encoded.size() != stacks.size()) {
			MoneyMod.LOGGER.warn("Shop offer did not store every stack");
			return;
		}
		sign.getPersistentData().put(ITEMS, encoded);
		sign.setChanged();
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

	/**
	 * Gives each offer stack by itself. Stacks are not added together, and a stack is not merged
	 * into another stack of the same item already in the inventory.
	 */
	private static void giveToPlayer(Player player, List<ItemStack> stacks) {
		if (stacks == null) return;
		for (ItemStack stack : stacks) {
			if (stack == null || stack.isEmpty() || stack.getCount() <= 0) continue;
			int left = stack.getCount();
			int limit = Math.min(OFFER_LINE_MAX, Math.max(1, stack.getMaxStackSize()));
			while (left > 0) {
				int piece = Math.min(limit, left);
				ItemStack part = stack.copy();
				part.setCount(piece);
				if (part.isEmpty() || part.getCount() <= 0 || part.getCount() > OFFER_LINE_MAX) {
					MoneyMod.LOGGER.warn("Shop give skipped a stack");
					break;
				}
				placeSeparate(player, part);
				left -= piece;
			}
		}
	}

	private static void placeSeparate(Player player, ItemStack part) {
		int slot = player.getInventory().getFreeSlot();
		if (slot < 0) {
			player.drop(part, false);
			return;
		}
		player.getInventory().setItem(slot, part);
		if (player instanceof ServerPlayer serverPlayer) {
			serverPlayer.connection.send(player.getInventory().createInventoryUpdatePacket(slot));
		}
	}

	/** chest, barrel, or other. Used only to pick the container word in a finished sentence. */
	private static String containerKind(BlockEntity tile) {
		if (tile == null) return "other";
		Block block = tile.getBlockState().getBlock();
		if (block instanceof BarrelBlock) return "barrel";
		if (block instanceof ChestBlock) return "chest";
		return "other";
	}

	/** True when the live slots can cover every offer line. A short type pays nothing. */
	private static boolean stockCovers(List<ItemStack> present, List<ItemStack> offer) {
		if (present == null || offer == null || offer.isEmpty()) return false;
		for (int i = 0; i < offer.size(); i++) {
			ItemStack line = offer.get(i);
			if (line == null || line.isEmpty() || line.getCount() <= 0) return false;
			boolean earlier = false;
			for (int j = 0; j < i; j++) {
				if (sameType(offer.get(j), line)) {
					earlier = true;
					break;
				}
			}
			if (earlier) continue;
			int need = 0;
			for (ItemStack other : offer) {
				if (sameType(other, line)) need += other.getCount();
			}
			int have = 0;
			for (ItemStack slot : present) {
				if (sameType(slot, line)) have += slot.getCount();
			}
			if (have < need) return false;
		}
		return true;
	}

	private static void flushOpenMenus(Level level, BlockEntity tile, Container combined, Player actor) {
		if (actor instanceof ServerPlayer buyer && buyer.containerMenu instanceof ChestMenu own && menuViews(own.getContainer(), tile, combined)) {
			flushMenu(own);
		}
		for (ChestMenu menu : openMenus(level, tile, combined)) flushMenu(menu);
	}

	private static void flushMenu(ChestMenu menu) {
		Container viewed = menu.getContainer();
		if (viewed == null) return;
		int limit = viewed.getContainerSize();
		for (int i = 0; i < limit; i++) {
			Slot slot;
			try {
				slot = menu.getSlot(i);
			} catch (IndexOutOfBoundsException ex) {
				break;
			}
			slot.getItem();
			slot.setChanged();
		}
	}

	private static List<ChestMenu> openMenus(Level level, BlockEntity tile, Container combined) {
		List<ChestMenu> menus = new ArrayList<>();
		if (!(level instanceof ServerLevel)) return menus;
		Set<ServerPlayer> seen = new HashSet<>();
		List<Container> parts = new ArrayList<>();
		if (tile instanceof Container self) parts.add(self);
		BlockEntity other = connectedChest(tile);
		if (other instanceof Container second && !parts.contains(second)) parts.add(second);
		if (combined != null && !parts.contains(combined)) parts.add(combined);
		for (Container part : parts) {
			for (ContainerUser user : part.getEntitiesWithContainerOpen()) {
				if (user == null) continue;
				LivingEntity living = user.getLivingEntity();
				if (!(living instanceof ServerPlayer player) || !seen.add(player)) continue;
				if (!(player.containerMenu instanceof ChestMenu menu)) continue;
				if (!menuViews(menu.getContainer(), tile, combined)) continue;
				menus.add(menu);
			}
		}
		return menus;
	}

	private static boolean menuViews(Container viewed, BlockEntity tile, Container combined) {
		if (viewed == null) return false;
		if (viewed == combined) return true;
		if (tile instanceof Container self && (viewed == self || viewed instanceof CompoundContainer cc && cc.contains(self))) return true;
		BlockEntity other = connectedChest(tile);
		return other instanceof Container second && (viewed == second || viewed instanceof CompoundContainer cc && cc.contains(second));
	}

	/** Chest slots still holding a type the block entity list does not. The buyer's menu wins a slot. */
	private static List<ItemStack> menuExtras(Level level, BlockEntity tile, Container combined, Player actor) {
		Map<Integer, ItemStack> byIndex = new LinkedHashMap<>();
		List<ChestMenu> menus = openMenus(level, tile, combined);
		ChestMenu own = null;
		if (actor instanceof ServerPlayer buyer && buyer.containerMenu instanceof ChestMenu menu && menuViews(menu.getContainer(), tile, combined)) {
			own = menu;
		}
		for (ChestMenu menu : menus) {
			if (menu == own) continue;
			takeMenuSlots(byIndex, menu, level, false);
		}
		if (own != null) takeMenuSlots(byIndex, own, level, true);
		return new ArrayList<>(byIndex.values());
	}

	private static void takeMenuSlots(Map<Integer, ItemStack> byIndex, ChestMenu menu, Level level, boolean overwrite) {
		Container viewed = menu.getContainer();
		if (viewed == null) return;
		int limit = viewed.getContainerSize();
		for (int i = 0; i < limit; i++) {
			Slot slot;
			try {
				slot = menu.getSlot(i);
			} catch (IndexOutOfBoundsException ex) {
				break;
			}
			ItemStack seen = slot.getItem();
			if (seen == null || seen.isEmpty() || seen.getCount() <= 0) continue;
			ItemStack offer = offerStackFromSlot(seen, level);
			if (offer.isEmpty() || offer.getCount() <= 0) continue;
			if (overwrite) byIndex.put(i, offer);
			else byIndex.putIfAbsent(i, offer);
		}
	}

	private static void invalidateShopCapabilities(BlockEntity tile) {
		tile.invalidateCapabilities();
		BlockEntity other = connectedChest(tile);
		if (other != null) other.invalidateCapabilities();
	}

	/** Slots on every side whose type is absent from the block-entity list. One side per half, not a sum. */
	private static List<ItemStack> capabilityExtras(Level level, BlockEntity tile, List<ItemStack> origin) {
		List<ItemStack> extras = new ArrayList<>();
		extras.addAll(richestAbsent(level, tile.getBlockPos(), origin));
		BlockEntity other = connectedChest(tile);
		if (other != null) extras.addAll(richestAbsent(level, other.getBlockPos(), origin));
		return extras;
	}

	private static List<ItemStack> richestAbsent(Level level, BlockPos pos, List<ItemStack> origin) {
		List<List<ItemStack>> sidesPresent = new ArrayList<>();
		for (Direction side : sides()) {
			ResourceHandler<ItemResource> handler = level.getCapability(Capabilities.Item.BLOCK, pos, side);
			if (handler == null) continue;
			List<ItemStack> absent = new ArrayList<>();
			for (int i = 0; i < handler.size(); i++) {
				int amount = handler.getAmountAsInt(i);
				if (amount <= 0 || handler.getResource(i).isEmpty()) continue;
				ItemStack offer = offerStackFromSlot(handler.getResource(i).toStack(amount), level);
				if (offer.isEmpty() || offer.getCount() <= 0) continue;
				if (listedType(origin, offer)) continue;
				absent.add(offer);
			}
			if (!absent.isEmpty()) sidesPresent.add(absent);
		}
		List<ItemStack> chosen = new ArrayList<>();
		List<ItemStack> types = new ArrayList<>();
		for (List<ItemStack> side : sidesPresent) {
			for (ItemStack stack : side) {
				if (!listedType(types, stack)) types.add(stack);
			}
		}
		for (ItemStack type : types) {
			List<ItemStack> richest = List.of();
			for (List<ItemStack> side : sidesPresent) {
				List<ItemStack> ofType = new ArrayList<>();
				for (ItemStack stack : side) {
					if (sameType(stack, type)) ofType.add(stack);
				}
				if (ofType.size() > richest.size()) richest = ofType;
			}
			chosen.addAll(richest);
		}
		return chosen;
	}

	/** Keeps every stack of a type from the longer of the two lists. Does not add the lists together. */
	private static List<ItemStack> mergeMaxByType(List<ItemStack> left, List<ItemStack> right) {
		List<ItemStack> types = new ArrayList<>();
		if (left != null) {
			for (ItemStack stack : left) {
				if (!listedType(types, stack)) types.add(stack);
			}
		}
		if (right != null) {
			for (ItemStack stack : right) {
				if (!listedType(types, stack)) types.add(stack);
			}
		}
		List<ItemStack> out = new ArrayList<>();
		for (ItemStack type : types) {
			List<ItemStack> fromLeft = ofType(left, type);
			List<ItemStack> fromRight = ofType(right, type);
			out.addAll(fromLeft.size() >= fromRight.size() ? fromLeft : fromRight);
		}
		return out;
	}

	private static List<ItemStack> ofType(List<ItemStack> stacks, ItemStack type) {
		List<ItemStack> found = new ArrayList<>();
		if (stacks == null) return found;
		for (ItemStack stack : stacks) {
			if (sameType(stack, type)) found.add(stack);
		}
		return found;
	}

	private static void watchShop(Level level, BlockPos container, BlockPos sign) {
		if (level == null || level.isClientSide() || container == null || sign == null) return;
		ResourceKey<Level> dimension = level.dimension();
		BlockPos containerPos = container.immutable();
		BlockPos signPos = sign.immutable();
		for (WatchedShop watch : WATCHED) {
			if (watch.dimension.equals(dimension) && watch.sign.equals(signPos)) {
				watch.container = containerPos;
				return;
			}
		}
		WATCHED.add(new WatchedShop(dimension, containerPos, signPos));
	}

	private static void unwatch(LevelAccessor level, BlockPos pos) {
		if (!(level instanceof Level world) || world.isClientSide() || pos == null) return;
		ResourceKey<Level> dimension = world.dimension();
		BlockPos key = pos.immutable();
		WATCHED.removeIf(watch -> watch.dimension.equals(dimension) && (watch.sign.equals(key) || watch.container.equals(key)));
	}

	private static boolean chunkLoaded(ServerLevel level, BlockPos pos) {
		return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
	}

	@SubscribeEvent
	public static void onShopTick(ServerTickEvent.Post event) {
		if (WATCHED.isEmpty()) return;
		shopPulse++;
		if (shopPulse % 20 != 0) return;
		MinecraftServer server = event.getServer();
		Iterator<WatchedShop> it = WATCHED.iterator();
		while (it.hasNext()) {
			WatchedShop watch = it.next();
			ServerLevel level = server.getLevel(watch.dimension);
			if (level == null) {
				it.remove();
				continue;
			}
			if (!chunkLoaded(level, watch.sign)) continue;
			BlockEntity signBe = level.getBlockEntity(watch.sign);
			if (!(signBe instanceof SignBlockEntity sign)
					|| !(sign.getBlockState().getBlock() instanceof WallSignBlock)
					|| !sign.getPersistentData().contains(ACTIVATED)) {
				it.remove();
				continue;
			}
			BlockPos back = watch.sign.relative(sign.getBlockState().getValue(WallSignBlock.FACING).getOpposite());
			if (!chunkLoaded(level, back)) continue;
			BlockEntity storage = level.getBlockEntity(back);
			if (storage == null) {
				it.remove();
				continue;
			}
			watch.container = back.immutable();
			Container container = liveContainer(level, storage.getBlockPos(), storage);
			List<ItemStack> present = readShopSlots(level, storage, container, null);
			if (present == null) continue;
			List<ItemStack> next = refreshOffer(sign, present, level);
			if (next == null) continue;
			ListTag encoded = encodeOffer(level, next);
			if (encoded.size() != next.size()) continue;
			if (encoded.equals(sign.getPersistentData().getListOrEmpty(ITEMS))) continue;
			rememberOffer(sign, level, next);
		}
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

	/** Same item id and the same components. A different id is never the same type. */
	private static boolean sameType(ItemStack a, ItemStack b) {
		if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
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
