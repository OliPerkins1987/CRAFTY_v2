package de.cesr.crafty.landsymm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import de.cesr.crafty.core.cli.Config;
import de.cesr.crafty.core.cli.ConfigLoader;

/**
 * The run's copy of CRAFTY's config.yaml (48e PLAN section 3.1): the keys the launcher sets, the checks on the base
 * file, and the check of the copy with core's own loader and the coupler's start-up check.
 */
final class CraftyRunConfig {

	/**
	 * CRAFTY's own market switches, which a coupled run needs off. The launcher turns them off in the run's copy, so
	 * the same config.yaml serves standalone runs (48e Q2 a).
	 */
	static final List<String> MARKET_SWITCHES = List.of("use_price_feedback", "global_price_feedback",
			"initial_demand_supply_equilibrium");

	/**
	 * Every key the launcher can set in the run's copy, apart from the coupling block's (48e PLAN section 3.1). The
	 * base file may set these: its lines are commented out and replaced.
	 */
	static final List<String> RUN_KEYS = List.of("coupled_with_plum", "plum_calibration_run", "crafty_production_path",
			"output_path", "output_folder_name", "use_price_feedback", "global_price_feedback",
			"initial_demand_supply_equilibrium", "plum_coupling_outputs", "plum_calibration_path",
			"plum_calibration_final_year", "calibration_production_path", "plum_output_path", "coupled_end_year",
			"plum_wait_timeout_minutes");

	/**
	 * Old names core still reads for keys the launcher sets (ConfigLoader's LEGACY_KEYS). Their lines are commented
	 * out too, so the copy doesn't hold two values for one key.
	 */
	static final Map<String, String> OLD_NAMES = Map.of("Output_path", "output_path", "COUPLED_WITH_PLUM",
			"coupled_with_plum", "plumCalibPath", "plum_calibration_path", "plumOutPutPath", "plum_output_path");

	private CraftyRunConfig() {
	}

	/** A base config.yaml: its text and its top-level values. */
	record Base(Path file, String text, Map<String, Object> values) {
	}

	/** CRAFTY's scenario and its years, from the project's scenarios.csv (core's ProjectLoader reads the same file). */
	record Scenario(String name, int startYear, int endYear) {
	}

	/** What core's loader and the coupler's start-up check said about the run's copy. */
	record CoreCheck(List<String> problems, List<String> warnings, List<String> notes) {
	}

	/** Reads a base config.yaml, adding to {@code problems} if it can't. */
	static Base readBase(Path file, List<String> problems) {
		String text;
		try {
			text = Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			problems.add("Can't read CRAFTY's config " + file + ": " + e);
			return null;
		}
		try {
			return new Base(file, text, parse(text));
		} catch (CouplingInputException e) {
			problems.add("CRAFTY's config " + file + ": " + e.getMessage());
			return null;
		}
	}

	/** A config.yaml's top-level values; empty for an empty file. */
	static Map<String, Object> parse(String text) {
		Object raw;
		try {
			raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
		} catch (YAMLException e) {
			throw new CouplingInputException("it isn't valid YAML: " + e.getMessage());
		}
		if (raw == null) {
			return new LinkedHashMap<>();
		}
		if (!(raw instanceof Map<?, ?> map)) {
			throw new CouplingInputException("it must be a YAML mapping of keys to values");
		}
		Map<String, Object> values = new LinkedHashMap<>();
		map.forEach((k, v) -> values.put(String.valueOf(k), v));
		return values;
	}

	/**
	 * The base file's problems: a coupler setting, which belongs in the master YAML (48e Q1 a); no project_path; a
	 * relative path, which wouldn't be found once CRAFTY runs in the run folder.
	 */
	static List<String> baseProblems(Base base) {
		List<String> problems = new ArrayList<>();
		List<String> couplerKeys = new ArrayList<>(MasterConfig.COUPLING_KEYS.keySet());
		couplerKeys.add("plum_coupling_outputs");
		for (String key : couplerKeys) {
			if (base.values().containsKey(key)) {
				problems.add(base.file() + " sets " + key + ". A coupled run's coupler settings come only from the "
						+ "master YAML (its " + (key.equals("plum_coupling_outputs") ? "outputs" : "coupling")
						+ " block), so take it out of config.yaml (48e Q1 a)");
			}
		}
		Object project = base.values().get("project_path");
		if (project == null || String.valueOf(project).isBlank()) {
			problems.add(base.file() + " doesn't set project_path");
		}
		base.values().forEach((key, value) -> {
			if ((key.endsWith("_path") || key.endsWith("_directory")) && !RUN_KEYS.contains(key)
					&& !OLD_NAMES.containsKey(key)
					&& value instanceof String text && !text.isBlank()) {
				try {
					if (!Paths.get(text).isAbsolute()) {
						problems.add(base.file() + ": " + key + " is a relative path (" + text + "). CRAFTY runs in "
								+ "the run folder, so make it absolute");
					}
				} catch (InvalidPathException e) {
					problems.add(base.file() + ": " + key + " isn't a path: " + text);
				}
			}
		});
		return problems;
	}

