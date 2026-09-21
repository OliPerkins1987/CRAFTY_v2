# CRAFTY_v2 Codebase Review — Bugs, Refactoring Targets, Test Coverage

Date: 2026-09-01
Scope: full review of the working tree at commit `bd0caff` (all four modules: `crafty-core`, `crafty-gui`, `crafty-institution`, `crafty-plum`).
Method: manual review of the competition core, config layer, updaters, data loaders, output listeners, and the institution runtime; a full compile of the reactor; a run of the `crafty-core` test suite; and an empirical probe of the config loader (finding B1 was verified by executing the real `ConfigLoader` against a synthetic YAML).

Relationship to `MERGE_DEBUG_REPORT.md`: that report covered regressions from merge `4a35a1d`. Most of its findings are now fixed in the committed working tree — the compile break (A1/A2), the takeover utility refresh (C1, reinstated at `Competitiveness.takeOverAcell`), the `coupled_with_plum` shortcut (C3, removed), and the naming-test failure (B1; `crafty-core` now passes **175/175**). Still outstanding from that report: the C2 semantics question (see bug B7 below) and the broken `crafty-plum` module (B3).

---

## Executive summary

- **Build health:** `crafty-core` compiles and passes 175/175 tests. `crafty-gui` does not build on this machine (pinned to Java 22; everything else targets 17, local JDK is 21). `crafty-institution` cannot resolve `jFuzzyLogic:1.2.1` (not on Maven Central, no repository declared) so it is unbuildable from a clean environment. `crafty-plum` does not compile at all.
- **Most dangerous bug:** any unknown key in `config.yaml` — including the four *documented canonical keys* `chart_synchronisation`, `chart_synchronisation_gap`, `map_synchronisation`, `map_synchronisation_gap`, which are declared `static` and therefore invisible to SnakeYAML — causes the loader to **silently discard the entire configuration and run with all defaults** (verified empirically: `random_seed: 42` came back as `1`). A run can silently use the wrong seed, wrong paths, wrong mechanisms.
- **Testing:** effectively all confidence lives in `crafty-core` (and the newer institution tests, which currently can't run). The seed-selection/determinism machinery — the reproducibility backbone — has **zero active tests**: `SeedUpdaterTest` and `SelectorTest` exist but every `@Test` is commented out, and five other test classes are empty `// TODO` stubs.

---

## 1. Bugs

Ordered by severity. "Confirmed" = reproduced or directly demonstrable from code; "high confidence" = clear from code but not executed.

### Critical

**B1 — One unknown config key silently discards the whole config (confirmed by execution).**
[`ConfigLoader.loadConfig`](crafty-core/src/main/java/de/cesr/crafty/core/cli/ConfigLoader.java:239) wraps the typed SnakeYAML parse in a catch-all that returns `new Config()` on *any* exception, printing only `"Failed to load config. Using default config values."` to stdout. SnakeYAML throws for any property it can't bind — so a single unknown or misspelled key resets *every* setting (seed, paths, mechanism flags) and the run continues.
It is worse than a typo hazard: [`Config.java:124-127`](crafty-core/src/main/java/de/cesr/crafty/core/cli/Config.java:124) declares `chart_synchronisation`, `chart_synchronisation_gap`, `map_synchronisation`, `map_synchronisation_gap` as `static` fields. SnakeYAML excludes static fields from binding, so these four *supported, canonical* keys always throw — and the `LEGACY_KEYS` table actively normalises the old spellings (`chartSynchronisation`, `map_synchronization`, …) into them. **Any config that sets chart/map synchronisation, old or new spelling, silently loses everything.**
Probe result against the compiled classes:
```
yaml: random_seed: 42 / chart_synchronisation: false / neighbour_radius: 5
→ "Unable to find property 'chart_synchronisation' on class Config"
→ "Failed to load config. Using default config values."
→ loaded values: random_seed=1, neighbour_radius=2
```
Fix direction: make the four fields non-static (or route them through the raw map before typed parsing), and on parse failure either fail fast or strip only the offending key with a loud warning — never swap in a full default config silently.

**B2 — `crafty-gui` cannot compile: pinned to Java 22.**
[`crafty-gui/pom.xml`](crafty-gui/pom.xml) sets `<maven.compiler.release>22</maven.compiler.release>` while `crafty-core`/`-institution`/`-plum` target 17 and the installed JDK is 21. `mvn compile` fails at `crafty-gui` with "release version 22 not supported", which also skips `crafty-institution` and `crafty-plum` in the reactor. Any full-project build is broken on a standard JDK 17/21 toolchain.

**B3 — `crafty-plum` does not compile (stale references to renamed `Config` fields).**
`Crafty_To_Plum_Mapper`, `Crafty_To_Plum_Mapper2`, `Coupler`, `PlumCommodityMapping`, `PlumConnecter` still reference `config.plumOutPutPath`, `config.regionalization`, etc., which were renamed (`plum_output_path`, `regionalisation`) in the config migration. Any coupled CRAFTY–PLUM run is necessarily being made from a stale jar. (Known from the merge report; still true.)

**B4 — `crafty-institution` unbuildable from a clean environment.**
[`crafty-institution/pom.xml`](crafty-institution/pom.xml) depends on `net.sourceforge.jFuzzyLogic:jFuzzyLogic:1.2.1`, which is not on Maven Central, and the pom declares no repository for it. On this machine dependency resolution fails and **the institution test suite (12 classes) did not run at all**. Anyone cloning fresh cannot build or test the institutional model. Fix: add the hosting repository, or commit the jar via a file-based repo / `install-file` step documented in the README.

### High

**B5 — Map outputs are written every year; `map_output_frequency` is ignored (default config path).**
In [`Listener.initializeListExportingYearsMap`](crafty-core/src/main/java/de/cesr/crafty/core/output/Listener.java:318), the default branch (reached whenever `map_output_years` is not set) tests `Timestep.getTick() % map_output_frequency == 0 || Timestep.getTick() == 0` inside the year loop — but `tick` is constant (0) at initialization time, so the condition is true for **every** year and all years are added to `yearsMapExporting`. The loop variable `y` (used correctly by the `map_output_years: <int>` branch at line 308) is what this branch should use. Consequence: with the default `map_output_frequency: 10`, full cell-level CSV + PNG exports happen every single year — a large silent I/O and disk cost.

**B6 — `CustomLogger.fatal(String)` can be a silent no-op.**
[`CustomLogger.java:71-77`](crafty-core/src/main/java/de/cesr/crafty/core/cli/CustomLogger.java:71): the `String` overload logs and calls `System.exit(1)` *only* `if (isConfigAvailable())` — otherwise it does nothing at all (no log, no exit, no exception). The `(String, Throwable)` overload always exits. Callers are written on the assumption that `fatal` halts: e.g. [`AFTsLoader.getInitailPaths`](crafty-core/src/main/java/de/cesr/crafty/core/dataLoader/afts/AFTsLoader.java:149) calls `LOGGER.fatal(...)` and then `return null`, after which the caller immediately dereferences the result (`pFile.toFile()` at line 108) — so when logging isn't configured yet (early startup, tests), a missing parameter file surfaces as an unexplained NPE instead of the intended fatal message. Same pattern in `ProductionCostUpdater` (fatal → `return` leaves cost maps silently half-built if the process survives).

**B7 — Institution policy effects are computed and then ignored unless `use_cell_level_taxes: true` (merge-report C2, still open).**
[`PolicyEffectApplier`](crafty-institution/src/main/java/de/cesr/crafty/institution/runtime/PolicyEffectApplier.java:48) writes effects into `cell.getLandTax()` / `cell.getServicesTax()`, but the default utility path ([`Competitiveness.utility`](crafty-core/src/main/java/de/cesr/crafty/core/crafty/Competitiveness.java:67)) reads those maps only when `use_cell_level_taxes` is on (default `false`). With the flag off, institutional policies have zero effect on competition, with no warning. At minimum, warn (or fail) when institutions are configured but the flag is off.

**B8 — `ServicesTax` semantics are internally inconsistent (absent = 1.0 for readers, but writers accumulate from 0).**
The two utility readers treat a missing entry as a *multiplier of 1*: `getServicesTax().getOrDefault(serviceName, 1d) * gap + marginal` ([`Competitiveness.java:96`](crafty-core/src/main/java/de/cesr/crafty/core/crafty/Competitiveness.java:96), [`RegionalModelRunner.ownerUtility:188`](crafty-core/src/main/java/de/cesr/crafty/core/crafty/RegionalModelRunner.java:188)). But `PolicyEffectApplier` populates the map with `merge(name, adjustment, Double::sum)` starting from an *empty* map (cleared each period by `CellPolicyState.clear`). So a cell receiving a small positive adjustment ε ends up with multiplier ε instead of 1+ε — a discontinuity at zero where a tiny subsidy *collapses* the term from 1.0 to ~0. Three different defaults exist for the same map: 1.0 (utility), 0.0 (`DataCollector.java:77`), `NaN` (`PngGenerator.java:63`). One of the two conventions (absolute multiplier vs. additive delta) must be picked and applied everywhere; as written, fuzzy/LLM institution outputs feed a formula with different units than they were computed for.

**B9 — Division by a possibly-zero AFT cell count in `computeDistributionMean`.**
[`RegionalModelRunner.java:239-244`](crafty-core/src/main/java/de/cesr/crafty/core/crafty/RegionalModelRunner.java:239) divides each cell's utility by `hashAgentNbrRegions.get(region).get(ownerLabel)` — counts refreshed at the *end* of the previous step. Forced masks run earlier in the same step and can hand cells to an AFT that had zero cells last tick, producing `x/0 = Infinity` distribution means that then poison give-up thresholds and competition acceptance for that AFT for the rest of the tick. Same class of problem if a new AFT enters mid-run.

**B10 — Empty validation block in `AftsUpdater.updateAftCapitalAdjustmentValues`.**
[`AftsUpdater.java:105-110`](crafty-core/src/main/java/de/cesr/crafty/core/updaters/AftsUpdater.java:105):
```java
if (AFTsLoader.getActivateAFTsHash().keySet().contains(aftName)
        && CapitalUpdater.getCapitalsList().contains(capitalName)) {
}
AFTsLoader.getActivateAFTsHash().get(aftName).getCapital_adjustments().put(...);
```
The guard's body is empty and the `put` runs unconditionally — clearly the `put` was meant to be inside the `if`. An adjustments CSV naming an unknown/inactive AFT throws an NPE; an unknown capital name is silently accepted. Related: [`adjust_cell_capitals`](crafty-core/src/main/java/de/cesr/crafty/core/updaters/AftsUpdater.java:125) unboxes `c.getCapitals().get(cn)` without a null check (NPE inside a parallel stream if a cell lacks that capital).

### Medium

**B11 — Last-year parameter files silently ignored (off-by-one in path scanning).**
[`AFTsLoader.production_paths`](crafty-core/src/main/java/de/cesr/crafty/core/dataLoader/afts/AFTsLoader.java:165)/`behaviourPaths`: the config-directory production branch loops `i <= getEndtYear()` (line 173) but the scenario-mode production branch (line 197) and *both* behaviour branches (lines 245, 266) loop `i < getEndtYear()`. A production/behaviour file keyed to the final simulation year is found in one mode and silently skipped in the others (it then falls through to being registered as `default_`, i.e. applied from year one — worse than being ignored).

**B12 — `landEventCounter` table off-by-one; last year unlabeled.**
[`Listener.java:169-174`](crafty-core/src/main/java/de/cesr/crafty/core/output/Listener.java:169): the array is sized `[getSize()][2]` (all sibling tables use `getSize()+1` rows) and the year-label loop runs to `getSize()-1`, so the final data row has a null year label, and there is one fewer data row than simulated years. Also note [`updateLandUseEventCounter`](crafty-core/src/main/java/de/cesr/crafty/core/output/Listener.java:285) skips tick 0 entirely, so the counter accumulated during year 0 is folded into year 1's row.

**B13 — `updateAFTProduction`/`updateAFTBehaviour` crash unhelpfully on imperfect CSVs.**
[`AftsUpdater.java:156`](crafty-core/src/main/java/de/cesr/crafty/core/updaters/AftsUpdater.java:156): `m[i][Utils.indexof("Production", m[0])]` → `ArrayIndexOutOfBoundsException(-1)` if the `Production` column is missing. [`updateAFTBehaviour:201-207`](crafty-core/src/main/java/de/cesr/crafty/core/updaters/AftsUpdater.java:199): unguarded `reder.get("givingInDistributionMean").get(0)` NPEs if any expected key is absent (and `ReadAsaHash` itself may return `null`, which is never checked here).

**B14 — `GlobalCostData` unguarded parsing.**
[`GlobalCostData.java:29-36`](crafty-core/src/main/java/de/cesr/crafty/core/dataLoader/costs/GlobalCostData.java:29): raw `Double.parseDouble` (uncaught `NumberFormatException` on e.g. `"N/A"`) and no length check between the `Item` and `Cost` columns (`IndexOutOfBoundsException` on a ragged file). Inconsistent with the rest of the codebase, which uses the tolerant `Utils.sToD`.

**B15 — `Utils.sToD` turns any malformed value into `0.0` silently (systemic).**
[`Utils.java:32`](crafty-core/src/main/java/de/cesr/crafty/core/utils/general/Utils.java:32). Every loader funnels through this: a corrupted capital, demand, or cost cell becomes a silent zero that flows into productivity/utility. At minimum count-and-report conversions that fell back to zero per file. (Note the resulting asymmetry with B14: two different failure modes for the same kind of input error.)

**B16 — Inconsistent owner-life-counter on takeover of unmanaged cells.**
[`RegionalModelRunner.takeOverUnmanageCells:373-380`](crafty-core/src/main/java/de/cesr/crafty/core/crafty/RegionalModelRunner.java:373): `takeOverAcell` sets `OwnerLifeCounter = 1` for every takeover, then this caller immediately overwrites it to `0` for cells claimed from the unmanaged pool. Cells acquired via competition and via unmanaged-takeover therefore age differently by one year against `min_life_cycle`/`max_life_cycle` checks. Also note the seed size for unmanaged takeover reuses `land_abandonment_fraction` (line 357) — if intentional, it deserves a comment; it reads like a copy-paste of the give-up call.

**B17 — `Cell.productivity` vs `Cell.competitiveness` disagree when a service has no sensitivity rows.**
[`Cell.java:56-58`](crafty-core/src/main/java/de/cesr/crafty/core/crafty/Cell.java:56): with `separate_production_competitiveness` on, a missing/empty sensitivity map returns the bare productivity level; [`competitiveness`](crafty-core/src/main/java/de/cesr/crafty/core/crafty/Cell.java:82) returns `0.0` for the identical condition. One of these is wrong (or the difference deserves a comment). Both also unbox `getProductivityLevel().get(serviceName)` — NPE if a service is missing from an AFT's production file.

### Low

- **`AFTsLoader.hashAgentNbr(String)`** — duplicated condition `!contains("Abandoned") || !contains("Abandoned")` ([`AFTsLoader.java:457`](crafty-core/src/main/java/de/cesr/crafty/core/dataLoader/afts/AFTsLoader.java:457)); harmless today but clearly a typo for a second key.
- **`Competitiveness.landUsechange`** — inconsistent null-handling: line 186 guards `getDistributionMeanY() != null` but line 182 dereferences it unguarded; and `get(competitor.getLabel())` unboxes (NPE for an AFT absent from the map — currently protected only by the `putIfAbsent(a, 0.0)` loop covering *active* AFTs).
- **`CsvProcessors.processCSV`** header canonicalisation ([`CsvProcessors.java:138`](crafty-core/src/main/java/de/cesr/crafty/core/dataLoader/CsvProcessors.java:138)): `replace("Agent","FR")` runs before `replace("Agents","FR")`, so the latter is dead; substring replacement would also corrupt any header merely *containing* "AFT"/"Agent".
- **`SubsidyUpdater.findSubsidyFile`** — `p.getParent()` NPE if the configured path has no parent; and per the merge report, `land_taxes_subsidies_path` sits in `REMOVED_KEYS` rather than being aliased to `prescribed_subsidies_path`, so old configs lose their subsidies silently.
- **`Listener.outputfolderPath`** — dead assignment (`"Default simulation folder"` immediately overwritten, [`Listener.java:357`](crafty-core/src/main/java/de/cesr/crafty/core/output/Listener.java:357)).
- **`Config` javadoc** references a `Config.inialize()` post-load hook that does not exist and is never called.

---

## 2. Refactoring / simplification opportunities

**R1 — The global-static singleton architecture.** Nearly all model state is static: `AFTsLoader.hashAFTs`, `CellsLoader.hashCell/regions`, `ServicesUpdater`'s four maps, `Listener`'s table arrays, `Tracker.sankeydata`, `Timestep`'s clock, `SupplyUpdater.totalSupply`, `ModelRunner`'s component fields, `LandMaskUpdater`'s five maps. Consequences visible in the code today: tests need `ToyData.resetStaticState()` to hand-clear a dozen maps; several classes initialise *static* state in *instance* constructors (`ServicesUpdater`, `Tracker`, `ProductionCostUpdater`), so re-instantiation semantics are subtle; only one run per JVM is possible (acknowledged in `ModelRunner`'s javadoc). Introducing a single `ModelContext` object passed to updaters (the `RegionalModelRunner` parameter-threading pattern already used in `Competitiveness` shows the way) would be a large but mostly mechanical change and would dramatically improve testability.

