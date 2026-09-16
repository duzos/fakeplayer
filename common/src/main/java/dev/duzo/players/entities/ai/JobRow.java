package dev.duzo.players.entities.ai;

/**
 * The marker rows a job's AI menu shows. Named here rather than in the screen so a registered
 * {@link JobType} can declare its rows without a common class touching client code.
 */
public enum JobRow {
	WAYPOINT,
	REGION,
	DEPOSIT,
	SOURCE,
	TEACH,
	FILTER,
	PATROL,
	POOL,
	REQUEST
}
