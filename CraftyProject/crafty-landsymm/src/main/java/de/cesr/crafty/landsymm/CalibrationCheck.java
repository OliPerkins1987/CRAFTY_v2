package de.cesr.crafty.landsymm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A calibration run's results (48e PLAN sections 5 and 6.1): the launcher checks them before it marks a calibration
 * run complete, and again when a scenario run names one.
 */
final class CalibrationCheck {

	/**
	 * What a PLUM scenario in CRAFTY mode reads from CALIB_DIR: its calibrated state and the calibration's double
	 * parameters. PLUM writes them after the final year's done file, so only these say the calibration finished.
	 */
	static final List<String> PLUM_STATE_FILES = List.of("internationalMarket.ser", "demandManager.ser",
			"countryCropUsages.ser", "countryWoodUsage.ser", "calib_config.properties");

	private static final int MAX_LISTED = 20;

	private CalibrationCheck() {
	}

	/**
	 * A complete calibration run, as a scenario run uses it.
	 *
	 * @param folder       the calibration run's folder
	 * @param startYear    CRAFTY's start year, the year of its production for PLUM
	 * @param finalYear    PLUM's final calibration year (BASE_YEAR + END_TIMESTEP), its start-year state
	 * @param production   the calibration run's production folder
	 * @param plumOutput   the calibration run's PLUM output folder (a scenario's CALIB_DIR)
	 * @param craftyConfig the calibration run's copy of CRAFTY's config.yaml
	 * @param plumConfig   the calibration run's copy of PLUM's config.properties
	 */
	record Calibration(Path folder, int startYear, int finalYear, Path production, Path plumOutput, Path craftyConfig,
			Path plumConfig) {
	}

	/** Reads a calibration run's run.yaml and checks its files. Stops with every problem found. */
	static Calibration ofRun(Path folder) {
		String where = "The calibration run " + folder;
		if (!Files.isRegularFile(folder.resolve(RunRecord.FILE))) {
			throw new CouplingInputException(where + " has no " + RunRecord.FILE + ". A scenario run starts only "
					+ "from a calibration run made by the launcher (48e Q4 a)");
		}
		RunRecord record = RunRecord.read(folder);
		List<String> problems = new ArrayList<>();
		if (!RunRecord.CALIBRATION.equals(record.text("run_type"))) {
			problems.add(where + " is a " + record.text("run_type") + " run, not a calibration run");
		}
		if (!RunRecord.COMPLETE.equals(record.text("status"))) {
			problems.add(where + " has status " + record.text("status") + ", not " + RunRecord.COMPLETE
					+ (record.text("reason") == null || record.text("reason").isBlank() ? ""
							: " (" + record.text("reason") + ")"));
		}
		Integer startYear = record.number("crafty", "start_year");
		Integer finalYear = record.number(RunRecord.CALIBRATION, "final_year");
		String production = record.text(RunRecord.CALIBRATION, "production");
		String plumOutput = record.text(RunRecord.CALIBRATION, "plum_output");
		String craftyConfig = record.text("crafty", "config");
		String plumConfig = record.text("plum", "config");
		if (startYear == null || finalYear == null || production == null || plumOutput == null || craftyConfig == null
				|| plumConfig == null) {
			problems.add(where + "'s " + RunRecord.FILE + " lacks crafty.start_year, crafty.config, plum.config or "
					+ "the calibration block, which a complete calibration run has");
		}
		if (!problems.isEmpty()) {
			throw new CouplingInputException(problems);
		}
		Calibration c = new Calibration(folder, startYear, finalYear, RunRecord.resolve(folder, production),
				RunRecord.resolve(folder, plumOutput), RunRecord.resolve(folder, craftyConfig),
				RunRecord.resolve(folder, plumConfig));
		problems.addAll(fileProblems(c.production(), c.plumOutput(), startYear, finalYear));
		for (Path config : List.of(c.craftyConfig(), c.plumConfig())) {
			if (!Files.isRegularFile(config)) {
				problems.add(where + " has lost its config copy " + config);
			}
		}
		if (!problems.isEmpty()) {
			throw new CouplingInputException(problems);
		}
		return c;
	}

	/**
	 * What a finished calibration leaves (48e PLAN section 5, step 5): PLUM's state files; its final year's rows,
	 * read as CRAFTY will read them; CRAFTY's start-year production; and the same countries in both (main plan D3).
	 */
	static List<String> fileProblems(Path production, Path plumOutput, int startYear, int finalYear) {
		List<String> problems = new ArrayList<>();
		for (String name : PLUM_STATE_FILES) {
			if (!Files.isRegularFile(plumOutput.resolve(name))) {
				problems.add("PLUM's calibrated state has no " + plumOutput.resolve(name));
			}
		}
		Path productionFile = production.resolve(String.valueOf(startYear)).resolve(ProductionFile.FILE);
		Path done = DoneFileWaiter.doneFile(production, startYear);
		Map<String, Map<String, Double>> crafty = null;
		if (!Files.isRegularFile(productionFile) || !Files.isRegularFile(done)) {
			problems.add("CRAFTY's " + startYear + " production isn't complete: " + productionFile + " and " + done
					+ " must both exist");
		} else {
			try {
				crafty = ProductionFile.read(productionFile);
			} catch (CouplingInputException e) {
				problems.addAll(e.getProblems());
			}
		}
		PlumYearOutputs rows = null;
		try {
			rows = PlumYearOutputs.read(plumOutput, finalYear);
		} catch (CouplingInputException e) {
			problems.add("PLUM's final calibration year " + finalYear + " can't be read from " + plumOutput + ":");
			problems.addAll(e.getProblems());
		}
		if (crafty != null && rows != null) {
			Set<String> onlyPlum = new TreeSet<>(rows.countries());
			onlyPlum.removeAll(crafty.keySet());
			Set<String> onlyCrafty = new TreeSet<>(crafty.keySet());
			onlyCrafty.removeAll(rows.countries());
			if (!onlyPlum.isEmpty()) {
				problems.add("PLUM's " + PlumYearOutputs.DOMESTIC_FILE + " for " + finalYear + " has countries "
						+ "CRAFTY's production doesn't: " + listed(onlyPlum));
			}
			if (!onlyCrafty.isEmpty()) {
				problems.add("PLUM's " + PlumYearOutputs.DOMESTIC_FILE + " for " + finalYear + " has no rows for "
						+ "CRAFTY's countries " + listed(onlyCrafty) + ". PLUM skips countries it has no agent for, "
						+ "such as those in countries_excluded.csv");
			}
		}
		return problems;
	}

	private static String listed(Set<String> names) {
		List<String> first = names.stream().limit(MAX_LISTED).toList();
		return first + (names.size() > MAX_LISTED ? " and " + (names.size() - MAX_LISTED) + " more" : "");
	}
}