**R2 — `AFTsLoader` (490 lines) does too much and duplicates itself.** `production_paths()` and `behaviourPaths()` are ~130 lines of near-identical path-resolution logic (differing in directory, filename pattern, and one loop bound — which is how bug B11 crept in); `getPath`/`getInitailPaths` duplicate the branch structure again. Extract one parameterised resolver. Also: the class `extends HashSet<Aft>` purely for legacy reasons (per its own javadoc) — drop the inheritance.

**R3 — Duplicated capital-exponent product loop.** [`Cell.productivity`](crafty-core/src/main/java/de/cesr/crafty/core/crafty/Cell.java:52) and [`Cell.competitiveness`](crafty-core/src/main/java/de/cesr/crafty/core/crafty/Cell.java:78) contain the same 12-line pow-product loop (one adds a suitability filter). Extract a helper; that is also the hot loop, so one implementation to optimise. The overload `calculateCurrentProductivity(String[] services)` is unused and subtly wrong (iterates `ServiceSet` size while indexing the argument array) — delete it.

**R4 — Two-phase competition pattern applied inconsistently.** `competition()` and `takeOverUnmanageCells()` carefully evaluate decisions against frozen state and apply them in deterministic encounter order, but [`twinnedCompetition`](crafty-core/src/main/java/de/cesr/crafty/core/crafty/RegionalModelRunner.java:454) mutates ownership directly inside `parallelStream().forEach`. Per-cell decisions are independent so this is (currently) safe, but it breaks the codebase's own determinism idiom, and the before/after supply-delta bookkeeping is copy-pasted between `competition()` (lines 414-427) and `twinnedCompetition` (lines 455-470). Extract a shared "evaluate batch → apply → refresh supply/marginal" helper and use it for both.

