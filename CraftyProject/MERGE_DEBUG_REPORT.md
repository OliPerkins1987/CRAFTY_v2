# Merge Debugging Report — `4a35a1d` (upstream/master into local feature branch)

Date: 2026-08-28
Scope: full review of the merge commit `4a35a1d` ("Merge remote-tracking branch 'upstream/master'"), its nine hand-resolved conflicts, the auto-merged files, and the current uncommitted working-tree changes.
Method: `git show --remerge-diff` on every conflicted file (comparing the committed resolution against what a clean automatic merge would have produced), semantic review of files modified on **both** sides (15 files), plus a compile and full test run of the working tree.

---

## Executive summary

The merge combined two long-diverged branches — the local price/costs/subsidies/twinned-AFTs work and upstream's institutional-modelling migration — and the resolution introduced **one compile break (already patched in your uncommitted working tree)** and **several semantic regressions in the competition core**. The most likely cause of "bizarre outputs" is **finding C1: cell utility is no longer refreshed when a cell changes owner**, which silently reverts the fix from local commit `6f07ce5` and makes newly claimed cells carry stale (typically zero or the previous owner's) utility through the rest of the tick's competition. Two further silent behaviour changes (C2, C3) and an expected-but-confusing RNG renumbering (C5) compound the picture. Separately, `crafty-plum` (the PLUM coupling entry point) does not compile at all, so any coupled run is being made from a stale build.

Current working tree status: `crafty-core` compiles; **175 tests run, 1 failure** (a naming-convention test, finding B1).

---

## A. The merge commit itself does not compile

Anyone building from `4a35a1d` (CI, a fresh clone, a jar rebuild) gets compile errors. Your uncommitted working-tree changes are the repair for this — they should be committed.

* **A1 — main sources:** `SubsidyUpdater.findSubsidyFile` referenced `ConfigLoader.config.land_taxes_subsidies_path`. Upstream deleted that field and added the key to `ConfigLoader.REMOVED_KEYS` (`ConfigLoader.java:109-120`), but the resolution kept the ours-side `SubsidyUpdater` untouched. Working-tree fix: new `prescribed_subsidies_path` field in `Config.java:63`, wired in `SubsidyUpdater.java:45`, plus a `config.yaml` entry. Sound fix; note the key is *not* in the legacy-alias table, so old configs using `land_taxes_subsidies_path` will now be silently ignored (it is a REMOVED key) — worth adding `land_taxes_subsidies_path → prescribed_subsidies_path` to `LEGACY_KEYS` instead if backwards compatibility is wanted.
* **A2 — test sources:** `CompetitivenessTest` at the merge commit still referenced `Aft.getCachedLandTax()` / `RegionalModelRunner.getServiceTax()` (8 references), which no longer exist in any main source. The working tree removes/comments these stubs.

## B. Test suite status (working tree)

* **B1 — one real failure:** `ConfigLoaderTest.allPublicConfigFieldsShouldUseCanonicalSnakeCase` rejects the ours-side field name **`use_price_explicit_givingUp`** (camelCase fragment). Upstream's convention test now guards `Config`. Fix: rename to `use_price_explicit_giving_up` and add the old spelling to `LEGACY_KEYS`.
* Everything else passes (174/175), including the merged competition, mask, production-cost and institution-adjacent tests in `crafty-core`.

## C. Semantic regressions from the conflict resolution (ranked by suspicion)

### C1 — Cell utility no longer updated on takeover (reverts local fix `6f07ce5`) — **prime suspect**

`Competitiveness.takeOverAcell` (`Competitiveness.java:284`) was resolved to upstream's shape:

* Ours (8b0ac8b): `c.setOwner(...); c.setCurrentUtility(utility(c, c.getOwner(), r));`
* Merged: `c.setOwner(newOwner);` — **no utility refresh** (and the method lost its `RegionalModelRunner` parameter).

Local commit `6f07ce5` added that refresh explicitly ("…ensures a cell's U is updated when it is taken over"). Only the twin-competition path kept it (`Competitiveness.java:264,277`).

