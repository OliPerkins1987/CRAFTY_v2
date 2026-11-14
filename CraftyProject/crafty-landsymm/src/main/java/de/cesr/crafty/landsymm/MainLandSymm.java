package de.cesr.crafty.landsymm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

import org.apache.logging.log4j.LogManager;

import de.cesr.crafty.core.cli.Config;
import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.dataLoader.ProjectLoader;
import de.cesr.crafty.core.dataLoader.land.CellsLoader;
import de.cesr.crafty.core.dataLoader.land.GisLoader;
import de.cesr.crafty.core.main.MainHeadless;
import de.cesr.crafty.core.modelRunner.ModelRunner;
import de.cesr.crafty.core.updaters.ProductionCostUpdater;
import de.cesr.crafty.core.updaters.Timestep;

/**
 * Entry point for CRAFTY in a coupled CRAFTY-PLUM run. It takes the same command line as MainHeadless
 * ({@code --config-file ...}) and runs CRAFTY the same way, with the coupler's start-up checks and steps added:
 * <ol>
 * <li>load the config (core's own checks run here);</li>
 * <li>check the coupling settings, before anything loads;</li>
 * <li>set up the project paths and the scenario (cheap), then check Services.csv, the region key and the
 * production and PLUM paths and years;</li>
 * <li>set the run's years ({@link RunYears}) and make the production and plum_coupling folders;</li>
 * <li>load CRAFTY (cells, capitals, AFTs), then the checks that need the loaded cells;</li>
 * <li>in a scenario run, read the calibration's final-year PLUM rows, check their countries, and work out and write
 * the price factors;</li>
 * <li>put the coupler's steps into the yearly schedule for the coupling mode;</li>
 * <li>a calibration run builds CRAFTY's starting state, writes its production for PLUM and stops; a scenario run
 * builds the same starting state, compares its production with the calibration run's (the 1990 check), then runs to
 * coupled_end_year, or to the scenario's end. The start year has giving up and competition, as in standalone
 * CRAFTY.</li>
 * </ol>
 * Any problem stops the run with one message listing them all, and exit code 1. A clean finish exits with 0.
 */
public final class MainLandSymm {

	private static final CustomLogger LOGGER = new CustomLogger(MainLandSymm.class);

	private MainLandSymm() {
	}

	public static void main(String[] args) {
		System.out.println("--Starting CRAFTY for a coupled CRAFTY-PLUM run (crafty-landsymm)--");
		MainHeadless.initializeConfig(args);
		Config config = ConfigLoader.config;
		report("The coupling settings", CouplingStartupCheck.configReport(config));

		ProjectLoader.pathInitialisation(Paths.get(config.project_path));
		int startYear = Timestep.getStartYear();
		int scenarioEndYear = Timestep.getEndtYear();
		CouplingStartupCheck.Report data = CouplingStartupCheck.dataReport(config,
				ProjectLoader.getServiceMetadata(), ProjectLoader.getCapitalsMetadata(),
				CouplingStartupCheck.locateGisFile(config), startYear, scenarioEndYear);
		report("The coupling inputs", data);

		// Before anything loads, so CRAFTY's loaders and outputs are sized to the years that run
		int endYear = RunYears.endYear(config.plum_calibration_run, config.coupled_end_year, startYear,
				scenarioEndYear);
		RunYears.apply(startYear, endYear);
		LOGGER.info("Coupling: " + data.services().links().size() + " market services, " + data.key().regions().size()
				+ " regions, " + (config.plum_calibration_run ? "calibration run" : "scenario run") + ", years "
				+ startYear + "-" + endYear + ", mode " + config.plum_coupling_mode + ", price basis "
				+ config.plum_price_basis + ", price scaling " + config.plum_price_scaling);

		CouplingOutputs outputs = null;
		try {
			// PLUM stops at once if the production folder isn't there when it starts waiting
			Files.createDirectories(Paths.get(config.crafty_production_path));
			outputs = CouplingOutputs.inOutputFolder(config.output_folder_name);
		} catch (IOException e) {
			stopIf("Making the production folder", List.of(config.crafty_production_path + ": " + e));
		} catch (CouplingInputException e) {
			stopIf("Making the coupler's output folder", e.getProblems());
		}

		ModelRunner runner = new ModelRunner();
		MainHeadless.runner = runner;
		runner.start();
		stopIf("After loading", CouplingStartupCheck.afterLoadProblems(data.key(), GisLoader.getGisFile(),
				CellsLoader.regions.keySet(), CellsLoader.regionalisation));

		ProductionStep production = null;
		try {
			ProductionStep.Regions regions = ProductionStep.Regions.ofModel();
			production = new ProductionStep(config, data.services(), data.key(), outputs, regions);
			StepInserter.Steps steps = config.plum_calibration_run
					? new StepInserter.Steps(PlumInputStep.inCalibrationRun(), TradeCapitalStep.off(),
							ConsumptionStep.off(), production)
					: scenarioSteps(config, data, outputs, regions, startYear, production);
			StepInserter.insert(runner.getScheduled(), steps, config.plum_coupling_mode, StepInserter.Anchors.of(runner));
		} catch (CouplingInputException e) {
			stopIf("Setting up the coupler's steps", e.getProblems());
		}

		if (config.plum_calibration_run) {
			runner.initialzeRun();
			production.step();
			LOGGER.info("Calibration run: CRAFTY's " + startYear + " production is in " + config.crafty_production_path
					+ ", for PLUM's calibration");
			CustomLogger.shutdownRunFileLoggers();
			LogManager.shutdown();
			System.exit(0);
		}

		runner.initialzeRun();
		try {
			// The starting state, as a calibration run writes it
			production.checkBaseline(startYear);
		} catch (CouplingInputException e) {
			stopIf("The " + startYear + " check against the calibration run", e.getProblems());
		}
		runner.run(); // shuts the logs down at the end
		System.exit(0);
	}