**R5 — Config loading.** Beyond bug B1: `loadConfig` round-trips the YAML through `dump()`/`load()` to apply key normalisation — parsing twice; consider binding from the normalised `Map` directly. Validation is piecemeal (`twinned_competition_rate` is range-checked; `participating_cell_fraction`, `land_abandonment_fraction`, `most_competitive_aft_probability`, `neighbour_priority_probability` are not). A single `validate()` over all fractions/probabilities would be ~15 lines.

**R6 — Dead code and commented-out blocks.** Notable examples worth deleting (git history preserves them):
- `CsvProcessors`: two full commented-out method bodies (~60 lines, lines 167-183, 237-264), plus `if (c != null)` immediately after `new Cell(...)` in `createCells`.
- `InitialDSEquilibriumManager`: the old `updateBaselineIfsupplyIsNull` is an ~80-line comment block **and** its rewritten replacement (lines 216-364, including the `CellAftCandidate` record and `hasZeroSupply`) is *also* dead — the only call site is commented out at line 33. Either wire it back in or remove ~230 lines.
- `RegionalModelRunner`: commented profiler section (lines 135-137), debug prints; `Competitiveness`: commented `CellsUpdater.decesionsNewOwner` lines.
- `Timestep.getSize()/setSize` vs the unused `size` semantics; `AbstractCell`'s commented static `size` accessors.

