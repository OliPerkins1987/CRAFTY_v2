package de.cesr.crafty.landsymm;

import java.nio.file.Paths;

import org.apache.logging.log4j.LogManager;

import de.cesr.crafty.core.cli.Config;
import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.dataLoader.ProjectLoader;
import de.cesr.crafty.core.dataLoader.land.CellsLoader;
import de.cesr.crafty.core.dataLoader.land.GisLoader;
import de.cesr.crafty.core.main.MainHeadless;
import de.cesr.crafty.core.modelRunner.ModelRunner;
import de.cesr.crafty.core.updaters.Timestep;

/**
 * Entry point for CRAFTY in a coupled CRAFTY-PLUM run. It takes the same command line as MainHeadless
 * ({@code --config-file ...}) and runs CRAFTY the same way, with the coupler's start-up checks and steps added:
 * <ol>
 * <li>load the config (core's own checks run here);</li>
 * <li>check the coupling settings, before anything loads;</li>
 * <li>set up the project paths and the scenario (cheap), then check Services.csv, the region key and the
 * production and PLUM paths and years;</li>
 * <li>load CRAFTY (cells, capitals, AFTs), then the checks that need the loaded cells;</li>
 * <li>put the coupler's steps into the yearly schedule for the coupling mode;</li>
 * <li>a calibration run builds CRAFTY's starting state and stops; a scenario run runs to coupled_end_year, or to
 * the scenario's end.</li>
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
		CouplingStartupCheck.Report data = CouplingStartupCheck.dataReport(config,
				ProjectLoader.getServiceMetadata(), CouplingStartupCheck.locateGisFile(config),
				Timestep.getStartYear(), Timestep.getEndtYear());
		report("The coupling inputs", data);
		LOGGER.info("Coupling: " + data.services().links().size() + " market services, " + data.key().regions().size()
				+ " regions, mode " + config.plum_coupling_mode + ", price basis " + config.plum_price_basis
				+ ", price scaling " + config.plum_price_scaling);

		ModelRunner runner = new ModelRunner();
		MainHeadless.runner = runner;
		runner.start();
		stopIf("After loading", CouplingStartupCheck.afterLoadProblems(data.key(), GisLoader.getGisFile(),
				CellsLoader.regions.keySet(), CellsLoader.regionalisation));

		try {
			StepInserter.insert(runner.getScheduled(), new StepInserter.Steps(new PlumInputStep(),
					new TradeCapitalStep(), new ConsumptionStep(), new ProductionStep()), config.plum_coupling_mode,
					StepInserter.Anchors.of(runner));
		} catch (CouplingInputException e) {
			stopIf("Placing the coupler's steps", e.getProblems());
		}

		if (config.plum_calibration_run) {
			runner.initialzeRun();
			LOGGER.info("Calibration run: CRAFTY's starting state is built for " + Timestep.getStartYear());
			CustomLogger.shutdownRunFileLoggers();
			LogManager.shutdown();
			return;
		}

		if (config.coupled_end_year > 0) {
			Timestep.setEndtYear(config.coupled_end_year);
			LOGGER.info("Coupled run ends in " + config.coupled_end_year + " (coupled_end_year)");
		}
		runner.initialzeRun();
		runner.run();
	}

	private static void report(String what, CouplingStartupCheck.Report report) {
		report.warnings().forEach(w -> LOGGER.warn("Coupling: " + w));
		stopIf(what, report.problems());
	}

	private static void stopIf(String what, java.util.List<String> problems) {
		if (problems.isEmpty()) {
			return;
		}
		String message = what + " have " + problems.size() + " problem(s), so the coupled run stops:\n  "
				+ String.join("\n  ", problems);
		LOGGER.fatal(message);
		// LOGGER.fatal exits once the config is loaded; this covers the case where it isn't
		System.err.println(message);
		System.exit(1);
	}
}
