package de.cesr.crafty.react;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.dataLoader.RunInputFiles;
import de.cesr.crafty.core.modelRunner.ModelRunner;
import de.cesr.crafty.core.updaters.AbstractUpdater;
import de.cesr.crafty.core.updaters.CapitalUpdater;
import de.cesr.crafty.core.updaters.ProductionCostUpdater;
import de.cesr.crafty.core.updaters.Timestep;

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
 * Phase 0 is a pass-through: no columns are replaced yet. In run-folder mode the
 * files react is responsible for are copied unchanged to where the model will
 * read them; in overwrite mode they are left as they are.
 */
public class ReactiveUpdater extends AbstractUpdater {

	private static final CustomLogger LOGGER = new CustomLogger(ReactiveUpdater.class);

	/** Finds the input files react writes for a year. Replaceable for tests. */
	private final IntFunction<List<Path>> filesReactWrites;

	/** Year zero is written during initialisation; this stops it being written twice. */
	private Integer lastYearWritten = null;

	/**
	 * The constructor crafty-core calls. In run-folder mode it also tells the model
	 * to read each year's files from {@value RunInputFiles#RUN_FOLDER_NAME} in the
	 * run's output folder.
	 */
	public ReactiveUpdater() {
		this(ReactiveUpdater::filesReactWrites);
		if (ConfigLoader.isReactiveRunFolderMode()) {
			// By now output_folder_name holds the run's full output folder path.
			Path runFolder = Paths.get(ConfigLoader.config.output_folder_name, RunInputFiles.RUN_FOLDER_NAME);
			RunInputFiles.useRunFolder(runFolder);
			LOGGER.info("CRAFTY-react writes each year's capitals and cost files to " + runFolder);
		}
	}

	ReactiveUpdater(IntFunction<List<Path>> filesReactWrites) {
		this.filesReactWrites = filesReactWrites;
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
		writeInputFiles(year);
		lastYearWritten = year;
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