**R7 — `Tracker.sankyData` is decompiled-style code.** [`Tracker.java:117-146`](crafty-core/src/main/java/de/cesr/crafty/core/output/Tracker.java:117) uses `var1`/`var2`/`var6` iterator loops and a for-loop with its assignment hidden in the update clause (`cs[0][k++] = oldOwner` at line 130). Rewrite with for-each; it is ~30 lines.

**R8 — Error-handling conventions.** Three competing styles coexist: `LOGGER.fatal` + `return null` (loaders), `printStackTrace()` + continue (37 sites across the modules), and `return null` from `ReadAsaHash` with call sites that never null-check (`ReadAsaHashDouble`, `initializeAftList`, `updateAFTBehaviour` — each becomes an NPE two lines later). Deciding on fail-fast for load-time errors (throw a dedicated `DataLoadException`) would delete a lot of defensive noise and make failures diagnosable. This pairs with fixing B6.

**R9 — Naming and typo debt.** The misspellings are pervasive enough to hurt grep-ability and onboarding: `utilitytyForAll`, `getEndtYear`, `serivces` (a *package* name), `initialzeRun`, `getInitailPaths`, `updateRestrections`, `cellsForecedToChange`, `capitalsAdjusment`, `listner`, `writOutPutMap`, `updateCSVFilesWolrd`, `landChengePath`, `Rigion`, `intializeForTaxesUse`, `initialTotalDSEquilibriumListrner`, `InversTRANSPOSE`. Also `AbstractModelRunner.setup(AbstractModelRunner)` ignores its parameter (passes `this` instead). A staged, IDE-driven rename (public API last) is low-risk and high-value; the `serivces` package is the most visible.

