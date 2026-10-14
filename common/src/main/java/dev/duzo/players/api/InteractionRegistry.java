package dev.duzo.players.api;

import dev.duzo.players.Constants;
import dev.duzo.players.core.FPItems;
import dev.duzo.players.core.RidingInteractions;
import dev.duzo.players.core.PossessionLinkItem;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.menu.FakePlayerMenuProvider;
import dev.duzo.players.platform.Services;
import dev.duzo.players.entities.speech.FakeSpeech;
import dev.duzo.players.entities.speech.Verbosity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public class InteractionRegistry {
	public static final InteractionRegistry INSTANCE = new InteractionRegistry();
	private final HashMap<Item, Interaction> lookup = new HashMap<>();

	private InteractionRegistry() {
		this.defaults();
	}

	private static Set<Item> fromKey(TagKey<Item> key) {
		Set<Item> result = new HashSet<>();

		for (Item item : BuiltInRegistries.ITEM) {
			if (item.getDefaultInstance().is(key)) {
				result.add(item);
			}
		}

		return result;
	}

	// ItemTags no longer carries generic STAIRS/SLABS tags (only WOODEN_ variants); the block-level
	// tags still cover every material, so resolve through the block -> item mapping instead.
	private static Set<Item> fromBlockKey(TagKey<Block> key) {
		Set<Item> result = new HashSet<>();

		for (Block block : BuiltInRegistries.BLOCK) {
			if (block.builtInRegistryHolder().is(key)) {
				Item item = Item.byBlock(block);
				if (item != Items.AIR) result.add(item);
			}
		}

		return result;
	}

	private void defaults() {
		register(FPItems.CHUNK_ANCHOR.get(), (player, entity) -> {
			if (!player.getUUID().equals(entity.getAIState().ownerUUID())) {
				player.sendSystemMessage(Component.literal("Only this fake's owner can install a Chunk Anchor"), true);
				return InteractionResult.FAIL;
			}
			if (!dev.duzo.players.config.PlayersConfig.get().chunkAnchors) {
				player.sendSystemMessage(Component.literal("Chunk Anchors are disabled on this server"), true);
				return InteractionResult.FAIL;
			}
			if (entity.isChunkAnchored()) {
				player.sendSystemMessage(Component.literal("Already anchored"), true);
				return InteractionResult.FAIL;
			}
			entity.setChunkAnchored(true);
			if (!player.getAbilities().instabuild) player.getMainHandItem().shrink(1);
			player.sendSystemMessage(Component.literal("Chunk Anchor installed: this fake keeps working while you are away"), true);
			playSound(entity, SoundEvents.RESPAWN_ANCHOR_CHARGE);
			return InteractionResult.SUCCESS;
		});

		register(FPItems.POSSESSION_LINK.get(), (player, entity) -> {
			boolean owner = player.getUUID().equals(entity.getAIState().ownerUUID());
			if (!owner && !dev.duzo.players.core.AIMarkerItem.isOp(player)) {
				player.sendSystemMessage(Component.literal("Only this fake's owner can bind a Possession Link to it"), true);
				return InteractionResult.FAIL;
			}
			ItemStack link = player.getMainHandItem();
			if (!link.is(FPItems.POSSESSION_LINK.get())) return InteractionResult.FAIL;
			PossessionLinkItem.bind(link, entity);
			player.sendSystemMessage(Component.literal("Linked to " + entity.getSkinData().name() + ". Use it in the air to take control"), true);
			playSound(entity, SoundEvents.AMETHYST_BLOCK_CHIME);
			return InteractionResult.SUCCESS;
		});

		register(Items.OBSERVER, (player, entity) -> {
			entity.setNoAi(!entity.isNoAi());
			player.sendSystemMessage(Component.literal(entity.isNoAi() ? "AI Disabled" : "AI Enabled"), true);
			playSound(entity, SoundEvents.UI_BUTTON_CLICK.value());
			return InteractionResult.SUCCESS;
		});

		registerBlockTag(BlockTags.STAIRS, ((player, entity) -> {
			if (entity.isRiding()) {
				if (!RidingInteractions.mayCommand(player, entity)) {
					player.sendSystemMessage(Component.literal("You do not own this fake."), true);
					return InteractionResult.FAIL;
				}
				entity.setOrderedSeat(false);
				entity.stopRiding();
				Constants.traceEvent("seat dismount {} by stairs", entity.getUUID());
				player.sendSystemMessage(Component.literal("Dismounted"), true);
				playSound(entity, SoundEvents.UI_BUTTON_CLICK.value());
				return InteractionResult.SUCCESS;
			}
			entity.setPhysicalState(entity.isSitting() ? FakePlayerEntity.PhysicalState.STANDING : FakePlayerEntity.PhysicalState.SITTING);
			player.sendSystemMessage(Component.literal(entity.isSitting() ? "Sitting" : "Standing"), true);
			playSound(entity, SoundEvents.UI_BUTTON_CLICK.value());
			return InteractionResult.SUCCESS;
		}));

		register(ItemTags.BEDS, ((player, entity) -> {
			if (entity.isRiding()) {
				player.sendSystemMessage(Component.literal("Dismount it first"), true);
				return InteractionResult.FAIL;
			}
			entity.setPhysicalState(entity.getPhysicalState() == FakePlayerEntity.PhysicalState.LAYING ? FakePlayerEntity.PhysicalState.STANDING : FakePlayerEntity.PhysicalState.LAYING);
			player.sendSystemMessage(Component.literal(entity.getPhysicalState() == FakePlayerEntity.PhysicalState.LAYING ? "Laying" : "Standing"), true);
			playSound(entity, SoundEvents.UI_BUTTON_CLICK.value());
			return InteractionResult.SUCCESS;
		}));

		registerBlockTag(BlockTags.SLABS, ((player, entity) -> {
			entity.setSlim(!entity.isSlim());
			player.sendSystemMessage(Component.literal(entity.isSlim() ? "Slim" : "Normal"), true);
			playSound(entity, SoundEvents.UI_BUTTON_CLICK.value());
			return InteractionResult.SUCCESS;
		}));

		register(Items.ENDER_EYE, ((player, entity) -> {
			entity.setCustomNameVisible(!entity.isCustomNameVisible());
			player.sendSystemMessage(Component.literal(entity.isCustomNameVisible() ? "Name Visible" : "Name Hidden"), true);
			playSound(entity, SoundEvents.UI_BUTTON_CLICK.value());
			return InteractionResult.SUCCESS;
		}));

		register(Items.PAPER, ((player, entity) -> {
			ItemStack stack = player.getMainHandItem();
			if (!stack.is(Items.PAPER)) return InteractionResult.FAIL;

			Component customName = stack.get(DataComponents.CUSTOM_NAME);
			String text = customName != null ? customName.getString() : "Hello World!";
			FakeSpeech.say(entity, Verbosity.ALWAYS, "paper", text);
			playSound(entity, SoundEvents.BOOK_PAGE_TURN);
			return InteractionResult.SUCCESS;
		}));

		Interaction openInventory = (player, entity) -> {
			Services.COMMON_REGISTRY.openMenu(player, new FakePlayerMenuProvider(entity), buf -> buf.writeInt(entity.getId()));
			playSound(entity, SoundEvents.CHEST_OPEN);
			return InteractionResult.SUCCESS;
		};
		register(Items.CHEST, openInventory);
		register(Items.TRAPPED_CHEST, openInventory);
	}

	private static void playSound(FakePlayerEntity entity, SoundEvent sound) {
		entity.level().playSound(null, entity.getX(), entity.getY(), entity.getZ(), sound, SoundSource.NEUTRAL, 0.5f, 1.0f);
	}

	private Interaction fallback() {
		return (player, entity) -> InteractionResult.PASS;
	}

	public void register(Item item, Interaction interaction) {
		lookup.put(item, interaction);
	}

	public void register(TagKey<Item> tag, Interaction interaction) {
		for (Item item : fromKey(tag)) {
			register(item, interaction);
		}
	}

	public void registerBlockTag(TagKey<Block> tag, Interaction interaction) {
		for (Item item : fromBlockKey(tag)) {
			register(item, interaction);
		}
	}

	public Interaction get(Item item) {
		return lookup.getOrDefault(item, fallback());
	}

	public interface Interaction {
		InteractionResult run(ServerPlayer player, FakePlayerEntity entity);
	}
}
