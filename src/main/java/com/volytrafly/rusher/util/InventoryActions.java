/*
 * Flight, autopilot, highway, avoidance, and HUD behaviour ported from
 * VolytraFly-Addon by Volizray (https://github.com/Volizray/VolytraFly-Addon),
 * originally a Meteor Client addon. That project is dedicated to the public
 * domain under CC0 1.0. See the LICENSE and NOTICE files in this repository.
 */

package com.volytrafly.rusher.util;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.rusherhack.client.api.Globals;
import org.rusherhack.client.api.utils.InventoryUtils;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Inventory clicks that stand in for Meteor's {@code InvUtils} and {@code ChestSwap}.
 * RusherHack has no public ChestSwap module, so elytra and chest-armor are exchanged
 * directly. Slot ids follow the player inventory menu:
 * https://minecraft.wiki/w/Java_Edition_protocol/Inventory
 */
public final class InventoryActions implements Globals {
	private InventoryActions() {}

	public static boolean hasGlider(ItemStack stack) {
		return !stack.isEmpty() && stack.has(net.minecraft.core.component.DataComponents.GLIDER);
	}

	public static int durabilityLeft(ItemStack stack) {
		if (stack.isEmpty() || stack.getMaxDamage() <= 0) return Integer.MAX_VALUE;
		return stack.getMaxDamage() - stack.getDamageValue();
	}

	/** First inventory slot (not armor, not the hotbar) matching the predicate, or -1. */
	public static int findInStorage(Predicate<ItemStack> predicate) {
		AtomicInteger found = new AtomicInteger(-1);
		InventoryUtils.loopInventory((slot, stack) -> {
			if (predicate.test(stack)) {
				found.set(slot);
				return true;
			}
			return false;
		}, true, false, false, false);
		return found.get();
	}

	public static int findAnywhere(Predicate<ItemStack> predicate, boolean includeOffhand) {
		return InventoryUtils.findItem(predicate, false, includeOffhand);
	}

	/**
	 * Moves fireworks into the chosen hotbar index when they are sitting in storage.
	 * A hotbar slot that already holds fireworks is left alone, matching Meteor's
	 * "only move when the found stack is not already on the hotbar" check.
	 */
	public static void replenishHotbar(int hotbarIndex) {
		if (mc.player == null) return;
		int slot = findInStorage(stack -> stack.is(Items.FIREWORK_ROCKET));
		if (slot < 0) return;
		InventoryUtils.swapSlots(slot, hotbarIndex);
	}

	/** Equips a fresh elytra into the chest slot when one is available. */
	public static boolean equipElytra(int minDurability) {
		if (mc.player == null) return false;
		Predicate<ItemStack> fresh = stack -> hasGlider(stack) && durabilityLeft(stack) > minDurability;
		int hotbar = InventoryUtils.findItemHotbar(fresh);
		if (hotbar >= 0) {
			InventoryUtils.swapSlots(InventoryUtils.getInventorySlot(EquipmentSlot.CHEST), hotbar);
			return true;
		}
		int stored = findInStorage(fresh);
		if (stored < 0) {
			int anywhere = findAnywhere(fresh, true);
			if (anywhere < 0 || anywhere == InventoryUtils.getInventorySlot(EquipmentSlot.CHEST)) return false;
			stored = anywhere;
		}
		moveToChest(stored);
		return true;
	}

	/**
	 * Swaps the chest slot between a glider and a chest armor piece.
	 * Enabling wants an elytra; disabling wants the armor back.
	 */
	public static void swapChest() {
		Player player = mc.player;
		if (player == null) return;
		ItemStack worn = player.getItemBySlot(EquipmentSlot.CHEST);
		if (hasGlider(worn)) {
			equipChestArmor();
		} else {
			equipElytra(0);
		}
	}

	private static void equipChestArmor() {
		Predicate<ItemStack> armor = stack -> !stack.isEmpty() && !hasGlider(stack) && isChestArmor(stack);
		int hotbar = InventoryUtils.findItemHotbar(armor);
		if (hotbar >= 0) {
			InventoryUtils.swapSlots(InventoryUtils.getInventorySlot(EquipmentSlot.CHEST), hotbar);
			return;
		}
		int stored = findAnywhere(armor, false);
		if (stored >= 0) moveToChest(stored);
	}

	private static boolean isChestArmor(ItemStack stack) {
		if (mc.player == null) return false;
		EquipmentSlot slot = mc.player.getEquipmentSlotForItem(stack);
		return slot == EquipmentSlot.CHEST;
	}

	private static void moveToChest(int containerSlot) {
		if (mc.player == null || containerSlot < 0) return;
		int chest = InventoryUtils.getInventorySlot(EquipmentSlot.CHEST);
		if (containerSlot >= 36 && containerSlot <= 44) {
			InventoryUtils.swapSlots(chest, containerSlot - 36);
			return;
		}
		// Pick the stack up, place it on the chest slot, then put whatever was worn back.
		InventoryUtils.clickSlot(containerSlot, false);
		InventoryUtils.clickSlot(chest, false);
		InventoryUtils.clickSlot(containerSlot, false);
	}

	/**
	 * Uses one firework the way Meteor's hotbar swap does: offhand first, otherwise
	 * select the hotbar stack, use it, and restore the previous slot.
	 *
	 * @return false when no firework is in the hotbar or offhand
	 */
	public static boolean useFirework(Player player) {
		if (mc.gameMode == null) return false;
		if (player.getOffhandItem().is(Items.FIREWORK_ROCKET)) {
			mc.gameMode.useItem(player, InteractionHand.OFF_HAND);
			player.swing(InteractionHand.OFF_HAND);
			return true;
		}
		int hotbar = InventoryUtils.findItemHotbar(Items.FIREWORK_ROCKET);
		if (hotbar < 0) return false;
		int previous = InventoryUtils.getSelectedHotbarSlot();
		InventoryUtils.setHotbarSlot(hotbar);
		mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
		player.swing(InteractionHand.MAIN_HAND);
		InventoryUtils.setHotbarSlot(previous);
		return true;
	}

	public static boolean wearingGlider(LivingEntity entity) {
		return hasGlider(entity.getItemBySlot(EquipmentSlot.CHEST));
	}
}