	/**
	 * CRAFTY's scenario and years, from scenarios.csv in the project's metadata folder (metadata_directory, or a
	 * csv or metaData folder in the project). Unlike core, which falls back to the first scenario with a warning, a
	 * scenario that isn't in the file is a problem.
	 */
	static Scenario scenario(Base base, List<String> problems) {
		Object project = base.values().get("project_path");
		if (project == null || String.valueOf(project).isBlank()) {
			return null; // reported by baseProblems
		}
		Path folder = metadataFolder(base.values(), Paths.get(String.valueOf(project)));
		if (folder == null) {
			problems.add("No metadata folder (csv or metaData) in CRAFTY's project " + project);
			return null;
		}
		Path file;
		try (Stream<Path> files = Files.list(folder)) {
			file = files.filter(f -> f.getFileName().toString().toLowerCase().contains("scenarios")
					&& f.getFileName().toString().toLowerCase().endsWith(".csv")).findFirst().orElse(null);
		} catch (IOException e) {
			problems.add("Can't list " + folder + ": " + e);
			return null;
		}
		if (file == null) {
			problems.add("No scenarios.csv in " + folder);
			return null;
		}
		Map<String, int[]> years = new LinkedHashMap<>();
		try {
			CsvReader.forEachRow(file, List.of("Name", "startYear", "endtYear"), (line, v) -> {
				try {
					years.put(v[0].strip(), new int[] { (int) Double.parseDouble(v[1].strip()),
							(int) Double.parseDouble(v[2].strip()) });
				} catch (NumberFormatException e) {
					problems.add(file + ", line " + line + ": the years aren't numbers");
				}
			});
		} catch (CouplingInputException e) {
			problems.addAll(e.getProblems());
			return null;
		}
		if (years.isEmpty()) {
			problems.add(file + " has no scenarios");
			return null;
		}
		Object named = base.values().get("scenario");
		String name = named == null || String.valueOf(named).isBlank() ? years.keySet().iterator().next()
				: String.valueOf(named).strip();
		int[] y = years.get(name);
		if (y == null) {
			problems.add(base.file() + " sets scenario " + name + ", which isn't in " + file + " " + years.keySet());
			return null;
		}
		return new Scenario(name, y[0], y[1]);
	}

