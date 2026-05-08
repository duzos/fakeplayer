package dev.duzo.players.entities.ai;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

public final class JobExecutors {
	private static final Map<Job, Supplier<JobExecutor>> REGISTRY = new EnumMap<>(Job.class);

	static {
		register(Job.NONE, NoopJobExecutor::new);
		register(Job.IDLE, IdleJobExecutor::new);
		register(Job.GUARD, NoopJobExecutor::new);
		register(Job.FOLLOW, NoopJobExecutor::new);
		register(Job.PATROL, NoopJobExecutor::new);
		register(Job.DEPOSIT, NoopJobExecutor::new);
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