Consequence per tick (`RegionalModelRunner.step()` order: utilities → twins → giveUp → unmanaged takeover → competition): a cell claimed in `takeOverUnmanageCells` keeps `currentUtility = 0` (the abandoned-owner value from `utilitytyForAll`), then enters `competition()` where its owner's `uO` reads as 0 — so almost any competitor with `uC > 0` immediately re-takes it. Cells flipped in earlier competition batches likewise carry the *previous* owner's utility until the next tick. Symptoms: excessive churn/flip-flopping, inflated `landUseChangeCounter`, unstable land-use maps — i.e., bizarre outputs.

Suggested fix: reinstate `c.setCurrentUtility(Competitiveness.utility(c, newOwner, r))` inside `takeOverAcell` (thread `r` through `applyCompetitionDecision`, which all callers already have).

### C2 — Subsidy/tax terms silently dropped from the default utility

Ours-side `utilityUseMarginal` was `Σ[(servicesTax + marginal) × competitiveness] + landTax`; the resolution took upstream's `Σ[marginal × competitiveness]` (`Competitiveness.java:82-91`). Cell-level taxes/subsidies now affect utility **only** when `use_cell_level_taxes: true` (via `utilityUseMarginalWithTaxes` and `ownerUtility`). Upstream's new institution engine writes policy effects into exactly those maps (`PolicyEffectApplier.java:48-49`), so with the flag off, **institutional policies and any file-based land taxes are computed and then ignored**. If your calibration relied on the ours-side behaviour, outputs shift silently. Decide which semantics you intend; if ours, restore the two additive terms; if upstream's, ensure `use_cell_level_taxes: true` in runs that use policies.

### C3 — `coupled_with_plum` shortcut re-introduced into `utility()` (reverts `35a3e61`), and inconsistently

Local commit `35a3e61` deliberately decoupled PLUM coupling from the price-only utility mode. The resolution restored upstream's shortcut: `if (use_price_only_utility || coupled_with_plum) return utilityUseOnlyPrice(...)` (`Competitiveness.java:73`), with `coupled_with_plum` frozen in a `static final` at class-load (`Competitiveness.java:65`). But `RegionalModelRunner.utilitytyForAll` (`RegionalModelRunner.java:167`) checks only the two explicit flags — so a coupled run that doesn't also set `use_price_only_utility` would compare price-ratio competitor utilities against marginal-utility owner utilities: incomparable numbers in the same inequality. Currently unreachable from a fresh build only because of D1 below, but a live trap. Suggested fix: remove `|| coupled_with_plum` (restore the `35a3e61` design) or mirror the condition in `utilitytyForAll`, and drop the static-final caching either way.

### C4 — Agent mutation feature removed wholesale

Upstream deleted `mutate_on_competition_win` / `mutation_interval` (now in `REMOVED_KEYS`), and the resolution dropped the ours-side `Aft(Aft other)` mutation copy-constructor. Configs still setting these keys get only a stdout note. If mutation-on-win matters to your experiments, it must be re-implemented; otherwise remove it from your scenario configs to avoid confusion.

### C5 — Deterministic RNG streams renumbered (expected divergence from pre-merge runs)

`DeterministicRandom.Process.CELL_SELECTION_TWIN_COMPETITION` moved 12 → 14; upstream claimed 12/13 for `COMPETITION_BATCH_ORDER` / `FORCED_MASK_COMPETITOR_PICK`. Also `twinnedCompetition` now seeds from `random_seed` instead of ours' `longSeedID`, and competition batching uses upstream's deterministic splitter. **Same seed ⇒ different draws than any pre-merge run.** Not a bug, but if "bizarre" means "doesn't match my reference run", part of the difference is definitionally this.

### C6 — Default constant drift

* `most_competitive_aft_probability` default: ours 0.8 → merged 0.85 (`Config.java`). The bundled `config.yaml` still says 0.8, but scenario configs omitting the key now behave differently.
* Price-explicit give-up sampling: ours' `land_abandonment_percentage` (default 0.03) was replaced by the shared `land_abandonment_fraction` (default 0.02) in `giveUp()` (`RegionalModelRunner.java:332`). The legacy alias maps the old key, so explicit configs are fine; defaults-only runs abandon slightly less land per tick.
* `step()` also swapped ours' active `takeOverMaskedCellByCategories()` for upstream's forced-mask mechanism (`LandMaskUpdater.applyForcedMasks`) — intentional per upstream, but verify masked-cell scenarios still behave as you expect.