**R10 — `ServicesTax`/`LandTax`/`CapitalsAdjusment` access.** Per bug B8, the default values (1.0 / 0.0 / NaN / 0.0 depending on caller) are scattered. Give `AbstractCell` semantic accessors (e.g. `serviceTaxMultiplier(String)` with the documented default) so the convention lives in one place.

**R11 — One-off analysis code inside production modules.** `TempCraftyFinlandAnalizer` (name says it all) and `CraftyDataUpscaler`/`SplitByRegions` live in `crafty-core/utils/analysis`; `LoessStandalone`, `SmoothMockField`, `NestedVoronoiDiskChart` in `crafty-gui`. Moving genuinely experimental material to a separate non-shipped module (or deleting `Temp*`) shrinks the surface that must be kept compiling.

**R12 — GUI/core duplication.** `DirectoryWatcher` exists in both `crafty-core/utils/file` and `crafty-plum/couplingUtils`. `crafty-plum` also carries four pseudo-inverse implementations (`InversTRANSPOSE`, `LeftInverse`, `Pseudo_inverse`, plus mapper variants `Crafty_To_Plum_Mapper`/`Mapper2`) that look like iterations of the same idea — worth consolidating when that module is revived (see B3).

---

## 3. Under-tested areas

### Module-level picture

