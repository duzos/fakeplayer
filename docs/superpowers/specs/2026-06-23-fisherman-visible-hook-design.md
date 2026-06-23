# Fisherman — Real Visible Fishing Hook (design)

**Date:** 2026-06-23
**Branch:** `feat/issue_48/1.21.11` (issue #48)
**Status:** approved approach, pre-implementation

## Goal

The Fisherman job currently rolls the fishing loot table on a timer with no visible hook. Make it look like the fake really fishes: it swings, a bobber arcs out and lands in the water, a fishing line connects the fake's hand to the bobber, the bobber bobs and dips on a bite, and the catch flies back from the bobber to the fake.

## Constraint that shapes the design

- `FakePlayerEntity extends PathfinderMob`, **not** `Player`. Vanilla `FishingHook` requires a `Player` owner (its constructor, `tick()`, `retrieve()`, and `FishingHookRenderer` all assume one), so we can't own a vanilla hook without a mixin.
- MC 1.21.11 here uses the **submit-node render pipeline** (`EntityRenderer.submit(state, PoseStack, SubmitNodeCollector, CameraRenderState)`, `Identifier` for `ResourceLocation`). Drawing custom line geometry inside an `EntityRenderer` is awkward in this pipeline.
- The project is deliberately **mixin-free** (`players.mixins.json` is empty scaffolding; zero mixin classes) and ports across 7 MC versions / 2 loaders.

**Chosen approach (deliberated against a mixin): a custom `Projectile` entity for physics + a world-overlay renderer for the line/bobber.** This touches no vanilla classes, registers through the same path as the existing fake-player entity, and reuses the project's proven world-render line-drawing path (`SessionItemRenderer`). The mixin alternative was rejected: it would introduce the project's first mixins targeting two vanilla classes (`FishingHook` server + `FishingHookRenderer` client) across 7 mappings plus AW/AT widening, and would *still* need a custom renderer because the owner is a `PathfinderMob`, not a client-side `Player`.

## Architecture

```
FishermanJobExecutor (server)             FakeFishingHook (entity, common)
  CAST  -> spawn hook, arc to waypoint  -> FLYING: gravity + move until water hit
  WAIT  -> Lure-shortened delay         -> BOBBING: float at water surface
  BITE  -> hook.setBiting(true), dip    -> biting flag synced; dip + particles
  REEL  -> roll loot table (TOOL+Luck)
           spawn catch at bobber,
           velocity toward fake;
           discard hook
  (vacuum each tick pulls the catch in)
  deposit every 16 / on rod break

Rendering (client, no entity geometry):
  FishingLineRenderer.render(poseStack)   <- called from the SAME world-render hook
    for each FakeFishingHook in level:        that already drives SessionItemRenderer
      draw line: fake hand -> bobber           (Fabric WorldRenderEvents.AFTER_ENTITIES;
      draw bobber marker at hook pos             NeoForge RenderLevelStageEvent)
  FakeFishingHookRenderer = no-op EntityRenderer (entities must have one registered)
```

## Components

### 1. `common/.../entities/FakeFishingHook.java` (new) — `extends Projectile`

State / sync:
- `private enum State { FLYING, BOBBING }` — server-side physics state (saved).
- `EntityDataAccessor<Integer> DATA_OWNER_ID` — the owning fake's entity id, synced so the client renderer can resolve the hand position. Set on spawn.
- `EntityDataAccessor<Boolean> DATA_BITING` — true during the BITE beat so the client dips the bobber.
- `private int life` — ticks alive; hard cap (e.g. 20*60) as an orphan backstop.

Construction / spawn:
- Standard `(EntityType, Level)` ctor for deserialize; a convenience ctor `(Level, FakePlayerEntity owner)` that stores the owner ref and seeds `DATA_OWNER_ID`.
- Executor positions it at the fake's hand and calls `shoot(dx, dy, dz, velocity, spread)` toward the waypoint center, then `level.addFreshEntity(hook)`.
- Override `getAddEntityPacket`/`recreateFromPacket` is **not** required because we sync the owner id through `SynchedEntityData` instead (simpler, loader-agnostic).

`tick()`:
- `super.tick()`; client side returns early (vanilla position interpolation handles motion; render bob/dip is purely visual).
- Server: resolve owner via `DATA_OWNER_ID`; if owner null/dead → `discard()`. (Rod and job lifecycle are the executor's responsibility; the executor removes the hook on reel/pause/deposit. The owner-gone check is just an orphan backstop, alongside `life` cap.)
- FLYING: if entering water (`level.getFluidState(blockPosition()).is(FluidTags.WATER)`) → damp motion (`getDeltaMovement().scale(0.3,0.2,0.3)`), `State=BOBBING`; else apply gravity and `move(MoverType.SELF, getDeltaMovement())`. If it hits a non-water block / `horizontalCollision` before water, keep it resting there (still visible) — the executor will still reel on schedule.
- BOBBING: ease Y toward the water surface (`floor(y)+fluidHeight`) and damp horizontal motion to ~0 — lifted from vanilla `FishingHook`'s bobbing math, minus the nibble RNG. A gentle render-time sine bob is added client-side, not synced.

Persistence: `addAdditionalSaveData`/`readAdditionalSaveData` store `State` and `life`. Owner id is re-seeded by the executor on resume; a hook that loads without a live owner self-discards.

### 2. `common/.../core/FPEntities.java` (modify)

Register like `FAKE_PLAYER`:
```java
public static final Supplier<EntityType<FakeFishingHook>> FISHING_HOOK = register("fishing_hook",
    () -> EntityType.Builder.<FakeFishingHook>of(FakeFishingHook::new, MobCategory.MISC)
        .sized(0.25F, 0.25F).clientTrackingRange(5).updateInterval(1)
        .build(ResourceKey.create(Registries.ENTITY_TYPE, PlayersCommon.id("fishing_hook"))));
```
No attributes (not a `LivingEntity`).

### 3. `common/.../client/renderers/FakeFishingHookRenderer.java` (new) — no-op

`extends EntityRenderer<FakeFishingHook, EntityRenderState>`. `createRenderState()` returns a minimal state; `submit(...)` does nothing (no shadow, no geometry). Exists only so the registered entity doesn't crash with "no renderer". All visuals come from the world hook.

Registered:
- Fabric: `EntityRendererRegistry.register(FPEntities.FISHING_HOOK.get(), FakeFishingHookRenderer::new)` in `PlayersFabricClient`.
- NeoForge: `event.registerEntityRenderer(FPEntities.FISHING_HOOK.get(), FakeFishingHookRenderer::new)` in `ClientModEvents`.

### 4. `common/.../client/renderers/FishingLineRenderer.java` (new) — world overlay

Mirrors `SessionItemRenderer`: takes the `PoseStack`, grabs `Minecraft`, `MultiBufferSource.BufferSource`, camera position. Iterates `level.entitiesForRendering()` for `FakeFishingHook` instances. For each:
- Resolve the owner fake via `DATA_OWNER_ID`; compute a hand anchor from the fake's position + look (eye height, slight forward/side offset for the main hand). Approximate — does not need vanilla's exact arm-swing math.
- Draw the **line** as a multi-segment curve (slight catenary sag) from hand to bobber, using `RenderTypes.lines()` like `SessionItemRenderer`.
- Draw the **bobber**: a small camera-facing textured quad using vanilla `minecraft:textures/entity/fishing_hook.png` (via an entity-cutout `RenderType`). Apply a client-side sine bob on Y and, when `DATA_BITING`, a downward dip. Fallback if the textured quad proves fiddly in this mapping: a small solid marker consistent with the mod's wireframe visual language.

Registered by adding a second call next to the existing `SessionItemRenderer.render(...)` in both loaders' world-render hooks.

### 5. `common/.../entities/ai/FishermanJobExecutor.java` (modify)

- Add `BITE` to the phase enum: `TO_SPOT → CAST → WAIT → BITE → REEL → (loop) → TO_DEPOSIT → DUMP`.
- Hold a transient `FakeFishingHook activeHook`.
- `CAST`: require a rod; before spawning, discard any tracked hook; spawn `FakeFishingHook`, position at the fake's hand, `shoot` toward `Vec3.atCenterOf(spot)` with an upward arc; swing main hand; compute Lure-shortened `waitUntil`; → `WAIT`.
- `WAIT`: when `gameTime >= waitUntil` → `BITE`; set `activeHook.setBiting(true)`, start a short bite timer (~10–20 ticks), spawn splash/fishing particles at the bobber.
- `BITE`: when the bite timer elapses → `REEL`.
- `REEL`: roll the loot table (unchanged: `TOOL=rod`, `withLuck`); for each drop spawn an `ItemEntity` at the bobber position with velocity toward the fake (arc back, short pickup delay); discard `activeHook`; clear biting; damage rod; `caught++`; then deposit-every-16-or-rod-broken logic → `TO_DEPOSIT` or `CAST`.
- Each tick (any phase), `JobHelpers.vacuum(level, entity, ~2.5)` so the in-flight catch is sucked into the fake's inventory when it arrives.
- `onPause` and on entering `TO_DEPOSIT`: discard `activeHook` (no orphaned bobber while walking/paused).
- On `deserialize`, if phase was `WAIT/BITE/REEL`, reset to `CAST` (re-cast cleanly; the old hook, if any, self-discards as an orphan).

## Data flow

cast (swing + spawn) → bobber arcs to water → floats & bobs → bite (dip + particles) → reel (loot rolled; catch spawns at bobber, flies to fake; hook removed) → fake vacuums the catch → repeat → every 16 catches or rod break: remove hook, walk to chest, dump all but the rod, resume.

## Edge cases / lifecycle

- **Rod breaks mid-cycle:** REEL's existing logic detects the empty rod and routes to deposit; the hook is already removed at REEL.
- **No water at waypoint:** hook rests on the block it hits and stays visible; reel still fires on schedule (it just looks like casting onto land). Acceptable for v1; the in-game test verifies water placement.
- **World reload:** transient `activeHook` is lost; executor re-casts from `CAST`; any loaded orphan hook self-discards (no live owner re-seeded) and is also bounded by the `life` cap.
- **Chunk unload / owner teleport:** owner-gone check + `life` cap discard the hook.
- **Pause / job change:** `onPause` discards the hook.

## Rendering risk (flagged, like the plan's mapping-sensitive calls)

The world-hook **line** reuses an API path proven in `SessionItemRenderer` (`RenderTypes.lines()` + `MultiBufferSource`) — low risk. The **textured bobber quad** uses an entity-cutout `RenderType` + camera billboarding whose exact names in this renamed mapping I'll confirm at compile time; fallback is a small solid/wireframe bobber. Staged: land line + simple bobber first (the "thrown hook with a line, bobbing" core), polish the bobber texture/dip after in-game confirmation.

## Out of scope

- Porting to the other 6 MC versions (separate `feat/issue_48/<version>` branches after in-game sign-off).
- A wrapper `Player` (not needed — the FISHING loot context uses only `ORIGIN` + `TOOL`, already working).
- Vanilla-style random nibble timing (the job drives timing deterministically via Lure).

## Test checklist (Fabric dev client)

1. Fisherman job, rod in inventory, waypoint at a water edge, deposit chest set, START JOB.
2. Fake swings; a bobber **arcs out and lands in the water** with a **visible line from his hand**.
3. Bobber **bobs**; after the Lure-adjusted wait it **dips** (bite) with particles.
4. The **catch flies from the bobber back to the fake** and enters his inventory.
5. After 16 catches or when the rod breaks: hook disappears, he walks to the chest, dumps all but the rod, resumes.
6. Lure shortens the wait; Luck of the Sea improves the catch.
7. No orphaned bobbers after pausing, changing job, or reloading the world.
