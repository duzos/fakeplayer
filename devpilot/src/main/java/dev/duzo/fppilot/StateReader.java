package dev.duzo.fppilot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;

/** Reads game state into JSON. Client thread only. */
public final class StateReader {
    private StateReader() {
    }

    public static JsonObject status() {
        Minecraft mc = Minecraft.getInstance();
        JsonObject out = new JsonObject();
        out.addProperty("enabled", PilotServer.isRunning());
        out.addProperty("screen", mc.screen == null ? null : mc.screen.getClass().getName());
        out.addProperty("windowFocused", mc.isWindowActive());
        ServerData server = mc.getCurrentServer();
        out.addProperty("server", server == null ? null : server.ip);
        LocalPlayer player = mc.player;
        if (player == null) {
            out.addProperty("inWorld", false);
            return out;
        }
        out.addProperty("inWorld", true);
        out.addProperty("name", player.getGameProfile().name());
        out.addProperty("dimension", player.level().dimension().identifier().toString());
        out.add("pos", vec(player.position()));
        out.addProperty("yaw", player.getYRot());
        out.addProperty("pitch", player.getXRot());
        out.addProperty("health", player.getHealth());
        out.addProperty("maxHealth", player.getMaxHealth());
        out.addProperty("food", player.getFoodData().getFoodLevel());
        out.addProperty("xpLevel", player.experienceLevel);
        out.addProperty("onGround", player.onGround());
        out.addProperty("vehicle", player.getVehicle() == null ? null : typeOf(player.getVehicle()));
        out.addProperty("gameMode", mc.gameMode == null ? null : mc.gameMode.getPlayerMode().getName());
        out.addProperty("selectedSlot", player.getInventory().getSelectedSlot());
        out.add("mainHand", item(player.getMainHandItem(), false));
        out.add("held", gson(Inputs.held()));
        out.add("hud", hud());
        return out;
    }

    /** Title, subtitle and action bar currently on screen, and every boss bar. */
    public static JsonObject hud() {
        Minecraft mc = Minecraft.getInstance();
        JsonObject out = new JsonObject();
        Gui gui = mc.gui;
        out.addProperty("title", text(gui.title));
        out.addProperty("subtitle", text(gui.subtitle));
        out.addProperty("titleTicksLeft", gui.titleTime);
        out.addProperty("actionBar", text(gui.overlayMessageString));
        out.addProperty("actionBarTicksLeft", gui.overlayMessageTime);
        JsonArray bars = new JsonArray();
        for (Map.Entry<UUID, LerpingBossEvent> entry : gui.getBossOverlay().events.entrySet()) {
            LerpingBossEvent bar = entry.getValue();
            JsonObject b = new JsonObject();
            b.addProperty("id", entry.getKey().toString());
            b.addProperty("name", bar.getName().getString());
            b.addProperty("progress", bar.getProgress());
            b.addProperty("color", bar.getColor().name());
            bars.add(b);
        }
        out.add("bossBars", bars);
        return out;
    }

    /** The open screen; for a container screen, every slot with its item and tooltip. */
    public static JsonObject screen(boolean tooltips) {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        JsonObject out = new JsonObject();
        if (screen == null) {
            out.add("screen", null);
            return out;
        }
        out.addProperty("screen", screen.getClass().getName());
        out.addProperty("title", screen.getTitle().getString());
        out.addProperty("width", screen.width);
        out.addProperty("height", screen.height);
        out.add("widgets", widgets(screen));
        if (screen instanceof AbstractContainerScreen<?> container) {
            var menu = container.getMenu();
            out.addProperty("containerId", menu.containerId);
            out.addProperty("menuType", menu.getClass().getName());
            out.add("carried", item(menu.getCarried(), tooltips));
            JsonArray slots = new JsonArray();
            for (Slot slot : menu.slots) {
                JsonObject s = new JsonObject();
                s.addProperty("slot", slot.index);
                s.addProperty("playerInventory", slot.container == mc.player.getInventory());
                s.addProperty("containerSlot", slot.getContainerSlot());
                if (slot.hasItem()) s.add("item", item(slot.getItem(), tooltips));
                slots.add(s);
            }
            out.add("slots", slots);
        }
        return out;
    }