	/**
	 * A scenario run's steps, with what they need from PLUM's calibration (48d PLAN section 2): its final-year rows,
	 * whose countries must be CRAFTY's, and the price factors from them and the start-year weights, written to
	 * plum_coupling/price_factors.csv. All before the first year, so a problem stops the run within minutes.
	 */
	private static StepInserter.Steps scenarioSteps(Config config, CouplingStartupCheck.Report data,
			CouplingOutputs outputs, ProductionStep.Regions regions, int startYear, ProductionStep production) {
		PlumYearOutputs calibrationRows = PlumYearOutputs.read(Paths.get(config.plum_calibration_path),
				config.plum_calibration_final_year);
		List<String> countries = PlumCountryCheck.problems(calibrationRows, data.key());
		if (!countries.isEmpty()) {
			throw new CouplingInputException(countries);
		}
		PlumInputStep.Weights weights = PlumInputStep.Weights.ofModel();
		PriceScaling scaling = PriceScaling.create(PriceScaling.Scaling.of(config.plum_price_scaling),
				PriceScaling.Basis.of(config.plum_price_basis), data.services(), data.key(),
				PlumInputStep.startWeights(weights, data.services(), data.key(), startYear), calibrationRows,
				config.initialise_regional_price_weights);
		scaling.writeCsv(outputs.file("price_factors.csv"));

		PlumYear plumYear = new PlumYear();
		PlumInputStep plumInput = new PlumInputStep(plumYear, scaling, data.key(), outputs, weights,
				PlumInputStep.Source.plumOutput(Paths.get(config.plum_output_path),
						Duration.ofMinutes(config.plum_wait_timeout_minutes)),
				startYear, calibrationRows);
		ConsumptionStep consumption = config.plum_consumption && ConfigLoader.isTransportByExportShare()
				? new ConsumptionStep(plumYear, data.services(), data.key(), outputs, regions,
						ProductionCostUpdater::setConsumption)
				: ConsumptionStep.off();
		TradeCapitalStep tradeCapitals = config.dynamic_trade_capitals
				? new TradeCapitalStep(plumYear, data.tradeCapitals(),
						new SelfSufficiencyLogisticRule(config.trade_capital_slope, config.trade_capital_midpoint),
						config.trade_capital_slope, config.trade_capital_midpoint, config.trade_capital_averaging_years,
						data.key(), outputs, regions)
				: TradeCapitalStep.off();
		LOGGER.info("Coupling from PLUM: prices from the calibration's " + config.plum_calibration_final_year
				+ " rows in " + startYear + ", then PLUM's year t - 1"
				+ (config.initialise_regional_price_weights ? ", each region starting at its country's mark-up" : "")
				+ "; consumption "
				+ (config.plum_consumption && ConfigLoader.isTransportByExportShare() ? "from PLUM" : "from the files")
				+ "; trade capitals " + (config.dynamic_trade_capitals ? "from PLUM (slope " + config.trade_capital_slope
						+ ", midpoint " + config.trade_capital_midpoint + ", averaged over "
						+ config.trade_capital_averaging_years + " years)" : "from the files"));
		return new StepInserter.Steps(plumInput, tradeCapitals, consumption, production);
	}

	private static void report(String what, CouplingStartupCheck.Report report) {
		report.warnings().forEach(w -> LOGGER.warn("Coupling: " + w));
		stopIf(what, report.problems());
	}

	/**
	 * Stops the run, with exit code 1, if there are problems: one fatal message lists them all. Used at start-up and by
	 * the coupler's steps.
	 */
	static void stopIf(String what, List<String> problems) {
		if (problems.isEmpty()) {
			return;
		}
		String message = what + ": " + problems.size() + " problem(s), so the coupled run stops:\n  "
				+ String.join("\n  ", problems);
		LOGGER.fatal(message);
		// LOGGER.fatal exits once the config is loaded; this covers the case where it isn't
		System.err.println(message);
		System.exit(1);
	}
}
