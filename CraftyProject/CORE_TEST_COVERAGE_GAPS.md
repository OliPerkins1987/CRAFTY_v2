# CRAFTY core — under-tested / un-tested aspects

## Context

The `crafty-core` module has a test suite that *looks* substantial in the file
listing (35 test files, ~192 `@Test` annotations) but a large fraction of that is
either commented out, stubbed, or aimed at loaders and utilities rather than the
model's decision logic. Patches 19–22 currently under review ("Phase 0") begin to
close the biggest holes, but even with all four applied the core land-use-change
engine remains largely unverified at the unit level.

This document is a **report only** — no code or test changes are proposed here.
Scope: `de.cesr.crafty.core.*` excluding `crafty-institution`, `crafty-plum`,
`crafty-gui`, and the `utils/graphics` / `utils/non_java_code_controller` output
tooling.

---

## Headline findings

| # | Area | State |
|---|------|-------|
| 1 | **Phantom coverage** — whole test files commented out | `SeedUpdaterTest` (10 methods → 0), `SelectorTest` (14 → 0), `AftsUpdaterTest` (1 → 0). Patch 21 restores the first two. |
| 2 | **The land-use-change engine is not unit-tested** | `RegionalModelRunner.giveUp/takeOverUnmanageCells/competition/twinnedCompetition/runCompetitionInBatches` — none have a direct test. |
| 3 | **Twin competition has zero tests** | `Competitiveness.twinCompetition` / `evaluateTwinCompetition` were just refactored (commit 44c399b) and are entirely uncovered. |
| 4 | **Behaviour-based give-in is half-tested** | `landUsechangeNormalisedUtility`, `effectiveGiveIn`, `giveInThreshold` (categorised branch), `CellBehaviour.social_influence` — untested. |
| 5 | **`Cell.giveUp` / `priceExplicitGiveUp` abandonment logic** | Only the null-owner guard is tested; the deterministic utility+probability decision is commented out. |
| 6 | **`InitialDSEquilibriumManager`** | `updateBaselineIfsupplyIsNull` (~130 lines) completely untested; full `demandEquilibrium` flow untested. |
| 7 | **Small scheduled updaters** | `SupplyUpdater`, `SubsidyUpdater`, `RegionsModelRunnerUpdater`, `Timestep`, `CellsUpdater` — no test files. |
| 8 | **No end-to-end / regression coverage** | Only addressed by patch 19 (`GoldenRunTest`), and only for one config mode. |
| 9 | **Config-flag combinatorial surface** | ~15 behaviour-switching flags; only `marginal` + `normalised-price` utility paths are exercised. |

---

## 1. Phantom coverage — tests that exist only as comments

These files contribute to the "192 `@Test`" count but run nothing:

- `updaters/SeedUpdaterTest.java` — all 10 methods `//`-commented. `SeedUpdater`
  decides **which cells act each year**; if that is not reproducible, nothing
  downstream can be. **Patch 21 rewrites and restores 7 tests.**
- `utils/general/SelectorTest.java` — all 14 methods commented. `Selector` picks
  the acting subset. **Patch 21 restores 8 tests.**
- `updaters/AftsUpdaterTest.java` — the single test method is commented out; the
  file is effectively empty. **Not addressed by any patch.** `AftsUpdater` (217
  lines) applies time-varying production, sensitivity, behaviour and capital-
  adjustment parameters each year.
- 7-line `// TODO` stubs: `dataLoader/serivces/ServiceSetTest`,
  `ServiceDemandLoaderTest`, `ServiceWeightLoaderTest`, `dataLoader/land/GisLoaderTest`.

Partially commented (real tests plus dead ones that hide intent):
`crafty/CompetitivenessTest.java` (12 commented, incl.
`mostCompetitiveAgent_picksAgentWithHighestUtility`,
`competition_takesOver_whenOwnerExists_andUtilityDifferenceExceedsThreshold`,
`giveInThreshold_usesCategorisedMeanSd`), `crafty/CellTest.java`
(`giveUp_givesUpDeterministically_whenBelowThresholdAndProbabilityOne`).

---

## 2. Core classes with **no test file at all**

`crafty/`: `Region`, `Service`, `AbstractAft`, `AbstractCell`, `AftCategory`,
`ManagerTypes`
`modelRunner/`: `AbstractModelRunner`, `ModelState`
`updaters/`: `AbstractUpdater`, `CellsUpdater` (dead — `step()` body commented
out), `RegionsModelRunnerUpdater`, `SubsidyUpdater`, `SupplyUpdater`, `Timestep`

