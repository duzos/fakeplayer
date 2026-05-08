# v2.1.0

- AI sub-menu in the management gui (#27). New `...` button on the AI/SL/TG row opens a screen with bond/unbond, a job picker, and waypoint/region/deposit marker buttons. Each marker hands you a single-use AI marker item; right-click a block (or a chest, for deposit) to set the param. Region needs two clicks for corners A and B. State lives in an `aiState` compound on the entity and syncs to tracking clients.
