package dev.duzo.players.entities.ai;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

public final class JobExecutors {
	private static final Map<Job, Supplier<JobExecutor>> REGISTRY = new EnumMap<>(Job.class);

	static {
		register(Job.NONE, NoopJobExecutor::new);
		register(Job.IDLE, IdleJobExecutor::new);
		register(Job.GUARD, GuardJobExecutor::new);
		register(Job.FOLLOW, NoopJobExecutor::new);
		register(Job.PATROL, NoopJobExecutor::new);
		register(Job.DEPOSIT, NoopJobExecutor::new);
		register(Job.COURIER, CourierJobExecutor::new);
		register(Job.MINER, MinerJobExecutor::new);
		register(Job.LUMBERJACK, LumberjackJobExecutor::new);
		register(Job.FISHERMAN, FishermanJobExecutor::new);
	}

	private JobExecutors() {}

	public static void register(Job job, Supplier<JobExecutor> factory) {
		REGISTRY.put(job, factory);
	}

	public static JobExecutor create(Job job) {
		Supplier<JobExecutor> factory = REGISTRY.get(job);
		return factory == null ? new NoopJobExecutor() : factory.get();
	}
}