## D. Modules that do not build (context, mostly pre-existing)

* **D1 — `crafty-plum` fails to compile.** `MainCoupling.java:42-59` references `COUPLED_WITH_PLUM`, `use_price_only_competition`, `plumOutPutPath`, `Output_path` — none exist in the merged `Config`. Note `use_price_only_competition` did not exist at your pre-merge head either (the `35a3e61` rename never reached this file), so the module was already stale; the merge removed three more fields. **Any "coupled" results are coming from an old jar, not this tree.**
* **D2 — `crafty-gui`** targets Java release 22; the JDK Maven is using here doesn't support it (environment/toolchain issue, not merge-caused).
* **D3 — `crafty-institution`** cannot resolve `net.sourceforge.jFuzzyLogic:jFuzzyLogic:1.2.1` from Maven Central; it presumably needs a manual/local-repo install per upstream's docs.

## E. Assessment of the uncommitted working-tree changes

All five changed files are coherent post-merge repairs and look correct:

1. `Config.java` / `config.yaml` — adds `prescribed_subsidies_path` (see A1 note about a legacy alias).
2. `SubsidyUpdater.java` — points at the new key; loader logic unchanged.
3. `ModelRunner.java` — moves `prepareInitialState()` and the baseline `regionalSupply()` out of the `initial_demand_supply_equilibrium` guard, fixing a first-tick NPE when calibration is disabled (a real merge-resolution regression: ours' `initialzeRun` had different priming). The unconditional `initialStatePrepared = true` is correct given the updaters now always run. One thing to verify: with equilibrium *enabled*, `InitialDSEquilibriumManager.demandEquilibrium()` re-runs some of the same priming — confirm nothing double-applies (capital adjustments, masks).
4. `CompetitivenessTest.java` — removes stubs for methods upstream deleted; makes tests compile.

**These should be committed** — as of `4a35a1d`, `master` is a broken commit.

## F. Recommended next steps (in order)

1. Commit the working-tree compile fixes (E).
2. Fix **C1** — restore `setCurrentUtility` on takeover. This is the highest-probability cause of bizarre dynamics.
3. Decide and fix **C2** (subsidies/taxes in default utility) — this changes calibration results silently.
4. Fix **B1** (rename `use_price_explicit_givingUp` + legacy alias) to get the suite green.
5. Remove or mirror the `coupled_with_plum` shortcut (**C3**), and repair or retire `crafty-plum`'s `MainCoupling` (**D1**).
6. To isolate remaining differences: check out the pre-merge head in a second worktree (`git worktree add ../pre-merge 8b0ac8b`), run the same scenario/seed on both builds, and diff outputs — differences beyond C1–C3 should be attributable to the RNG renumbering (C5) and defaults (C6).

---

### Appendix: conflict-resolution audit summary

| File | Resolution quality |
|---|---|
| `Competitiveness.java` | Merged both features, but lost `setCurrentUtility` on takeover (C1), lost tax terms in default utility (C2), re-added PLUM shortcut (C3) |
| `RegionalModelRunner.java` | Good combination of ours' price/twin logic with upstream's deterministic batching; masked-cell mechanism swapped to upstream's (C6) |
| `ModelRunner.java` | Correctly registers ours' updaters in upstream's initial-state framework; first-tick NPE when calibration off (fixed in working tree, E3) |
| `Config.java` | Clean union, but dropped `land_taxes_subsidies_path` while `SubsidyUpdater` still used it (A1); defaults drift (C6) |
| `ConfigLoader` (auto-merged) | Removed-keys list silently disables ours-era options (C4) |
| `Aft.java` | Mutation copy-constructor removed with upstream (C4) |
| `CapitalUpdater.java` | Correct union — ours' capital-type/suitability feature fully preserved |
| `DeterministicRandom.java` | Correct union; process codes renumbered (C5) |
| `config.yaml` | Clean union |
| `CompetitivenessTest.java` | Left referencing deleted API at the commit (A2); repaired in working tree |
