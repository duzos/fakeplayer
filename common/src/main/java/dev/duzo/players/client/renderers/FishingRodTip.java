package dev.duzo.players.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.IdentityHashMap;
import java.util.Map;

/** Rod tips captured during entity rendering and consumed by the world line pass. */
public final class FishingRodTip {
	private record Context(FakePlayerEntity owner, Matrix4f inverseRoot, Vec3 position) {}
	private static final Map<LivingEntity, Map<ItemStack, Vec3>> TIPS = new IdentityHashMap<>();
	private static Context current;
	private static boolean worldFrame;

	private FishingRodTip() {}

	public static Runnable begin(FakePlayerEntity owner, PoseStack pose, float partial) {
		if (!worldFrame) return () -> {};
		Context previous = current;
		TIPS.remove(owner);
		current = new Context(owner, new Matrix4f(pose.last().pose()).invert(), owner.getPosition(partial));
		return () -> current = previous;
	}

	public static void capture(PoseStack pose, ItemStack rod, float x, float y, float z) {
		Context context = current;
		if (context == null || (rod != context.owner.getMainHandItem() && rod != context.owner.getOffhandItem())) return;
		// Undo the entity render root, retaining its model, arm, item and rod transforms.
		Vector3f point = pose.last().pose().transformPosition(new Vector3f(x, y, z));
		context.inverseRoot.transformPosition(point);
		if (!point.isFinite()) return;
		TIPS.computeIfAbsent(context.owner, ignored -> new IdentityHashMap<>())
				.put(rod, context.position.add(point.x, point.y, point.z));
	}

	public static Vec3 position(LivingEntity owner, ItemStack rod) {
		Map<ItemStack, Vec3> tips = TIPS.get(owner);
		if (tips == null) return null;
		ItemStack main = owner.getMainHandItem();
		if (ItemStack.isSameItem(main, rod) && tips.containsKey(main)) return tips.get(main);
		ItemStack off = owner.getOffhandItem();
		return ItemStack.isSameItem(off, rod) ? tips.get(off) : null;
	}

	public static void beginFrame() { TIPS.clear(); worldFrame = true; }
	public static void endFrame() { TIPS.clear(); worldFrame = false; }
}
