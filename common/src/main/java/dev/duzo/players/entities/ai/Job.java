package dev.duzo.players.entities.ai;

public enum Job {
	NONE("None"),
	IDLE("Idle"),
	GUARD("Guard"),
	FOLLOW("Follow"),
	PATROL("Patrol"),
	DEPOSIT("Deposit"),
	LUMBERJACK("Lumberjack");

	private final String label;

	Job(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	public static Job byOrdinal(int ord) {
		Job[] all = values();
		if (ord < 0 || ord >= all.length) return NONE;
		return all[ord];
	}
}
