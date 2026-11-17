package de.cesr.crafty.landsymm;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

/**
 * The run's copy of PLUM's config.properties (48e PLAN section 3.2): the keys the launcher sets and the checks on the
 * base file. Values are read the way PLUM's ModelConfig reads them: java.util.Properties, where the last value of a
 * key wins, then Integer.valueOf and Boolean.valueOf with nothing trimmed.
 *
 * The file is read and written as ISO-8859-1, as Properties reads it, so every byte of the base file's own lines is
 * kept.
 */
final class PlumRunConfig {

	/** ModelConfig's default BASE_YEAR, when the file doesn't set one. */
	static final int PLUM_DEFAULT_BASE_YEAR = 2020;

	/** The keys the launcher sets; CALIB_DIR only in a scenario run. */
	static final List<String> LAUNCHER_KEYS = List.of("IS_CALIBRATION_RUN", "START_TIMESTEP", "END_TIMESTEP",
			"OUTPUT_DIR", "CRAFTY_PRODUCTION_DIR", "CALIB_DIR", "LPJG_MONITOR_TIMEOUT");

	private PlumRunConfig() {
	}

	/** A base config.properties: its text and values. */
	record Base(Path file, String text, Properties properties) {
	}

	/** Reads a base config.properties, adding to {@code problems} if it can't. */
	static Base readBase(Path file, List<String> problems) {
		try {
			byte[] bytes = Files.readAllBytes(file);
			return new Base(file, new String(bytes, StandardCharsets.ISO_8859_1), load(bytes));
		} catch (IOException | IllegalArgumentException e) {
			problems.add("Can't read PLUM's config " + file + ": " + e);
			return null;
		}
	}

	/** Properties as PLUM loads them. */
	static Properties load(String text) {
		try {
			return load(text.getBytes(StandardCharsets.ISO_8859_1));
		} catch (IOException e) {
			throw new IllegalStateException(e); // a byte array can't fail to read
		}
	}

	private static Properties load(byte[] bytes) throws IOException {
		Properties p = new Properties();
		p.load(new ByteArrayInputStream(bytes));
		return p;
	}

	/** PLUM's BASE_YEAR: the file's, or ModelConfig's default of 2020. Null, with a problem, if PLUM couldn't read it. */
	static Integer baseYear(Base base, List<String> problems) {
		String v = base.properties().getProperty("BASE_YEAR");
		if (v == null) {
			return PLUM_DEFAULT_BASE_YEAR;
		}
		try {
			return Integer.valueOf(v);
		} catch (NumberFormatException e) {
			problems.add(base.file() + ": BASE_YEAR isn't a whole number PLUM can read: '" + v + "'");
			return null;
		}
	}

	/** The base file's problems for a coupled run of this type (48e PLAN section 3.2). */
	static List<String> baseProblems(Base base, boolean calibrationRun) {
		List<String> problems = new ArrayList<>();
		Properties p = base.properties();
		String allCrafty = p.getProperty("ALL_CRAFTY_COUNTRIES");
		if (allCrafty == null || !Boolean.valueOf(allCrafty)) {
			problems.add(base.file() + " doesn't set ALL_CRAFTY_COUNTRIES=true" + (allCrafty == null ? ""
					: " (it's '" + allCrafty + "')") + ". Without it PLUM runs its own land model, not on CRAFTY's "
					+ "production");
		}
		String step = p.getProperty("TIMESTEP_SIZE");
		if (step != null) {
			try {
				if (Integer.valueOf(step) != 1) {
					problems.add(base.file() + ": TIMESTEP_SIZE must be 1, one PLUM year for each CRAFTY year, got "
							+ step);
				}
			} catch (NumberFormatException e) {
				problems.add(base.file() + ": TIMESTEP_SIZE isn't a whole number PLUM can read: '" + step + "'");
			}
		}
		String changeDemandYear = p.getProperty("CHANGE_DEMAND_YEAR");
		if (!calibrationRun && changeDemandYear != null && !Boolean.valueOf(changeDemandYear)) {
			problems.add(base.file() + " sets CHANGE_DEMAND_YEAR=" + changeDemandYear + ". PLUM would then read "
					+ "CRAFTY's BASE_YEAR production every year (CraftyProductionReader.getProductionYear)");
		}
		for (String key : new TreeSet<>(p.stringPropertyNames())) {
			if ((key.endsWith("_DIR") || key.endsWith("_FILE")) && !LAUNCHER_KEYS.contains(key)) {
				String v = p.getProperty(key);
				if (v.isBlank() || v.strip().equalsIgnoreCase("true") || v.strip().equalsIgnoreCase("false")) {
					continue; // switches named like paths: IS_1STGEN_DEMAND_FROM_FILE, DEMAND_FROM_FILE, ...
				}
				try {
					if (!Paths.get(v.strip()).isAbsolute()) {
						problems.add(base.file() + ": " + key + " is a relative path (" + v + "). PLUM runs in the "
								+ "run folder, so make it absolute");
					}
				} catch (InvalidPathException e) {
					problems.add(base.file() + ": " + key + " isn't a path: " + v);
				}
			}
		}
		return problems;
	}

	/**
	 * The keys the launcher sets in the run's copy, in the order they're written (48e PLAN section 3.2). A scenario's
	 * PLUM solves CRAFTY's last year too (48e Q3 a).
	 *
	 * @param calibration a scenario run's calibration; null in a calibration run
	 */
	static Map<String, String> values(MasterConfig master, RunFolder run, int baseYear,
			CalibrationCheck.Calibration calibration) {
		Map<String, String> v = new LinkedHashMap<>();
		v.put("IS_CALIBRATION_RUN", String.valueOf(master.calibrationRun()));
		v.put("START_TIMESTEP", "0");
		v.put("END_TIMESTEP", String.valueOf(endTimestep(master, baseYear)));
		v.put("OUTPUT_DIR", RunConfigWriter.slashes(run.plumOutput()));
		v.put("CRAFTY_PRODUCTION_DIR", RunConfigWriter.slashes(run.production()));
		if (!master.calibrationRun()) {
			v.put("CALIB_DIR", RunConfigWriter.slashes(calibration.plumOutput()));
		}
		v.put("LPJG_MONITOR_TIMEOUT", String.valueOf(master.waitTimeoutMinutes() * 60));
		return v;
	}

	/** PLUM's END_TIMESTEP: the calibration steps, or the scenario's end year less BASE_YEAR. */
	static int endTimestep(MasterConfig master, int baseYear) {
		return master.calibrationRun() ? master.calibrationSteps() : master.endYear() - baseYear;
	}

	/** The run's copy. */
	static RunConfigWriter.Copy copy(Base base, Map<String, String> values, List<String> header) {
		return RunConfigWriter.properties(base.text(), values, header, "PLUM's config " + base.file());
	}
}
