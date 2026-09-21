# crafty-core Cleanup Plan

Date: 2026-09-01
Scope: **crafty-core only** (crafty-gui, crafty-institution, crafty-plum explicitly out of scope).
Source findings: `CODEBASE_REVIEW_REPORT.md` (bug IDs B*, refactor IDs R* referenced below).
Workflow: plan → your approval → diffs delivered in `CraftyProject/patches/` (git-root-relative paths, `CraftyProject/`-prefixed). No source file is touched by me directly.

---

## Guiding principle

**Build the safety net first, then fix bugs, then refactor, then rename.** Every phase ends with `mvn test -pl crafty-core` green, and (from Phase 0 onward) the golden-run test proving the simulation trajectory is unchanged — except where a bug fix is *supposed* to change it, in which case the golden file is regenerated deliberately and the change is called out in the patch notes.

---

## Phase 0 — Safety net (no production-code changes)

Goal: make it possible to refactor without fear.

| # | Task | Notes |
|---|------|-------|
| 0.1 | **Golden-run determinism test.** A small toy landscape fixture (~20×20 cells, 2–3 AFTs, 2 services), fixed seed, ~10 ticks; assert the exact ownership grid at end plus a hash of the key output CSVs. | This is the single test that would have caught the merge regressions (C1/C2). Reuses/extends the existing `ToyData` helper. |
| 0.2 | **Config-loader failure-mode tests.** Unknown key must not reset other values; legacy aliases; the `chart_synchronisation`/`map_synchronisation` keys. | Written first as *failing* tests documenting bug B1; Phase 1 makes them pass. |
| 0.3 | **Restore `SeedUpdaterTest` (5 tests) and `SelectorTest` (7 tests).** Uncomment, fix whatever static-state coupling caused them to be disabled, using `ToyData.resetStaticState`. | If any test documents genuinely dead behaviour, delete it with a note instead. |
| 0.4 | **Listener output-shape test.** Run 3 ticks, assert table dimensions and year labels of the exported CSVs. | Written as failing where B5/B12 apply; pinned down before the fixes. |

Deliverable: `patches/phase0-safety-net.diff` (test sources + fixtures only).

---

## Phase 1 — Bug fixes (behaviour-changing, each isolated and testable)

Ordered so each diff is small and independently applicable. Fixes marked ⚠ change simulation output by design.

