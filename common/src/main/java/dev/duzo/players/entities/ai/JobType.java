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

	private JobType(Identifier id, Component displayName, boolean selectable, List<JobRow> rows,
			Supplier<JobExecutor> executor) {
		this.id = Objects.requireNonNull(id, "id");
		this.displayName = Objects.requireNonNull(displayName, "displayName");
		this.selectable = selectable;
		this.rows = List.copyOf(rows);
		this.executor = Objects.requireNonNull(executor, "executor");
	}

	public Identifier id() { return id; }
	public Component displayName() { return displayName; }
	public List<JobRow> rows() { return rows; }

	/** False for the marker pseudo-jobs, which exist but are not offered by the cycle button. */
	public boolean selectable() { return selectable; }

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

		public JobType build() {
			if (displayName == null) throw new IllegalStateException("job " + id + " has no display name");
			return new JobType(id, displayName, selectable, rows, executor);
		}
	}
}
