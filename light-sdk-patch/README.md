# light-sdk-patch

Every change Amp makes to Light's SDK, as one patch file per area, applied to
the pristine `light-sdk` submodule. The build applies the whole set
automatically when the submodule is clean (see `settings.gradle.kts`) and then
*leaves it applied*, so the sources you read and debug are the sources that
built. `scripts/sdk-patches.sh` has `apply` / `revert` / `check` / `regen`.

Two kinds of change live here, and the distinction is the whole point:

- **SDK PATCH** — additive and meant to be upstreamed to
  [lightphone/light-sdk](https://github.com/lightphone/light-sdk). These stay
  until Light merges an equivalent.
- **SPIKE** (files prefixed `spike-`) — deliberately outside the SDK's rules,
  possible only because the sandbox plugin exempts SDK modules from source
  validation. These are deleted before any store submission. Full revert steps
  are in [tool/docs/SDK-PATCHES.md](../tool/docs/SDK-PATCHES.md).

| Patch | What it is |
|---|---|
| `audio-player.patch` | **Mixed.** Upstreamable: incremental queue ops (`addItems`/`addItemAt`/`removeItem`/`moveItem`/`replaceRange`/`seekToIndex`), `setMediaQueueAt(items, index, positionMs)` so a queue can restore to an index *and* position. Spike: the Media3 `MediaSession`, `MediaController` connect and device-volume plumbing for background audio + hardware volume keys. |
| `connectivity-backport.patch` | `LightConnectivity` + its README section, taken verbatim from upstream after our pin. **Drops entirely when the submodule moves past upstream #166.** |
| `light-activity.patch` | **Mixed.** Upstreamable: `popToRoot()` unwind (publishes `currentScreen` once — assigning per pop flashed every dying screen). Spikes: volume keys fall through to the system instead of being forwarded to LightOS; `LightDisplayColor` foreground/background hooks. |
| `light-db-fallback.patch` | `buildDatabase` gains `fallbackToDestructiveMigration` — a tool can't register migrations, and a tool DB is a rebuildable cache. |
| `loading-icon-centering.patch` | Centres the splash word in its 240-unit viewport (it sat 87.5px high on the panel, measured). |
| `pop-to-root-screen.patch` | The public `LightScreen.popToRoot()` for the unwind in `light-activity.patch`. |
| `runtime-permission.patch` | `hasRuntimePermission()` — asks the process about a grant directly; the server answers from policy, not the grant, and can say Blocked for access the phone already allows. |
| `spike-background-services.patch` | `LightMediaService` (foreground `MediaSessionService`: background audio, media notification, keep-alive through pause), `LightTransferService` (`dataSync` service so long downloads aren't throttled 9× in the cached bucket), their manifest entries + FGS permissions, and the `media3.session` dependency. |
| `spike-display-color.patch` | `LightDisplayColor` — lifts LightOS's global greyscale daltonizer while Amp is foreground, via `WRITE_SECURE_SETTINGS` (a `pm grant` the user runs; a clean no-op without it). |
| `spike-dlna-cast.patch` | `DlnaCast`, a dependency-free UPnP-AV control point (SSDP + SOAP over `java.net`), and its 14 parser tests. |

## Working on a patch

Edit the files inside `light-sdk/` directly — the applied state is the normal
state — then fold the result back into its patch:

```sh
scripts/sdk-patches.sh regen spike-dlna-cast.patch
```

`check` tells you which patch a stray submodule edit belongs to (everything
`ok` except the one you touched). A patch must touch only files no other patch
touches; that is what keeps `apply` order-independent and `regen` safe.

## Moving the submodule pin

```sh
scripts/sdk-patches.sh revert          # pristine again
git -C light-sdk checkout <new-sha>
./gradlew :tool:assembleRelease        # auto-applies; fails loudly on drift
```

A patch that no longer applies is rebased by hand: apply what survives, re-make
the rest against the new sources, `regen`. Never resolve drift by weakening
what the patch does — [no silent fallbacks](../tool/docs/ARCHITECTURE.md).
