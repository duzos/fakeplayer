# v3.0.0

## NEW

- added Quartermaster and Runner jobs. mark storage with the Pool marker, and Runners collect and deliver requested items.
- request items from the Quartermaster's Browse menu: click for a stack, sneak-click for one, ctrl-click for all. you can track and cancel orders there too.
- added modded fishing rod support. thanks to RageQKM for the Aquaculture report and suggestion.
- fishermen using a Tide rod roll Tide's own fish and loot, including the vanilla rod if you let Tide replace it. bait, the journal and the minigame are not used, and you won't get loot crates, so odds differ from fishing yourself.
- fakes can use TACZ guns as ranged weapons: they draw, aim, shoot, bolt and reload from their own inventory. thanks to DitzyBunny for the suggestion. works on 1.20.1 and 1.21.1 only. melee, fire modes and attachments aren't supported.
- added Aquaculture tackle, bait, hook textures and dyed lines on Forge/NeoForge. lava fishing also works with its lava fishing addon and a suitable hook.
- addons can register jobs and manage item requests through the API.
- added a Stock box to worker AI menus. set items and amounts, and workers request refills while they work.
- Crafters can fill requests using materials from storage, including recipes with several steps. this needs a free, bonded Crafter with a marked table.
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
- tool requests pick the best usable tool in storage, including supported modded tools. worn-out tools are skipped.
- a fake's arrows, explosions and other damage no longer hurt itself, its owner, the owner's other fakes or the owner's pets, and it won't fight back against them.

## FIXED

- fishermen now aim for open water so they can catch treasure.
- miners, lumberjacks, farmers and fishermen request missing tools and resume when they arrive.
- working fakes no longer follow a held redstone torch.
- Runners now tell you if a delivery's recipient is missing. they wait, then return the items if the request times out.

## REMOVED

- none.