	private static Path metadataFolder(Map<String, Object> values, Path project) {
		Object metadata = values.get("metadata_directory");
		if (metadata != null && !String.valueOf(metadata).isBlank()
				&& Files.isDirectory(Paths.get(String.valueOf(metadata)))) {
			return Paths.get(String.valueOf(metadata));
		}
		try (Stream<Path> folders = Files.list(project)) {
			return folders.filter(Files::isDirectory).filter(f -> {
				String n = f.getFileName().toString();
				return n.equalsIgnoreCase("csv") || n.equalsIgnoreCase("metaData");
			}).sorted().findFirst().orElse(null);
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * The keys the launcher sets in the run's copy, in the order they're written (48e PLAN section 3.1).
	 *
	 * @param calibration a scenario run's calibration; null in a calibration run
	 */
	static Map<String, Object> values(MasterConfig master, RunFolder run, CalibrationCheck.Calibration calibration) {
		Map<String, Object> v = new LinkedHashMap<>();
		v.put("coupled_with_plum", true);
		v.put("plum_calibration_run", master.calibrationRun());
		v.put("crafty_production_path", RunConfigWriter.slashes(run.production()));
		v.put("output_path", RunConfigWriter.slashes(run.craftyOutput()));
		v.put("output_folder_name", master.runName());
		for (String key : MARKET_SWITCHES) {
			v.put(key, false);
		}
		v.put("plum_coupling_outputs", master.outputs());
		if (!master.calibrationRun()) {
			v.put("plum_calibration_path", RunConfigWriter.slashes(calibration.plumOutput()));
			v.put("plum_calibration_final_year", calibration.finalYear());
			v.put("calibration_production_path", RunConfigWriter.slashes(calibration.production()));
			v.put("plum_output_path", RunConfigWriter.slashes(run.plumOutput()));
			v.put("coupled_end_year", master.endYear());
			v.put("plum_wait_timeout_minutes", master.waitTimeoutMinutes());
			v.putAll(master.coupling());
		}
		return v;
	}

	/** The market switches the base file has on, which the copy turns off. */
	static List<String> switchesTurnedOff(Base base) {
		List<String> on = new ArrayList<>();
		for (String key : MARKET_SWITCHES) {
			if (Boolean.TRUE.equals(base.values().get(key))
					|| (key.equals("initial_demand_supply_equilibrium") && !base.values().containsKey(key))) {
				on.add(key); // initial_demand_supply_equilibrium is on in core when not set
			}
		}
		return on;
	}

	/** The run's copy. */
	static RunConfigWriter.Copy copy(Base base, Map<String, Object> values, List<String> header) {
		Map<String, String> text = new LinkedHashMap<>();
		values.forEach((k, v) -> text.put(k, RunConfigWriter.yamlValue(v)));
		return RunConfigWriter.yaml(base.text(), text, OLD_NAMES, header, "CRAFTY's config " + base.file());
	}

	/**
	 * Loads the run's copy with core's own ConfigLoader, as CRAFTY will, checks that every key the launcher set came
	 * through, and runs the coupler's start-up check on it. Core's own value checks stop the JVM, so the launcher's
	 * values are checked before this. What core prints is held back, except its [Config] notes. ConfigLoader's state
	 * is put back afterwards.
	 */
	static CoreCheck checkWithCore(Path copyFile, Map<String, Object> values) {
		List<String> problems = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		List<String> notes = new ArrayList<>();
		String oldPath = ConfigLoader.configPath;
		Config oldConfig = ConfigLoader.config;
		PrintStream console = System.out;
		ByteArrayOutputStream printed = new ByteArrayOutputStream();
		try {
			ConfigLoader.PLUM_COUPLING_MODES.size(); // starts core's logging on the real console first
			System.setOut(new PrintStream(printed, true, StandardCharsets.UTF_8));
			ConfigLoader.configPath = copyFile.toString();
			try {
				ConfigLoader.init();
			} catch (IllegalArgumentException e) {
				problems.add("CRAFTY's config loader rejects the run's config.yaml (" + copyFile + "): "
						+ e.getMessage());
			} finally {
				System.setOut(console);
			}
			String output = printed.toString(StandardCharsets.UTF_8);
			output.lines().filter(l -> l.startsWith("[Config]")).forEach(notes::add);
			if (output.contains("Using default config values")) {
				problems.add("CRAFTY's config loader couldn't read the run's config.yaml (" + copyFile
						+ ") and fell back to its defaults. Its output: "
						+ output.lines().filter(l -> !l.startsWith("loadedConfig") && !l.startsWith("|->")).limit(10)
								.toList());
			}
			if (problems.isEmpty() && ConfigLoader.config != null) {
				Config loaded = ConfigLoader.config;
				values.forEach((key, expected) -> {
					Object actual = fieldValue(loaded, key);
					if (!same(expected, actual)) {
						problems.add("CRAFTY's config loader reads " + key + " in the run's config.yaml as " + actual
								+ ", not the launcher's " + expected);
					}
				});
				CouplingStartupCheck.Report report = CouplingStartupCheck.configReport(loaded);
				problems.addAll(report.problems());
				warnings.addAll(report.warnings());
			}
		} finally {
			System.setOut(console);
			ConfigLoader.configPath = oldPath;
			ConfigLoader.config = oldConfig;
		}
		return new CoreCheck(problems, warnings, notes);
	}

	private static Object fieldValue(Config config, String key) {
		try {
			Field field = Config.class.getField(key);
			return field.get(config);
		} catch (ReflectiveOperationException e) {
			return "(no such key in Config)";
		}
	}

	/** Whether a loaded value is the value the launcher wrote. */
	static boolean same(Object expected, Object actual) {
		if (expected instanceof Number e && actual instanceof Number a) {
			return Double.compare(e.doubleValue(), a.doubleValue()) == 0;
		}
		if (expected instanceof List<?> e && actual instanceof List<?> a) {
			return e.stream().map(String::valueOf).toList().equals(a.stream().map(String::valueOf).toList());
		}
		if (expected instanceof Boolean || expected instanceof String) {
			return expected.equals(actual);
		}
		return String.valueOf(expected).equals(String.valueOf(actual));
	}
}
