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
import de.cesr.crafty.core.dataLoader.afts.AFTsLoader;
import de.cesr.crafty.core.dataLoader.land.CellsLoader;
import de.cesr.crafty.core.dataLoader.land.GisLoader;
import de.cesr.crafty.core.dataLoader.serivces.ServiceSet;
import de.cesr.crafty.core.dataLoader.serivces.ServiceWeightLoader;
import de.cesr.crafty.core.main.MainHeadless;
import de.cesr.crafty.core.modelRunner.ModelRunner;
import de.cesr.crafty.core.updaters.ProductionCostUpdater;
import de.cesr.crafty.core.updaters.Timestep;

/**
 * Entry point for CRAFTY in a coupled CRAFTY-PLUM run. It takes the same command line as MainHeadless
 * ({@code --config-file ...}) and runs CRAFTY the same way, with the coupler's start-up checks and steps added. The
 * jar's main class is {@link LandSymmLauncher}, which starts this as a child JVM; to run CRAFTY on its own, use
 * {@code java -cp crafty-landsymm-1.0.jar de.cesr.crafty.landsymm.MainLandSymm --config-file ...}. In order:
 * <ol>
 * <li>load the config (core's own checks run here);</li>
 * <li>check the coupling settings, before anything loads;</li>
 * <li>set up the project paths and the scenario (cheap), then check Services.csv, the region key and the
 * production and PLUM paths and years;</li>
 * <li>set the run's years ({@link RunYears}) and make the production and plum_coupling folders;</li>
 * <li>load CRAFTY (cells, capitals, AFTs), then the checks that need the loaded cells;</li>
 * <li>in a scenario run, read the calibration's final-year PLUM rows, check their countries, and work out and write
 * the price factors; with non-economic service prices on, check what they need and copy each region's spend;</li>
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
			outputs = CouplingOutputs.inOutputFolder(config.output_folder_name, config.plum_coupling_outputs);
			if (!outputs.switchedOff().isEmpty()) {
				LOGGER.info("Coupling: the coupler's yearly files switched off (plum_coupling_outputs): "
						+ outputs.switchedOff());
			}
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
					? new StepInserter.Steps(PlumInputStep.inCalibrationRun(), StockCostStep.off(),
							TradeCapitalStep.off(), NesPriceStep.off(), ConsumptionStep.off(), production)
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
		StockCostStep stockCost = StockCostStep.off();
		if (config.use_stock_costs) {
			StockCosts stockCosts = StockCosts.create(data.services(), calibrationRows,
					ProductionCostUpdater.getGlobalCostData().getStockCost(), config.stock_target,
					config.stock_cost_threshold);
			stockCost = new StockCostStep(plumYear, stockCosts, outputs, ProductionCostUpdater::setStockCosts);
			LOGGER.info("Coupling from PLUM: stock costs, Stock_cost " + stockCosts.stockCost()
					+ " x stock ratio (world stock / (production + imports - exports)) when the ratio is above "
					+ "stock_target " + config.stock_target + " x stock_cost_threshold " + config.stock_cost_threshold
					+ " = " + stockCosts.criticalRatio() + ", on "
					+ stockCosts.payers().stream().map(ServiceCommodityMap.Link::service).toList()
					+ (stockCosts.exempt().isEmpty() ? ""
							: "; never on " + stockCosts.exempt() + ", whose PLUM_price_name has no row in PLUM's "
									+ PlumYearOutputs.PRICES_FILE));
		}
		NesPriceStep nesPrices = nesStep(config, outputs, regions, startYear);
		LOGGER.info("Coupling from PLUM: prices from the calibration's " + config.plum_calibration_final_year
				+ " rows in " + startYear + ", then PLUM's year t - 1"
				+ (config.initialise_regional_price_weights ? ", each region starting at its country's mark-up" : "")
				+ "; consumption "
				+ (config.plum_consumption && ConfigLoader.isTransportByExportShare() ? "from PLUM" : "from the files")
				+ "; trade capitals " + (config.dynamic_trade_capitals ? "from PLUM (slope " + config.trade_capital_slope
						+ ", midpoint " + config.trade_capital_midpoint + ", averaged over "
						+ config.trade_capital_averaging_years + " years)" : "from the files"));
		return new StepInserter.Steps(plumInput, stockCost, tradeCapitals, nesPrices, consumption, production);
	}

	/**
	 * The non-economic service (NES) step (50a PLAN): off unless dynamic_nes_prices and at least one service's switch
	 * are on (today only dynamic_biodiversity_price). Its start-up checks run here, after CRAFTY has loaded, and each
	 * region's spend is copied from its weights before any price is written over them.
	 */
	private static NesPriceStep nesStep(Config config, CouplingOutputs outputs, ProductionStep.Regions regions,
			int startYear) {
		if (!config.dynamic_nes_prices) {
			if (config.dynamic_biodiversity_price) {
				LOGGER.info("Coupling: dynamic_biodiversity_price is on but dynamic_nes_prices is off, so no "
						+ "non-economic service price is set");
			}
			return NesPriceStep.off();
		}
		if (!config.dynamic_biodiversity_price) {
			LOGGER.warn("Coupling: dynamic_nes_prices is on but no service's switch is (dynamic_biodiversity_price), so no "
					+ "non-economic service price is set");
			return NesPriceStep.off();
		}
		String service = "Biodiversity";
		CouplingStartupCheck.NesReport report = CouplingStartupCheck.nesReport(service,
				config.biodiversity_allocation_capital, ServiceSet.getServicesList(), ServiceSet.getMarketGood(),
				ProjectLoader.getCapitalsMetadata(), ProjectLoader.getAftMetaData(), config.service_utility_weights_path,
				ServiceWeightLoader.getRegionsWithDefaultWeights(), AFTsLoader.getAftHash().values());
		report.warnings().forEach(w -> LOGGER.warn("Coupling: " + w));
		if (!report.problems().isEmpty()) {
			throw new CouplingInputException(report.problems());
		}
		PlumInputStep.Weights weights = PlumInputStep.Weights.ofModel();
		NesBudget budget = NesBudget.fromWeights(weights, service, regions.cells().keySet(), startYear,
				Timestep.getEndtYear());
		NesPricing biodiversity = new NesPricing(service, config.biodiversity_allocation_capital,
				NesMetric.of(config.biodiversity_metric), report.counted(), budget, NesPriceRule.BUDGET_PER_AREA, weights);
		report.notes().forEach(n -> LOGGER.info("Coupling: " + n));
		LOGGER.info("Coupling: non-economic service prices each year for [" + service + "]: each region's spend (its "
				+ service + " weight, million US$) / max(counted land, mean pixel), metric " + config.biodiversity_metric
				+ ", written into " + config.biodiversity_allocation_capital + " on every cell over the capitals file's values");
		return new NesPriceStep(List.of(biodiversity), outputs, regions);
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
