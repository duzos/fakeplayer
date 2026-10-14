# v3.0.0

## NEW

- added Quartermaster and Runner jobs. mark storage with the Pool marker, and Runners collect and deliver requested items.
- request items from the Quartermaster's Browse menu: click for a stack, sneak-click for one, ctrl-click for all. you can track and cancel orders there too.
- added modded fishing rod support. thanks to RageQKM for the Aquaculture report and suggestion.
- added Aquaculture tackle, bait, hook textures and dyed lines on Forge/NeoForge. lava fishing also works with its lava fishing addon and a suitable hook.
- addons can register jobs and manage item requests through the API.
- fakes can keep a list of items to have on hand: type them into the Stock box in the AI menu, like `minecraft:torch 64, minecraft:bread 16`, and they request top-ups from a Quartermaster without stopping work.
- when the storeroom can't fill a request, a bonded Crafter makes up the difference. the Quartermaster plans the whole recipe chain, counts existing stock, and sends one message listing everything still missing.
- jobs blocked without a tool now request one: Miners without a pickaxe, Lumberjacks without an axe, Farmers without a hoe and Fishermen without a rod. they ask for the best tool the storeroom holds, judged by the real stack rather than its name, so modded tools work and worn ones are passed over.
- a Runner whose requester has gone now says so once instead of sitting silent for five minutes. it still waits and returns the goods if nobody turns up.
- fakes now ride boats, minecarts, Create seats and trains and other mods' seats. seat one with its AI marker, and use stairs on it to get off. a fake at a train's conductor seat runs the train from a schedule.
- added the Possession Link. link it to your fake, use it in the air to see and act through the fake, and sneak to return.

## CHANGED

- jobs now use namespaced IDs. existing jobs carry over; missing addon jobs keep their settings until you reinstall the addon or change the job.
- Runners show deliveries in their hand and return to their Quartermaster when idle. requests use the nearest Quartermaster with stock.
- requests wait if they can't be filled, with one message per problem. messages sent while you're offline arrive when you log back in.
- request range now defaults to 256 blocks. custom settings are kept.
- fishermen use vanilla fishing loot, including mod additions. custom tackle bonuses currently only work with Aquaculture; Fishing Real catches stay as items.
- fishermen keep their equipped rod, deposit spares and take a replacement from storage when needed. offhand rods work too.
- the addon request API now takes namespaced job IDs.
- a fake's arrows, explosions and other damage no longer hurt itself, its owner, the owner's other fakes or the owner's pets, and it won't fight back against them.

## FIXED

- fixed the Fabric startup crash from the fishing rod cast model. thanks to user_ibivn35hvseplw7798233 for reporting it on 1.21.4.
- fishermen now aim for open water so they can catch treasure.
- fishermen request a missing rod and resume when it arrives.
- working fakes no longer follow a held redstone torch.

## REMOVED

- none.