| # | Bug | Fix | Output-affecting? |
|---|-----|-----|-------------------|
| 1.1 | **B1** config silently reset | Make the 4 sync fields non-static; on typed-parse failure, report the offending key and **fail fast** rather than substituting defaults. Unknown keys → error listing the key (with did-you-mean from LEGACY_KEYS). | No (failing runs now fail loudly) |
| 1.2 | **B6** `fatal()` silent no-op | `fatal(String)` always logs (stderr fallback) and always exits, matching the `(String,Throwable)` overload. Audit the ~10 call sites that `return null` after `fatal` — they can then drop the unreachable fallback. | No |
| 1.3 | **B5** maps exported every year | Use the loop counter `y` in the frequency branch of `initializeListExportingYearsMap`, mirroring the `map_output_years:int` branch. | Output *files* only |
| 1.4 | **B9** ÷0 in `computeDistributionMean` | Compute counts from the cells being iterated (single pass: sum + count per AFT, divide once at the end) instead of dividing per-cell by last tick's stale `hashAgentNbrRegions`. | ⚠ marginal (only when counts were stale) |
| 1.5 | **B10** empty `if` in `AftsUpdater.updateAftCapitalAdjustmentValues` | Move the `put` inside the guard; warn on unknown AFT/capital names. Null-guard `adjust_cell_capitals` capital lookup. | Only for previously-crashing/miswired inputs |
| 1.6 | **B11** last-year parameter files skipped | Align all four path-scan loops to `<= endYear` (folded into R2 in Phase 2 if you prefer one diff — default is to fix the bound here minimally, dedupe later). | ⚠ if year-specific files exist for the final year |
| 1.7 | **B12** `landEventCounter` off-by-one | Size `[size+1][2]` like the sibling tables; label all years; include tick 0. | Output files only |
| 1.8 | **B13/B14** loader crash-hardening | `updateAFTProduction`: explicit error if `Production` column missing. `updateAFTBehaviour`: explicit error naming the missing key. `GlobalCostData`: use `Utils.sToD`-style tolerant parse **or** fail with row/column context (per decision D4). | No |
| 1.9 | **B16** life-counter inconsistency | Remove the `setOwnerLifeCounter(0)` override in `takeOverUnmanageCells` (takeover already sets 1); add a comment on the `land_abandonment_fraction` reuse or introduce a dedicated config key (decision D5). | ⚠ one-year shift in life-cycle checks for reclaimed cells |
| 1.10 | **B17** `productivity` vs `competitiveness` fallback mismatch | Needs your call — see decision D1. | ⚠ depends on D1 |
| 1.11 | Low-severity batch | `hashAgentNbr` duplicated condition; `landUsechange` null-guard consistency; `processCSV` dead/unsafe header replace; `SubsidyUpdater.getParent()` null-guard + `land_taxes_subsidies_path` → `prescribed_subsidies_path` legacy alias; `outputfolderPath` dead assignment; stale `Config.inialize()` javadoc. | No (except the alias, which *restores* old configs' subsidies) |

Deferred from Phase 1 (institution-coupled, out of scope for now): **B7** (policies ignored without `use_cell_level_taxes`) and **B8** (`ServicesTax` default-1 vs merge-from-0). The *reader* lives in core, but the right fix depends on institution-side semantics — parked until that module is back in scope. Recorded in decision D2 so it isn't lost.

Deliverables: `patches/phase1-<nn>-<slug>.diff`, one per row (small, reviewable, individually revertable).

---

## Phase 2 — Behaviour-preserving refactors

Every diff here must leave the Phase-0 golden run byte-identical.

| # | Refactor | Content |
|---|----------|---------|
| 2.1 | **R6 dead code** | Delete: two commented method bodies in `CsvProcessors` (~60 lines); dead `updateBaselineIfsupplyIsNull` old+new (~230 lines) in `InitialDSEquilibriumManager` (unless you want it wired in — decision D6); commented debris in `RegionalModelRunner`/`Competitiveness`/`AbstractCell`; unused `Cell.calculateCurrentProductivity(String[])`. |
| 2.2 | **R2 `AFTsLoader` dedup** | One parameterised path-resolver replacing `production_paths`/`behaviourPaths` (~130 → ~50 lines); merge `getPath`/`getInitailPaths` branch logic; drop `extends HashSet<Aft>`. |
| 2.3 | **R3 `Cell` hot-loop dedup** | Extract the capital-exponent product into one helper used by `productivity` and `competitiveness`. |
| 2.4 | **R4 competition batch helper** | Shared "evaluate batch → apply in order → refresh supply/marginal" used by `competition()` and `twinnedCompetition()`; twin path becomes two-phase like the others. |
| 2.5 | **R7 `Tracker.sankyData` rewrite** | Replace decompiled-style iterators with plain for-each; same output. |
| 2.6 | **R5 config validation** | Single `validate()` range-checking all fractions/probabilities; keep existing `LOGGER.fatal` style (now reliable after 1.2). |
| 2.7 | **R10 tax accessors** | `AbstractCell.serviceTaxMultiplier(...)` / `landTaxTerm(...)` centralising the defaults; call sites in `Competitiveness`/`RegionalModelRunner` switch over. (Pure mechanical move of today's constants — does *not* resolve B8, just concentrates it in one place for when D2 is decided.) |
| 2.8 | **R8 error-handling pass (loaders)** | `ReadAsaHash` null-return call sites get explicit checks with file-path context; replace `printStackTrace` in core with `LOGGER.error`. Full fail-fast exception redesign only if D4 says so. |
| 2.9 | **R11** | Delete `TempCraftyFinlandAnalizer`; leave `CraftyDataUpscaler`/`SplitByRegions` (documented as offline tools) unless you want them moved. |

Deliverables: `patches/phase2-<nn>-<slug>.diff`, one per row, in the listed order (2.1 first shrinks everything after it).

---

## Phase 3 — Naming / typo debt (R9)

Staged to control blast radius, because **other modules import crafty-core** — renames of public API break their compilation even though they're out of cleanup scope.

- **3a (safe now):** private/internal identifiers and locals — `utilitytyForAll`, `updateRestrections`, `intializeForTaxesUse`, `writOutPutMap`, `updateCSVFilesWolrd`, `landChengePath`, `initialzeRun`, `getInitailPaths`, `initialTotalDSEquilibriumListrner`, log-message typos (`Rigion`), etc. Zero cross-module impact.
- **3b (small cross-module ripples):** public members — `getEndtYear()`, `cellsForecedToChange`, `capitalsAdjusment`, `listner`. I'd keep thin deprecated delegates for one release so gui/institution/plum still compile untouched, then remove later.
- **3c (deferred, decision D3):** the `serivces` → `services` **package** rename. Touches every import across all four modules — mechanically trivial with IDE support but not containable to core. Recommend doing it as its own dedicated commit when you're next touching the other modules anyway.

Deliverables: `patches/phase3a-…diff`, `phase3b-…diff` (3c only if D3 approves).

---

## Phase 4 — De-static the architecture (R1) — proposal only, not in this cleanup

The static-singleton problem (`AFTsLoader`, `CellsLoader`, `ServicesUpdater`, `Listener`, `Tracker`, `Timestep`, …) is the root cause of the disabled tests and the one-run-per-JVM limit, but fixing it properly is a multi-week architectural change, not a cleanup. Recommendation: **do not bundle it here.** After Phases 0–3 land, if you want it, I'd propose a separate incremental plan (introduce a `ModelContext`, migrate one subsystem at a time behind the golden-run test). Phases 0–3 deliberately avoid making that harder.

---

## Decisions needed before I cut diffs

| ID | Question | My default if you just say "go" |
|----|----------|-------------------------------|
| D1 | **B17:** when an AFT has no sensitivity rows for a service, `productivity` returns the bare productivity level but `competitiveness` returns 0. Which is correct? | Make `productivity` return 0 to match (and log once per AFT/service) — the current asymmetry looks accidental. |
| D2 | **B7/B8** (cell-level tax semantics): fix now in core, or park until institution work resumes? | Park; add a one-line startup warning when `institutions_directory` is set but `use_cell_level_taxes` is false. |
| D3 | Rename `serivces` package (touches all modules' imports)? | Defer (Phase 3c not produced). |
| D4 | Loader philosophy: fail fast on malformed input files, or tolerant-with-loud-warnings? (Affects 1.8, 2.8, and whether `Utils.sToD`'s silent-zero gets a reporting wrapper.) | Fail fast at load/startup time; tolerant only for optional per-year update files, with a per-file count of coerced values logged. |
| D5 | **B16:** dedicated `unmanaged_takeover_fraction`-style config key for the takeover seed size, or keep reusing `land_abandonment_fraction` with a comment? | Keep reuse + comment (no config surface change during cleanup). |
| D6 | Dead `updateBaselineIfsupplyIsNull` (~230 lines): delete, or wire the rewritten version in behind a config flag? | Delete (git history keeps it; reinstating later is a feature, not cleanup). |

---

## Sequencing & effort summary

```
Phase 0  safety net           ~4 test tasks        (must land first)
Phase 1  bug fixes            ~11 small diffs      (1.1, 1.2 highest value)
Phase 2  refactors            ~9 diffs             (golden-run-guarded)
Phase 3  naming               2–3 diffs            (3a safe, 3b careful, 3c deferred)
Phase 4  de-static            separate future plan
```

Each patch lands only after you've reviewed and applied it; I re-run the core suite + golden run against your applied tree before producing the next one in the sequence.
