# crafty-landsymm: coupled CRAFTY–PLUM runs

CRAFTY supplies the land use and production; PLUM supplies the markets (prices, consumption, trade). The two run as
separate JVMs and take turns through files in a run folder. The design is in
`patches/48-plum-global-coupler/PLAN.md`; the launcher is `patches/48e-launcher/PLAN.md`.

## Build

```bash
mvn -pl crafty-landsymm -am package -DskipTests
```

The jar is `crafty-landsymm/target/crafty-landsymm-1.0.jar`. Its main class is the launcher.

## Run

Each run is described by a master YAML (examples in `examples/`):

```bash
java -jar crafty-landsymm-1.0.jar --check examples/calibration.yaml
```

```bash
java -jar crafty-landsymm-1.0.jar examples/calibration.yaml
```

- `--check` runs every check, writes the run's configs to a temporary folder and prints where, and starts nothing.
  It loads no model, so it's safe to run while another run is going.
- A **calibration run** (`is_calibration_run: true`) runs CRAFTY's start year, which writes `production/1990`,
  then PLUM's calibration on it. It's complete once PLUM has exited cleanly and left its calibrated state.
- A **scenario run** (`is_calibration_run: false`) names a complete calibration run and runs CRAFTY and PLUM
  together, 1990 to `end_year`. PLUM solves `end_year` too.
- If either model fails, the launcher stops the other, with everything it started (on Windows, PLUM's `gams.exe`).
  Stopping the launcher (Ctrl+C, closing its window, `scancel`) stops both models too.
- Exit code: 0 when the run is complete, 1 otherwise, 2 for bad arguments.

The master YAML holds what describes the run and what the two models must agree on. Every other choice stays in your
CRAFTY `config.yaml` and PLUM `config.properties`, which the launcher copies into the run folder and never edits. In
the copies, each line the launcher replaced is kept as a `# [launcher]` comment, and the launcher's values are in one
block at the end, so a diff against your file shows exactly what changed. In particular:
- CRAFTY's `use_price_feedback`, `global_price_feedback` and `initial_demand_supply_equilibrium` are turned off, so
  the same `config.yaml` serves standalone runs.
- The coupler's settings (`plum_coupling_mode`, `plum_price_basis`, `dynamic_trade_capitals`, …) come only from the
  master YAML's `coupling` block, and `plum_coupling_outputs` from its `outputs` block. A `config.yaml` that sets one
  stops the run.
- `use_stock_costs`, `stock_target` and `stock_cost_threshold` are the exception: they stay in your `config.yaml`.
  With stock costs on, a standalone run of that `config.yaml` stops, because stock costs need PLUM. Each year the
  coupler sets each market good's stock cost per unit, `Stock_cost` (`global_costs.csv`) × stock ratio, where stock
  ratio = PLUM's world stock ÷ (production + imports − exports) of the commodity it is priced on
  (`PLUM_price_name`), when the ratio is above `stock_target` × `stock_cost_threshold`. It writes every term to
  `plum_coupling/stock_costs.csv` (`outputs: stock_costs`, on by default). AFTs pay their `Pays_stock_cost` share
  (`AFTsMetaData`). Pasture follows ruminant stocks, since PLUM keeps no stock of grass.
- PLUM's config must already have `ALL_CRAFTY_COUNTRIES=true` and `BASE_YEAR` = CRAFTY's start year; the launcher
  sets `IS_CALIBRATION_RUN`, the timesteps, `OUTPUT_DIR`, `CRAFTY_PRODUCTION_DIR`, `CALIB_DIR` and
  `LPJG_MONITOR_TIMEOUT`.
- Paths in both base files must be absolute, because each model runs in the run folder.

## The run folder

```
<output_root>/
  jars/crafty-landsymm-<hash>.jar   the jar CRAFTY runs from: one copy per build, shared by runs
  <run_name>/
    master.yaml            the master YAML, as given
    run.yaml               what was resolved, and how the run ended (status: running, complete, failed, interrupted)
    launcher.log           the launcher's log
    timeline.csv           when each year's done files appeared (scenario runs)
    config_vs_calibration.txt   settings that differ from the calibration run's (scenario runs; a record only)
    production/<year>/production.csv + done      CRAFTY → PLUM
    crafty/config.yaml     crafty/crafty.log     crafty/output/<scenario>/<run_name>/ (with plum_coupling/)
    plum/config.properties plum/stdout.txt       plum/output/ (PLUM's OUTPUT_DIR)
```

The launcher never re-uses a run folder: choose a new `run_name` for each run.

On Windows, a jar a running JVM has open usually can't be replaced, so a long run can block `mvn package`. CRAFTY
runs from its copy in `jars/`; run the launcher itself from a copy too, e.g.:

```powershell
Copy-Item crafty-landsymm\target\crafty-landsymm-1.0.jar C:\Users\olive\Documents\UEA_fellowship\coupled_runs\bin\
```

## Running CRAFTY on its own

With a hand-made coupled `config.yaml` (as for tests A and B):

```bash
java -Xmx16g -cp crafty-landsymm-1.0.jar de.cesr.crafty.landsymm.MainLandSymm --config-file config.yaml
```
