package de.cesr.crafty.react;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.dataLoader.RunInputFiles;
import de.cesr.crafty.core.modelRunner.ModelRunner;
import de.cesr.crafty.core.updaters.AbstractUpdater;
import de.cesr.crafty.core.updaters.CapitalUpdater;
import de.cesr.crafty.core.updaters.ProductionCostUpdater;
import de.cesr.crafty.core.updaters.Timestep;
import de.cesr.crafty.react.data.CoreFacts;
import de.cesr.crafty.react.data.ReactConfigLoader;
import de.cesr.crafty.react.data.ReactInputException;
import de.cesr.crafty.react.data.ReactInputs;
import de.cesr.crafty.react.data.ReactRunContext;
import de.cesr.crafty.react.data.ReactYearData;
import de.cesr.crafty.react.decisions.CropDecisions;
import de.cesr.crafty.react.decisions.CropManagement;
import de.cesr.crafty.react.decisions.DecisionUnits;
import de.cesr.crafty.react.decisions.PastureDecisions;
import de.cesr.crafty.react.decisions.PastureManagement;
import de.cesr.crafty.react.decisions.YearPrices;
import de.cesr.crafty.react.science.CropSurfaces;

/**
 * The yearly CRAFTY-react step, and the one class crafty-core knows about.
 *
 * When reactive_afts is true, crafty-core creates this class by name
 * ({@link ModelRunner#REACTIVE_UPDATER_CLASS}) and runs it directly before
 * CapitalUpdater, every year and for year zero. Each year it writes react's
 * version of the input files it is responsible for, which the model then reads:
 * <ul>
 * <li>the capitals file, when any of fertiliser, irrigation, other intensity or
 * stocking is reactive (react replaces the reactive AFTs' _suit columns);</li>
 * <li>each spatial cost file whose element is reactive (react replaces the
 * reactive AFTs' columns).</li>
 * </ul>
 * All other columns are copied from the input file, so the model reads a
 * complete file. Files react is not responsible for are left alone.
 *
 * Each year it loads that year's data and runs react's stages on it, in order:
 * the cropland decisions ({@link CropDecisions}), then the pasture decisions
 * ({@link PastureDecisions}). It logs one line for the year and one per AFT.
 *
 * Nothing is written yet: the files are still passed through (phase 5 writes
 * react's values into them). In run-folder mode the files react is responsible
 * for are copied unchanged to where the model will read them; in overwrite mode
 * they are left as they are.
 */
public class ReactiveUpdater extends AbstractUpdater {

	private static final CustomLogger LOGGER = new CustomLogger(ReactiveUpdater.class);

	/** Finds the input files react writes for a year. Replaceable for tests. */
	private final IntFunction<List<Path>> filesReactWrites;

	/** React's inputs: the checked project, and the year being simulated. Null only in phase 0 tests. */
	private final ReactInputs inputs;

	/** Where react makes its decisions: built once, from the cell key. Null only in phase 0 tests. */
	private final DecisionUnits units;

	/** The cropland stage, which carries each AFT's N from year to year. Null only in phase 0 tests. */
	private final CropDecisions crops;

	/** The pasture stage, which carries each AFT's stocking rate from year to year. Null only in phase 0 tests. */
	private final PastureDecisions pasture;

	/**
	 * Year zero is stepped during initialisation and again as the first scheduled step. This stops it being
	 * decided and written twice, so the spin-up happens once.
	 */
	private Integer lastYearWritten = null;

	/**
	 * The constructor crafty-core calls, at the end of {@code ModelRunner.start()}.
	 *
	 * It reads react's own settings, gathers what react needs from core, and runs the startup checks.
	 * Everything the checks need - services, AFT metadata, cells, the years, the spatial cost files - has
	 * been loaded by this point. A project react cannot use stops the run here, before any year is
	 * simulated. (The log files are not open yet, so that message reaches the console only.)
	 *
	 * In run-folder mode it also tells the model to read each year's files from
	 * {@value RunInputFiles#RUN_FOLDER_NAME} in the run's output folder.
	 */
	public ReactiveUpdater() {
		this(ReactiveUpdater::filesReactWrites, createInputs());
		if (ConfigLoader.isReactiveRunFolderMode()) {
			// By now output_folder_name holds the run's full output folder path.
			Path runFolder = Paths.get(ConfigLoader.config.output_folder_name, RunInputFiles.RUN_FOLDER_NAME);
			RunInputFiles.useRunFolder(runFolder);
			LOGGER.info("CRAFTY-react writes each year's capitals and cost files to " + runFolder);
		}
	}

	ReactiveUpdater(IntFunction<List<Path>> filesReactWrites) {
		this(filesReactWrites, null);
	}

	/** With react's stages built from its inputs. */
	ReactiveUpdater(IntFunction<List<Path>> filesReactWrites, ReactInputs inputs) {
		this(filesReactWrites, inputs, decisionUnits(inputs));
	}

	private ReactiveUpdater(IntFunction<List<Path>> filesReactWrites, ReactInputs inputs, DecisionUnits units) {
		this(filesReactWrites, inputs, units, inputs == null ? null : CropDecisions.create(inputs, units),
				inputs == null ? null : PastureDecisions.create(inputs, units));
	}

