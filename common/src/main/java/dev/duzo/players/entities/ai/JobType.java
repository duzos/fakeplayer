package dev.duzo.players.entities.ai;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A registered job: what it does, what it is called, which marker rows it offers, and whether a
 * player can pick it from the cycle button.
 *
 * <p>Two jobs are equal only when they are the same instance, so the registry's own identity is the
 * identity of the job. Compare with {@code ==} or compare {@link #id()}.
 */
public final class JobType {
	private final Identifier id;
	private final Component displayName;
	private final boolean selectable;
	private final List<JobRow> rows;
	private final Supplier<JobExecutor> executor;
	private final int progressGraceSeconds;

	private JobType(Identifier id, Component displayName, boolean selectable, List<JobRow> rows,
			Supplier<JobExecutor> executor, int progressGraceSeconds) {
		this.id = Objects.requireNonNull(id, "id");
		this.displayName = Objects.requireNonNull(displayName, "displayName");
		this.selectable = selectable;
		this.rows = List.copyOf(rows);
		this.executor = Objects.requireNonNull(executor, "executor");
		this.progressGraceSeconds = Math.max(0, progressGraceSeconds);
	}

	public Identifier id() { return id; }
	public Component displayName() { return displayName; }
	public List<JobRow> rows() { return rows; }

	/** False for the marker pseudo-jobs, which exist but are not offered by the cycle button. */
	public boolean selectable() { return selectable; }

	/**
	 * Seconds a running job may go without reporting progress before the fake is flagged as stuck, or 0 to
	 * never flag it. Only jobs that call {@code FakePlayerEntity.markProgress()} can use this.
	 */
	public int progressGraceSeconds() { return progressGraceSeconds; }

	public JobExecutor createExecutor() { return executor.get(); }

	public static Builder builder(Identifier id) {
		return new Builder(id);
	}

	public static final class Builder {
		private final Identifier id;
		private Component displayName;
		private boolean selectable = true;
		private List<JobRow> rows = List.of();
		private Supplier<JobExecutor> executor = NoopJobExecutor::new;
		private int progressGraceSeconds;

		private Builder(Identifier id) {
			this.id = id;
		}

		public Builder displayName(String literal) {
			this.displayName = Component.literal(literal);
			return this;
		}

		public Builder displayName(Component name) {
			this.displayName = name;
			return this;
		}

		public Builder notSelectable() {
			this.selectable = false;
			return this;
		}

		public Builder rows(JobRow... rows) {
			this.rows = List.of(rows);
			return this;
		}

		public Builder executor(Supplier<JobExecutor> executor) {
			this.executor = executor;
			return this;
		}

		/** Opt in to stuck detection. 0, the default, never flags the job. */
		public Builder progressGraceSeconds(int seconds) {
			this.progressGraceSeconds = seconds;
			return this;
		}

		public JobType build() {
			if (displayName == null) throw new IllegalStateException("job " + id + " has no display name");
			return new JobType(id, displayName, selectable, rows, executor, progressGraceSeconds);
		}
	}
}
