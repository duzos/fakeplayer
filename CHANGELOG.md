# v3.0.0

## NEW

- added Minecraft 26.3 support. thanks to user_mmjpzmuskryt6iik for asking.
- added Quartermaster and Runner jobs. mark storage with the Pool marker, and Runners collect and deliver requested items.
- request items from the Quartermaster's Browse menu: click for a stack, sneak-click for one, ctrl-click for all. you can track and cancel orders there too.
- added modded fishing rod support. thanks to RageQKM for the Aquaculture report and suggestion.
- added Aquaculture tackle, bait, hook textures and dyed lines on Forge/NeoForge. lava fishing also works with its lava fishing addon and a suitable hook.
- addons can register jobs and manage item requests through the API.
- fakes can keep a list of items stocked. type it into the Stock box in the AI menu, like `minecraft:torch 64, minecraft:bread 16`, and the fake asks a Quartermaster whenever it drops below a target, without stopping work.
- when the storeroom can't fill a request, a bonded Crafter makes up the difference. the Quartermaster plans the whole recipe chain, counts existing stock, and crafts only the shortfall.
- if a chain can't be finished you get one message listing everything missing. a crafted request needs a bonded, free Crafter with a table marked.
- a miner with no pickaxe, a lumberjack with no axe, a farmer with no hoe and a fisherman with no rod now request one and carry on once it arrives. it asks for the best tool the storeroom holds, judged on real stats, so modded tools count and worn ones are passed over.
- a Runner that finds its requester gone now says so once, then still waits out the orphan window.
- fakes now ride boats, minecarts, Create seats and trains and other mods' seats. seat one with its AI marker, and use stairs on it to get off. a fake at a train's conductor seat runs the train from a schedule.
- added the Possession Link. link it to your fake, use it in the air to see and act through the fake, and sneak to return.
- added the Builder job. give it a structure file and a chest of blocks and it builds it, asking a Quartermaster for anything missing. several Builders can share one build.
- added the Chunk Anchor. install it in your fake and the chunks around it stay loaded, so it keeps working while you're away.
- fakes can use drawers and other modded storage as sources, deposits and pool storage. thanks to SmollNarwhal for the suggestion.
- idle fakes watch nearby players, wave when their owner comes back and sleep in a free bed at night.
- what a fake says shows in a speech bubble over its head, and a ! or ? appears when it's blocked or stuck.

## CHANGED

- jobs now use namespaced IDs. existing jobs carry over; missing addon jobs keep their settings until you reinstall the addon or change the job.
- Runners show deliveries in their hand and return to their Quartermaster when idle. requests use the nearest Quartermaster with stock.
- requests wait if they can't be filled, with one message per problem. messages sent while you're offline arrive when you log back in.
- request range now defaults to 256 blocks. custom settings are kept.
- fishermen use vanilla fishing loot, including mod additions. custom tackle bonuses currently only work with Aquaculture; Fishing Real catches stay as items.
- fishermen keep their equipped rod, deposit spares and take a replacement from storage when needed. offhand rods work too.
- the addon request API now takes namespaced job IDs.
- on 26.3, farmers use any item in the `#minecraft:hoes` tag. a modded hoe outside that tag won't count.
- a fake's arrows, explosions and other damage no longer hurt itself, its owner, the owner's other fakes or the owner's pets, and it won't fight back against them.
- shift-click the job button to cycle jobs backwards.
- fake messages now look like normal chat lines from the fake.

## FIXED

- fishermen now aim for open water so they can catch treasure.
- fishermen request a missing rod and resume when it arrives.
- working fakes no longer follow a held redstone torch.

## REMOVED

- none.
