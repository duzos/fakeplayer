# v3.0.0

## NEW

- fishermen can assemble River Fishing rods, request missing parts and fish from shore. fly, winter, boat, trolling and stocked ponds aren't supported yet. thanks to user_xkelxur3h8vuiwfb for the suggestion.
- added Quartermaster and Runner jobs. mark storage with the Pool marker, and Runners collect and deliver requested items.
- request items from the Quartermaster's Browse menu: click for a stack, sneak-click for one, ctrl-click for all. you can track and cancel orders there too.
- added modded fishing rod support. thanks to RageQKM for the Aquaculture report and suggestion.
- added Aquaculture tackle, bait, hook textures and dyed lines on Forge/NeoForge. lava fishing also works with its lava fishing addon and a suitable hook.
- addons can register jobs and manage item requests through the API.
- fakes can keep a list of items stocked. type it into the Stock box in the AI menu, like `minecraft:torch 64, minecraft:bread 16`, and the fake asks a Quartermaster whenever it drops below a target, without stopping work.
- when the storeroom can't fill a request, a bonded Crafter makes up the difference. the Quartermaster plans the whole recipe chain, counts existing stock, and crafts only the shortfall.
- if a chain can't be finished you get one message listing everything missing. a crafted request needs a bonded, free Crafter with a table marked.

## CHANGED

- jobs now use namespaced IDs. existing jobs carry over; missing addon jobs keep their settings until you reinstall the addon or change the job.
- Runners show deliveries in their hand and return to their Quartermaster when idle. requests use the nearest Quartermaster with stock.
- requests wait if they can't be filled, with one message per problem. messages sent while you're offline arrive when you log back in.
- request range now defaults to 256 blocks. custom settings are kept.
- fishermen use vanilla fishing loot, including mod additions, unless using River Fishing rods. Fishing Real catches stay as items.
- fishermen keep their equipped rod, deposit spares and take a replacement from storage when needed. offhand rods work too.
- the addon request API now takes namespaced job IDs.
- tool requests pick the best usable tool in storage, including supported modded tools. worn-out tools are skipped.

## FIXED

- River Fishing lines now start at the rod tip.

- fishermen now aim for open water so they can catch treasure.
- miners, lumberjacks, farmers and fishermen request missing tools and resume when they arrive.
- working fakes no longer follow a held redstone torch.
- Runners now tell you if a delivery's recipient is missing. they wait, then return the items if the request times out.

## REMOVED

- none.
