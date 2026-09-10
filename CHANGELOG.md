# v2.3.0

- Fakes can ask each other for items, through two new jobs. A **Quartermaster** owns a storeroom you define by marking chests with its Pool marker, keeps track of what is in them, and works out who can fill a request. A **Runner** does the carrying: it collects from the pool and delivers to whoever asked. The Quartermaster never leaves the storeroom, so you scale a base up by bonding more Runners to it rather than by making one fake faster.
- A Fisherman that has no fishing rod now asks for one and waits, instead of standing there doing nothing. The rod arrives if the pool has one, and it gets back to fishing on its own.
- You can ask a Quartermaster for items yourself, from the new Request row in its AI menu. Type an item id and optionally a count, like `minecraft:oak_planks 64`, and a Runner brings it to wherever you are standing. Asking again for a larger amount tops up the request you already have rather than starting a second one.
- Requests that cannot be filled tell you once and then stay quiet. The fake keeps waiting rather than unbonding itself, and you get one message per problem rather than a repeat every few seconds. A message that arrives while you are logged out is held until you are back.
- The nearest Quartermaster that actually has the item wins, so a nearby empty storeroom does not shadow a stocked one further away.
- Addons can use all of this. `dev.duzo.players.api.requests.FakePlayerRequests` is a single entry point for raising a request, following it, and cancelling it, plus two hooks: a resolver chain for sourcing items the pool does not have, and a listener for watching requests through their whole life. Addon-authored jobs are not possible yet.

The Courier is unchanged and is not involved in requests: it keeps doing standing chest-to-chest runs.