`Service` and `AftCategory` carry real logic (calibration factor, intensity
levels used in give-in) and are only touched incidentally through other tests.
`Timestep.step()` writes the per-year "done" file and advances both `currentYear`
and `tick` — the off-by-one class of bug that patches 09/10/18 already had to fix
lives exactly here and has no guard.

---

## 3. The land-use-change engine — [RegionalModelRunner](crafty-core/src/main/java/de/cesr/crafty/core/crafty/RegionalModelRunner.java)

`RegionalModelRunnerTest` (6 tests) covers only the *input-side* helpers:
constructor, `regionalSupply`, `utilitytyForAll` (marginal branch only),
`computeMaxMinUtility`, `computeMarginal`, `computeDistributionMean`.

**Untested** — the entire decision half of `step()` ([RegionalModelRunner.java:103](crafty-core/src/main/java/de/cesr/crafty/core/crafty/RegionalModelRunner.java:103)):

- `twinnedCompetition()` (line 475) — new code, see §4
- `giveUp()` (line 331) — both `use_price_explicit_giving_up` and
  `use_abandonment_threshold` branches, and the seed-is-null guard
- `takeOverUnmanageCells()` (line 355) — the two-phase evaluate-then-apply, the
  `unmanaged_cell_takeover_fraction` gate, and removal from the unmanaged pool
- `competition()` (line 389) + `runCompetitionInBatches()` (line 430) — the
  shared helper extracted in cleanup 2.4 (commit c2a6a07 / patch 18-cleanup-2.4),
  including the per-batch `takesPart` filter and the
  `applyProductivityChangeToRegionalSupply` + `computeMarginal` refresh between
  batches
- `cellsWhereOwnerExceededMaxLifeCycle()` (line 512) — the `max_life_cycle`
  forced-recompetition path (patches 16/18 touched this counter)
- `intializeForTaxesUse()` / `ownerUtility()` — the `use_cell_level_taxes`
  utility path at region level (`utilitytyForAll` else-branch, line 183)
- the `applyForcedMasks(...) > 0` re-computation loop (lines 120–127)
- `initialDSEquilibriumFactorCalculation()` — stubbed out in `ModelRunnerTest`,
  so the real version never runs under test

Patch 19's `GoldenRunTest` exercises this path end-to-end for the first time, but
only in **plain marginal-utility mode** (no taxes, no prices, no twins, no
behaviour model) and asserts against a single golden signature.

---

## 4. Twin competition — no coverage

