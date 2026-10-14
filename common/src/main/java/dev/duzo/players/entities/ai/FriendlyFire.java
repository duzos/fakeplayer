package dev.duzo.players.entities.ai;

import dev.duzo.players.Constants;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;

import java.util.UUID;

/** Keeps a fake's projectiles and explosions off its own side. Loader damage events call this. */
public final class FriendlyFire {
	private FriendlyFire() {}

	public static boolean shouldBlock(DamageSource source, Entity victim) {
		return shouldBlock(source.getEntity(), victim);
	}

	/** True when the attacker is a fake and the victim is itself, its owner, a fake of the same owner, or a pet of the owner. */
	public static boolean shouldBlock(Entity attacker, Entity victim) {
		if (!(attacker instanceof FakePlayerEntity fake) || victim == null) return false;
		boolean block = victim == fake;
		UUID owner = fake.getAIState().ownerUUID();
		if (!block && owner != null) {
			block = victim.getUUID().equals(owner)
					|| victim instanceof FakePlayerEntity other && owner.equals(other.getAIState().ownerUUID())
					|| victim instanceof TamableAnimal pet && owner.equals(pet.getOwnerUUID());
		}
		if (block) Constants.traceEvent("damage on {} blocked, caused by its own side's fake", victim.getName().getString());
		return block;
	}
}
