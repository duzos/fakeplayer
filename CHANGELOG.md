# v2.0.6

- Armor, held items, and the attack swing animation now display on fake players (#21).
- Recipes load on the 1.21.2+ data pack format so JEI lists them (#21).

# v2.0.5

- Fake players no longer despawn (#16).
- Added `/players spawn <skin> [pos]` command.
- Added click sounds on interactions and a page turn on chat.
- Added `config/players.json` for default skin, attributes, and persistence.
- Tolerate missing nbt tags when loading legacy fake players.
- Cached custom name component to avoid per-frame allocation.
- 1.21.1 build targets NeoForge and Java 21.
