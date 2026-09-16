package dev.duzo.players.core;

import dev.duzo.players.Constants;
import dev.duzo.players.entities.ai.CourierJobExecutor;
import dev.duzo.players.entities.ai.CrafterJobExecutor;
import dev.duzo.players.entities.ai.FarmerJobExecutor;
import dev.duzo.players.entities.ai.FishermanJobExecutor;
import dev.duzo.players.entities.ai.GuardJobExecutor;
import dev.duzo.players.entities.ai.IdleJobExecutor;
import dev.duzo.players.entities.ai.JobRow;
import dev.duzo.players.entities.ai.JobType;
import dev.duzo.players.entities.ai.LegacyJobIds;
import dev.duzo.players.entities.ai.LumberjackJobExecutor;
import dev.duzo.players.entities.ai.MinerJobExecutor;
import dev.duzo.players.entities.ai.QuartermasterJobExecutor;
import dev.duzo.players.entities.ai.RunnerJobExecutor;
import dev.duzo.players.platform.Services;
import dev.duzo.players.platform.services.ICustomRegistry;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class FPJobs {
	public static final ResourceKey<Registry<JobType>> REGISTRY_KEY =
			ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "job"));

	public static final Identifier NONE_ID = id("none");

	private static final ICustomRegistry<JobType> REGISTRY =
			Services.COMMON_REGISTRY.createRegistry(REGISTRY_KEY);

	// registration order is the cycle order, and the cycle is the only thing that depends on it.
	// it is not the legacy ordinal order and must never be treated as it: LegacyJobIds owns that.
	private static final List<Identifier> ORDER = new ArrayList<>();

	public static final Supplier<JobType> NONE = register("none", b -> b.displayName("None"));
	public static final Supplier<JobType> IDLE = register("idle", b -> b
			.displayName("Idle")
			.rows(JobRow.WAYPOINT)
			.executor(IdleJobExecutor::new));
	public static final Supplier<JobType> GUARD = register("guard", b -> b
			.displayName("Guard")
			.rows(JobRow.WAYPOINT, JobRow.PATROL)
			.executor(GuardJobExecutor::new));
	public static final Supplier<JobType> FOLLOW = register("follow", b -> b.displayName("Follow"));
	public static final Supplier<JobType> PATROL = register("patrol", b -> b
			.displayName("Patrol")
			.notSelectable());
	public static final Supplier<JobType> DEPOSIT = register("deposit", b -> b
			.displayName("Deposit")
			.notSelectable());
	public static final Supplier<JobType> COURIER = register("courier", b -> b
			.displayName("Courier")
			.rows(JobRow.SOURCE, JobRow.DEPOSIT, JobRow.FILTER)
			.executor(CourierJobExecutor::new));
	public static final Supplier<JobType> MINER = register("miner", b -> b
			.displayName("Miner")
			.rows(JobRow.REGION, JobRow.DEPOSIT, JobRow.FILTER)
			.executor(MinerJobExecutor::new));
	public static final Supplier<JobType> LUMBERJACK = register("lumberjack", b -> b
			.displayName("Lumberjack")
			.rows(JobRow.REGION, JobRow.DEPOSIT)
			.executor(LumberjackJobExecutor::new));
	public static final Supplier<JobType> FISHERMAN = register("fisherman", b -> b
			.displayName("Fisherman")
			.rows(JobRow.WAYPOINT, JobRow.DEPOSIT)
			.executor(FishermanJobExecutor::new));
	public static final Supplier<JobType> FARMER = register("farmer", b -> b
			.displayName("Farmer")
			.rows(JobRow.REGION, JobRow.DEPOSIT)
			.executor(FarmerJobExecutor::new));
	public static final Supplier<JobType> CRAFTER = register("crafter", b -> b
			.displayName("Crafter")
			.rows(JobRow.WAYPOINT, JobRow.SOURCE, JobRow.DEPOSIT, JobRow.TEACH)
			.executor(CrafterJobExecutor::new));
	public static final Supplier<JobType> QUARTERMASTER = register("quartermaster", b -> b
			.displayName("Quartermaster")
			.rows(JobRow.POOL, JobRow.REQUEST)
			.executor(QuartermasterJobExecutor::new));
	public static final Supplier<JobType> RUNNER = register("runner", b -> b
			.displayName("Runner")
			.executor(RunnerJobExecutor::new));

	public static Identifier id(String name) {
		return Identifier.fromNamespaceAndPath(Constants.MOD_ID, name);
	}

	/** Registers a job under this mod's namespace. Addons call {@link #register(String, String, Supplier)}. */
	private static Supplier<JobType> register(String name, java.util.function.UnaryOperator<JobType.Builder> build) {
		Identifier full = id(name);
		ORDER.add(full);
		return REGISTRY.register(Constants.MOD_ID, name,
				() -> build.apply(JobType.builder(full)).build());
	}

	/**
	 * Registers a job in an addon's own namespace. Call from the addon's initialiser, before
	 * registries freeze.
	 */
	public static Supplier<JobType> register(String modid, String name, Supplier<JobType> job) {
		ORDER.add(Identifier.fromNamespaceAndPath(modid, name));
		return REGISTRY.register(modid, name, job);
	}

	/** @return the job registered under that id, or null when its mod is not present. */
	@Nullable
	public static JobType get(@Nullable Identifier id) {
		return id == null ? null : REGISTRY.get(id);
	}

	/** True when {@code id} names exactly {@code job}. Replaces the old {@code job() == Job.X}. */
	public static boolean is(@Nullable Identifier id, Supplier<JobType> job) {
		return id != null && id.equals(job.get().id());
	}

	/** Cycle order: registration order, with the marker pseudo-jobs left out. */
	public static List<Identifier> selectableOrder() {
		List<Identifier> out = new ArrayList<>();
		for (Identifier candidate : ORDER) {
			JobType job = get(candidate);
			if (job != null && job.selectable()) out.add(candidate);
		}
		return out;
	}

	public static void init() {
		LegacyJobIds.verify();
	}
}
