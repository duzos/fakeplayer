package dev.duzo.players.platform.services;

/**
 * What the loader's block-use event said about a possessor's click. DENY refuses it, HANDLED means a listener already
 * dealt with the click so the action must not be repeated, and PROCEED carries whether the block and the item may each act.
 */
public record UseCheck(Verdict verdict, boolean block, boolean item) {
	public enum Verdict { DENY, HANDLED, PROCEED }

	public static final UseCheck DENY = new UseCheck(Verdict.DENY, false, false);
	public static final UseCheck HANDLED = new UseCheck(Verdict.HANDLED, false, false);
	public static final UseCheck PROCEED = new UseCheck(Verdict.PROCEED, true, true);
}