Commit 44c399b ("Refactoring of competition code to avoid duplication with
twinned competition mode") added:

- `Competitiveness.twinCompetition` ([Competitiveness.java:251](crafty-core/src/main/java/de/cesr/crafty/core/crafty/Competitiveness.java:251))
- `Competitiveness.evaluateTwinCompetition` (line 260) — the "straight $ decision
  net of switching cost" including `use_twinned_cost` / `getTwinCost()`
- `RegionalModelRunner.twinnedCompetition` — the `twinned_competition_rate` seed
  filter and the determinism fix described in the comment at lines 494–506

`CompetitivenessTest` contains **no reference to "twin"**. A change here is
currently caught by nothing (the golden-run test disables `use_twinned_afts`).

---

## 5. Competition decision rules — [Competitiveness](crafty-core/src/main/java/de/cesr/crafty/core/crafty/Competitiveness.java)

`CompetitivenessTest` (21 active) covers the price/normalised-price utility
formulas and the `owner == null` takeover path well. Gaps:

- `landUsechange` (line 176) **owner-exists branch** — the
  `uC - uO > distributionMean * giveInThreshold` comparison. Test commented out.
- `landUsechangeNormalisedUtility` (line 193) — the behaviour/categorisation
  give-in mode (`useCategorisationGivIn && behaviourUsed`). Not tested.
- `effectiveGiveIn` (line 235) — same-category / same-intensity routing to
  `behaviour.give_In` vs `giveInThreshold`. Not tested.
- `giveInThreshold` (line 294) — the deterministic Gaussian draw and the
  `AftCategorised` mean/SD branch. Test commented out.
- `makeCompetition` (line 153) — mask-restriction lookups are partly covered
  (one case in `CellTest`), but the `owner != null && ownerLifeCounter <
  min_life_cycle` branch (line 170) is not.
- `mostCompetitiveAgent` (line 356) — only the empty-set case; the EPS tie
  clustering + sort-by-label determinism is commented out.
- `takeOverAcell` (line 276) — the same-tick `setCurrentUtility` refresh (one of
  the two original merge regressions) is only indirectly covered by patch 19.

---

## 6. Cell-level events — [Cell](crafty-core/src/main/java/de/cesr/crafty/core/crafty/Cell.java)

`CellTest` (12 active) covers `productivity` / `competitiveness` / `capitalProduct`
and `calculateCurrentProductivity` thoroughly. Gaps:

- `giveUp` ([Cell.java:134](crafty-core/src/main/java/de/cesr/crafty/core/crafty/Cell.java:134)) —
  only `giveUp_doesNothing_whenOwnerNull` runs. The actual decision
  (`utility < averageUtility * thresholdFactor` **and** probability test), the
  unmanaged-pool insertion, the counter increments, and the `Tracker.sankeydata`
  write are untested. The deterministic test is commented out.
- `priceExplicitGiveUp` (line 179) — `utility + subsidy < 0` path, entirely
  untested.
- `productionCost` (line 108) — spatial vs global cost modes
  (`isSpatialProductionCosts`), untested at `Cell` level.

`AbstractCell` — the `OwnerLifeCounter` lifecycle (init 1, increment, reset to 0
on takeover/give-up, reset to 1 on `takeOverAcell`) is a recurring bug site
(patches 12/16/18) with no dedicated test.

---

## 7. Behaviour model — [CellBehaviour](crafty-core/src/main/java/de/cesr/crafty/core/crafty/CellBehaviour.java)

`CellBehaviourTest` (4) covers `give_In` in the same-category path, the logistic
bounds, and `toString`. Untested:

- `social_influence` (line 97) — the neighbour-fraction / critical-mass logic,
  including the intensity-direction branches
- `fractionOfNeighbors` (line 117) — depends on
  `CellsSubSets.detectExtendedNeighboringAFTs`
- `Attitude_influence` (line 129)
- `parameterValue` (line 68) — the name-normalisation switch and the
  unknown-parameter exception

`CellBehaviourUpdaterTest` (5) is reasonable — it covers the "retain previous
parameters when a later year has no file" case.

---

## 8. Initial demand–supply equilibrium — [InitialDSEquilibriumManager](crafty-core/src/main/java/de/cesr/crafty/core/modelRunner/InitialDSEquilibriumManager.java)

`ModelRunnerTest` (6) covers `RegionalDemandEquilibrium_calculation` (via a stub
runner), `initialTotalDSEquilibriumListrner`, and both `validateInitialEquilibrium`
outcomes. Untested:

- `demandEquilibrium()` (line 31) end-to-end — the demand rescaling by
  `calibration_factor` across all years/regions, then re-validation, then
  `aggregateRegionalToWorldServiceDemand`
- `updateBaselineIfsupplyIsNull()` (line 216) — ~130 lines, the entire
  "allocate 0.1% of best cells to producer AFTs to avoid zero baseline supply"
  routine. Not called from any test. (A commented-out earlier copy sits directly
  above it, lines 130–209 — dead code that also inflates the file.)
- `RegionalModelRunner.initialDSEquilibriumFactorCalculation()` — the real one;
  `ModelRunnerTest` overrides it with a stub.

---

## 9. Scheduled updaters — coverage map

| Updater | LOC | Active tests | Assessment |
|---|---|---|---|
| `ProductionCostUpdater` | 227 | 13 | Good |
| `LandMaskUpdater` | 405 | 8 | Good on forced-mask target selection; the `applyForcedMasks → full recompute` interaction with `RegionalModelRunner.step` is not covered |
| `ServicesUpdater` | 107 | 4 | Reasonable; one test documents an NPE risk rather than guarding it |
| `CellBehaviourUpdater` | 139 | 5 | Reasonable |
| `CapitalUpdater` | 151 | 4 | Constructor + `isSuitability` only; `step()` (CSV → cell capitals) and `loadCapitalTypes` fatal paths untested |
| `Capital_Degradation_Updater` | 122 | 2 | Path discovery only; degradation application untested |
| `FlagUpdater` | 123 | 4 | Reasonable |
| `AftsUpdater` | 217 | **0** | Only test commented out — see §1 |
| `SeedUpdater` | 158 | **0** (7 after patch 21) | See §1 |
| `SupplyUpdater` | 42 | 0 | World-supply aggregation + region ordering untested |
| `SubsidyUpdater` | 83 | 0 | Path resolution + `use_price_explicit_giving_up` gating untested |
| `RegionsModelRunnerUpdater` | 83 | 0 | Per-region runner fan-out, `setStep` seam, counter refresh untested |
| `Timestep` | 96 | 0 | `step()` year/tick advance + done-file write untested |
| `CellsUpdater` | 32 | 0 | Dead (`step()` empty) — candidate for deletion, not a test |

Note on `AftsUpdater.adjust_cell_capitals` (line 123): it multiplies each cell
capital by `(1 + adjustment)` **on every call**, so repeated invocation compounds.
Whether that is intended is undocumented and unguarded.

---

## 10. Config-flag combinatorial surface

`Competitiveness.utility()` alone branches four ways
(`use_explicit_price_utility`, `use_price_only_utility`, `use_cell_level_taxes`,
else marginal). Add the flags that change the decision cycle:
`use_normalised_price_competition`, `use_twinned_afts`, `use_twinned_cost`,
`use_abandonment_threshold` vs `use_price_explicit_giving_up`,
`use_neighbour_priority`, `separate_production_competitiveness`,
`use_relative_marginal_utility`, `averaged_residual_demand_per_cell`,
`penalise_oversupply` (per service), `useCategorisationGivIn` + `behaviourUsed`.

`ConfigLoaderTest` (13, +6 from patch 20) covers *loading and validation* of the
config well. It does **not** cover model *behaviour* under each flag. There is no
matrix test and no per-flag golden run. A change that, say, makes
`use_relative_marginal_utility` a no-op would pass the whole suite.

---

## How patches 19–22 change the picture

- **19 `GoldenRunTest`** — first end-to-end determinism + regression pin over the
  `RegionalModelRunner` decision cycle. Checks reproducibility, cell-insertion-
  order independence, non-vacuousness, and a golden landscape signature. **Only
  marginal-utility mode.**
- **20** — config loader failure modes (bug B1: one bad key silently reset the
  whole config). 6 tests. Solid.
- **21** — restores `SeedUpdaterTest` (7) + `SelectorTest` (8), rewritten against
  current APIs. Closes the single largest phantom-coverage hole and the
  determinism floor patch 19 stands on.
- **22** — `Listener` output-table shape (B12 land-use table off-by-one; B5 map
  frequency). 6 pass + 1 `@Disabled` documenting still-open B5.

**Still uncovered after all four:** twin competition (§4), behaviour-based
give-in (§4/§5/§7), `Cell.giveUp` / `priceExplicitGiveUp` (§6),
`AftsUpdater` (§1/§9), `InitialDSEquilibriumManager.updateBaselineIfsupplyIsNull`
(§8), the price/tax utility cycles end-to-end (§3/§10), and the small updaters
`SupplyUpdater` / `SubsidyUpdater` / `RegionsModelRunnerUpdater` / `Timestep` (§9).

---

## Prioritised recommendations (for a follow-up, not this task)

1. **Restore `AftsUpdaterTest`** the way patch 21 restored the seed tests —
   `updateAFTProduction`, `updateSensitivty`, `updateAFTBehaviour`, and the
   compounding `adjust_cell_capitals`.
2. **Add twin-competition tests** to `CompetitivenessTest` —
   `evaluateTwinCompetition` with/without `use_twinned_cost`, and the
   `twinnedCompetition` seed filter.
3. **Un-comment and fix the owner-exists / categorised give-in tests** already
   sketched in `CompetitivenessTest` and `CellTest` (they were written, then
   left behind by API drift — same story as patch 21).
4. **Direct tests for `RegionalModelRunner.giveUp` / `takeOverUnmanageCells` /
   `competition`** using the fixture style patch 19 already builds — the
   infrastructure now exists.
5. **Second and third golden runs** for the price-utility and cell-level-tax
   modes, reusing `GoldenRunTest`'s fixture.
6. **Small-updater tests**: `Timestep.step` (year/tick/done-file),
   `SupplyUpdater` aggregation order, `SubsidyUpdater` gating.
7. **`InitialDSEquilibriumManager.demandEquilibrium` end-to-end** and at least a
   smoke test of `updateBaselineIfsupplyIsNull`.

---

## Verification

This is a documentation deliverable. To re-check the claims:

- Cross-check the "no test file" and "commented out" claims:
  ```bash
  cd CraftyProject/crafty-core/src/test/java/de/cesr/crafty/core
  grep -rc '^\s*@Test' . | grep ':0'          # stub / all-commented files
  grep -rc '^\s*//.*@Test' . | grep -v ':0'   # commented-out test methods
  ```
- Confirm patch status: patches 19–22 are in `CraftyProject/patches/` and not yet
  applied (`git log` shows the last commit as 44c399b, the twin refactor).
- No build required; `crafty-core` is the only locally-buildable module
  (`mvn -f crafty-core/pom.xml test`) if a coverage run is wanted later.