	/** With react's stages passed in, so that a test can choose them. */
	ReactiveUpdater(IntFunction<List<Path>> filesReactWrites, ReactInputs inputs, DecisionUnits units,
			CropDecisions crops, PastureDecisions pasture) {
		this.filesReactWrites = filesReactWrites;
		this.inputs = inputs;
		this.units = units;
		this.crops = crops;
		this.pasture = pasture;
	}

	/** The decision units, built from the checked cell key and the model's regions. */
	private static DecisionUnits decisionUnits(ReactInputs inputs) {
		return inputs == null ? null
				: DecisionUnits.build(inputs.checked().cellKey(), inputs.context().regions());
	}

	/** Loads react's settings and checks the project, stopping the run if it cannot be used. */
	private static ReactInputs createInputs() {
		ReactRunContext context = CoreFacts.fromCore();
		try {
			return ReactInputs.create(ReactConfigLoader.load(context.projectPath()), context);
		} catch (ReactInputException e) {
			LOGGER.fatal(e.getMessage());
			return null; // not reached: LOGGER.fatal stops the run
		}
	}

	/**
	 * The input files react writes for a year, as found by crafty-core at startup:
	 * the capitals file if react writes capitals, and each spatial cost file whose
	 * cost type is reactive.
	 */
	static List<Path> filesReactWrites(int year) {
		List<Path> files = new ArrayList<>();
		Path capitals = CapitalUpdater.getCapitalPath(year);
		if (capitals != null && ConfigLoader.isReactiveCapitals()) {
			files.add(capitals);
		}
		if (ModelRunner.productionCostUpdater != null) {
			ModelRunner.productionCostUpdater.getSpatialCostPaths(year).forEach((costType, path) -> {
				if (ProductionCostUpdater.isReactiveCostType(costType)) {
					files.add(path);
				}
			});
		}
		return files;
	}

	@Override
	public void toSchedule() {
		modelRunner.scheduleRepeating(this);
	}

	@Override
	public void step() {
		int year = Timestep.getCurrentYear();
		if (lastYearWritten != null && lastYearWritten == year) {
			return;
		}
		if (inputs != null) {
			try {
				decideYear(year);
			} catch (ReactInputException e) {
				LOGGER.fatal(e.getMessage());
			}
		}
		writeInputFiles(year);
		lastYearWritten = year;
	}

	/**
	 * Loads a year (the year before is released) and runs react's stages on it: the cropland decisions, then
	 * the pasture decisions. A stage with no AFT to decide (none of its elements on) is skipped; with neither,
	 * only the data is loaded.
	 *
	 * @throws ReactInputException if the year cannot be decided, for example when a service has no price
	 */
	void decideYear(int year) {
		ReactYearData data = inputs.forYear(year);
		boolean decideCrops = !crops.managements().isEmpty();
		boolean decidePasture = !pasture.managements().isEmpty();
		if (!decideCrops && !decidePasture) {
			return;
		}
		long start = System.nanoTime();
		Integer lastYearDecided = decideCrops ? crops.lastYear() : pasture.lastYear();
		int steps = lastYearDecided == null ? inputs.config().spinupIterations() : 1;
		CropSurfaces surfaces = null;
		String fitted = "";
		if (decideCrops) {
			surfaces = CropSurfaces.fit(data);
			fitted = String.format("surfaces fitted in %.2f s; ", (System.nanoTime() - start) / 1e9);
		}
		// One look-up a year, for the services both stages need.
		Set<String> services = new LinkedHashSet<>(crops.servicesNeedingPrices());
		services.addAll(pasture.servicesNeedingPrices());
		YearPrices prices = YearPrices.forYear(year, services, units, inputs.context().prices());
		Map<String, CropManagement> cropsDecided = decideCrops ? crops.decide(data, surfaces, prices) : Map.of();
		Map<String, PastureManagement> pastureDecided = decidePasture ? pasture.decide(data, prices) : Map.of();

		LOGGER.info(String.format("CRAFTY-react decided year %d in %.2f s (%s%d %s): %d crops AFT(s) and %d pasture"
				+ " AFT(s) in %d decision units", year, (System.nanoTime() - start) / 1e9, fitted, steps,
				steps == 1 ? "step" : "spin-up steps", cropsDecided.size(), pastureDecided.size(), units.size()));
		for (CropManagement management : cropsDecided.values()) {
			LOGGER.info("CRAFTY-react " + year + " " + management.summary());
		}
		for (PastureManagement management : pastureDecided.values()) {
			LOGGER.info("CRAFTY-react " + year + " " + management.summary());
		}
	}

	/** React's inputs, once the checks have passed. */
	public ReactInputs getInputs() {
		return inputs;
	}

	/** The cropland stage, holding each crops AFT's management for the last year decided. */
	public CropDecisions getCropDecisions() {
		return crops;
	}

	/** The pasture stage, holding each pasture AFT's management for the last year decided. */
	public PastureDecisions getPastureDecisions() {
		return pasture;
	}

	/** Phase 0 pass-through; see the class comment. */
	void writeInputFiles(int year) {
		if (!RunInputFiles.isUsingRunFolder()) {
			return;
		}
		for (Path original : filesReactWrites.apply(year)) {
			Path runVersion = RunInputFiles.resolve(original);
			try {
				Files.createDirectories(runVersion.getParent());
				Files.copy(original, runVersion, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				LOGGER.fatal("crafty-react could not write " + runVersion + " for year " + year + ": " + e);
			}
		}
	}
}