| Module | Main classes | Test classes | Status |
|---|---|---|---|
| crafty-core | 82 | 40 (31 actually run) | 175/175 pass; but see gaps below |
| crafty-institution | 75 | 12 | **cannot run** here (B4: unresolvable dependency) |
| crafty-gui | 66 | 0 | no tests at all |
| crafty-plum | 18 | 0 | no tests; doesn't compile (B3) |

### Dead test files in crafty-core (silently not running)

These exist on disk but contribute zero coverage — worth knowing before trusting the green build:

- **All `@Test`s commented out:** [`SeedUpdaterTest`](crafty-core/src/test/java/de/cesr/crafty/core/updaters/SeedUpdaterTest.java) (5 tests), [`SelectorTest`](crafty-core/src/test/java/de/cesr/crafty/core/utils/general/SelectorTest.java) (7 tests), [`AftsUpdaterTest`](crafty-core/src/test/java/de/cesr/crafty/core/updaters/AftsUpdaterTest.java) (1 test).
- **Empty `// TODO` stubs:** `CellsLoaderTest`, `GisLoaderTest`, `ServiceDemandLoaderTest`, `ServiceSetTest`, `ServiceWeightLoaderTest`.

The first group is the most alarming: `SeedUpdater` + `Selector` are the deterministic cell-sampling machinery that the whole reproducibility story (`DeterministicRandom`, stable ordering, two-phase competition) rests on, and they currently have **no active tests**. Whatever caused these to be commented out (likely the static-state coupling that `ToyData.resetStaticState` fights) should be fixed and the tests restored.