    /** The screen's widgets, indexed by their position in [Screen.children] (what POST /widget takes). */
    static JsonArray widgets(Screen screen) {
        JsonArray out = new JsonArray();
        var children = screen.children();
        for (int i = 0; i < children.size(); i++) {
            if (!(children.get(i) instanceof AbstractWidget widget)) continue;
            JsonObject w = new JsonObject();
            w.addProperty("index", i);
            w.addProperty("type", widget.getClass().getSimpleName());
            w.addProperty("text", widget.getMessage().getString());
            if (widget instanceof EditBox box) w.addProperty("value", box.getValue());
            w.addProperty("x", widget.getX());
            w.addProperty("y", widget.getY());
            w.addProperty("width", widget.getWidth());
            w.addProperty("height", widget.getHeight());
            w.addProperty("active", widget.isActive());
            w.addProperty("visible", widget.visible);
            out.add(w);
        }
        return out;
    }

    public static JsonObject inventory(boolean tooltips) {
        LocalPlayer player = Minecraft.getInstance().player;
        JsonObject out = new JsonObject();
        if (player == null) return out;
        var inventory = player.getInventory();
        out.addProperty("selectedSlot", inventory.getSelectedSlot());
        JsonArray items = new JsonArray();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            JsonObject slot = item(stack, tooltips);
            slot.addProperty("slot", i);
            items.add(slot);
        }
        out.add("items", items);
        return out;
    }

    public static JsonObject entities(double radius, int limit) {
        Minecraft mc = Minecraft.getInstance();
        JsonObject out = new JsonObject();
        JsonArray list = new JsonArray();
        if (mc.level == null || mc.player == null) {
            out.add("entities", list);
            return out;
        }
        Vec3 eye = mc.player.position();
        java.util.List<Entity> near = new java.util.ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player) continue;
            if (entity.position().distanceTo(eye) <= radius) near.add(entity);
        }
        near.sort(java.util.Comparator.comparingDouble(e -> e.position().distanceTo(eye)));
        for (Entity entity : near.subList(0, Math.min(limit, near.size()))) {
            JsonObject e = new JsonObject();
            e.addProperty("id", entity.getId());
            e.addProperty("uuid", entity.getUUID().toString());
            e.addProperty("type", typeOf(entity));
            e.addProperty("name", entity.getName().getString());
            if (entity.hasCustomName()) e.addProperty("customName", entity.getCustomName().getString());
            e.add("pos", vec(entity.position()));
            e.addProperty("distance", round(entity.position().distanceTo(eye)));
            e.addProperty("invisible", entity.isInvisible());
            if (entity instanceof LivingEntity living) {
                e.addProperty("health", living.getHealth());
                e.addProperty("maxHealth", living.getMaxHealth());
            }
            if (entity.getVehicle() != null) e.addProperty("vehicle", entity.getVehicle().getId());
            list.add(e);
        }
        out.add("entities", list);
        return out;
    }

    /** What the crosshair is on. */
    public static JsonObject target() {
        Minecraft mc = Minecraft.getInstance();
        JsonObject out = new JsonObject();
        HitResult hit = mc.hitResult;
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            out.addProperty("type", "miss");
            return out;
        }
        out.add("location", vec(hit.getLocation()));
        if (hit instanceof BlockHitResult block && mc.level != null) {
            BlockPos pos = block.getBlockPos();
            out.addProperty("type", "block");
            out.addProperty("block", BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).toString());
            out.addProperty("state", mc.level.getBlockState(pos).toString());
            JsonArray p = new JsonArray();
            p.add(pos.getX());
            p.add(pos.getY());
            p.add(pos.getZ());
            out.add("pos", p);
            out.addProperty("face", block.getDirection().getName());
        } else if (hit instanceof EntityHitResult entity) {
            out.addProperty("type", "entity");
            out.addProperty("entityId", entity.getEntity().getId());
            out.addProperty("entityType", typeOf(entity.getEntity()));
            out.addProperty("name", entity.getEntity().getName().getString());
        }
        return out;
    }

    static JsonObject item(ItemStack stack, boolean tooltip) {
        JsonObject out = new JsonObject();
        if (stack == null || stack.isEmpty()) return out;
        out.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        out.addProperty("count", stack.getCount());
        out.addProperty("name", stack.getHoverName().getString());
        if (tooltip) {
            Minecraft mc = Minecraft.getInstance();
            JsonArray lines = new JsonArray();
            for (Component line : stack.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.NORMAL)) {
                lines.add(line.getString());
            }
            out.add("tooltip", lines);
        }
        return out;
    }

    private static String typeOf(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private static JsonArray vec(Vec3 v) {
        JsonArray a = new JsonArray();
        a.add(round(v.x));
        a.add(round(v.y));
        a.add(round(v.z));
        return a;
    }

    private static double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    private static String text(Object component) {
        return component instanceof Component c ? c.getString() : null;
    }

    private static JsonObject gson(Map<String, Integer> map) {
        JsonObject out = new JsonObject();
        map.forEach(out::addProperty);
        return out;
    }
}
