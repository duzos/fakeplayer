# v2.1.0

- AI sub-menu in the management gui (#27). The AI button on the AI/SL/TG row now opens a sub-screen with bond/unbond, a no-ai toggle, a job picker, and waypoint/region/deposit marker buttons. Each marker hands you a single-use AI marker item; right-click a block (or a chest, for deposit) to set the param. Region needs two clicks for corners A and B. State lives in an `aiState` compound on the entity and syncs to tracking clients.
- Session item framework (#28). The marker handed out by the AI sub-menu now carries fakePlayerUUID, ownerUUID, purpose and expiresAt on its stack. A server tick sweep silently removes the marker when it strays past 32 blocks from its bound fake, after 90 seconds of no clicks, on death/disconnect, or when you sneak right-click air to cancel.