### Behaviour with no test coverage at all

1. **Twinned-AFT competition** — no test in the repo references twins (`twinCompetition`, `use_twinned_cost`, twin metadata parsing/validation in `AFTsLoader.initializeAftList`). This is recent, config-gated, and touches the competition core.
2. **Price-explicit give-up and subsidies** — `Cell.priceExplicitGiveUp`, `SubsidyUpdater` path resolution, `Receives_subsidy` parsing. Only config-level validation is tested.
3. **Config-loading failure modes** — nothing tests unknown keys, legacy-alias collisions, or the static-field keys; bug B1 would have been caught by a single test asserting that a config with one extra key retains its other values.
4. **`Listener`/`Tracker` output correctness** — the existing `ListenerTest`/`TrackerTest` are small (3 tests each); nothing pins down table dimensions or year alignment, which is why B5 and B12 (both off-by-one/scheduling bugs in this file) survived. A test that runs 3 ticks and asserts the exported CSV rows would cover all of it.
5. **`RegionalModelRunner` internals** — 6 tests exist, but `computeDistributionMean` (division semantics, zero-count AFTs → B9), `computeMarginal` flag combinations (`penalise_oversupply` × `averaged_residual_demand_per_cell` × `use_relative_marginal_utility` = 8 paths), and `takeOverUnmanageCells` (life-counter semantics → B16) are untested.
6. **`CsvProcessors` streaming path** — `processCSV`'s parallel row processing, the header canonicalisation (`AFT`/`Agent` → `FR`), and behaviour on ragged/quoted rows. The class-level javadoc admits the streaming parser is not RFC-compliant; no test documents where its limits are.
7. **`AFTsLoader` path resolution** — the year/scenario/default fallback matrix (where off-by-one B11 lives) has no direct tests; `AFTsLoaderTest`'s 4 tests cover metadata parsing only.
8. **Institution ↔ core integration** — `PolicyEffectApplier` is well unit-tested, but nothing exercises the full chain "institution writes taxes → `use_cell_level_taxes` utility reads them" (which is exactly where B7/B8 sit). Also `InstitutionOrchestratorTest` has only 2 tests for the central coordinator, and the LLM clients (`GptLlmClient`, `KitLlmClient`, `LlmClientFactory`) have no tests (the prompt builder and parsers do).
9. **End-to-end determinism** — given how much machinery exists for reproducibility, there is no "golden run" test: a tiny toy landscape, fixed seed, N ticks, assert the exact land-use trajectory/output CSVs. This single test is the cheapest defence against the class of silent semantic regressions the merge produced (C1/C2), and against future refactors of the competition core.

### Suggested priorities

1. **Config-loader tests for B1** (cheap, catches the worst live bug class).
2. **Golden-run determinism test** (small fixture; protects everything at once).
3. **Restore `SeedUpdaterTest`/`SelectorTest`** (fix the static-state obstacle rather than deleting the tests).
4. **Listener output-shape tests** (pins B5/B12 fixes).
5. **Twin competition + unmanaged-takeover unit tests** (recent, config-gated logic in the core loop).
6. **Un-block the institution module build** (B4) so its existing 12 test classes actually run in CI.

---

## Positive observations (for calibration)

Recent work is visibly raising the bar: `DeterministicRandom` is clean, well-documented, and stateless; the two-phase evaluate/apply competition refactor is careful about determinism; `LandMaskUpdater` reads like a proper rewrite; and the `crafty-institution` module (records, `Objects.requireNonNull`, exhaustive-switch enums, real unit tests, zero `printStackTrace`) is the quality target the older core code should converge toward. The bugs above are concentrated in the older, static-heavy layers — which is also the strongest argument for refactorings R1/R8.
