package de.cesr.crafty.landsymm;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import de.cesr.crafty.core.cli.ConfigLoader;

/**
 * A coupled run's master YAML (48e PLAN section 1): what describes the run and what CRAFTY and PLUM must agree on.
 * Every detailed choice stays in the CRAFTY config.yaml and PLUM config.properties it names, which the launcher
 * copies but never edits.
 *
 * Relative paths are relative to the master YAML's folder. Every problem is collected, then
 * {@link #read(Path)} stops with them all.
 *
 * @param file                 the master YAML
 * @param runName              the run folder's name
 * @param outputRoot           the folder the run folder goes in
 * @param calibrationRun       true for a calibration run
 * @param calibrationRunFolder a scenario run's calibration run; null in a calibration run
 * @param endYear              a scenario run's last CRAFTY year (coupled_end_year); 0 in a calibration run
 * @param waitTimeoutMinutes   how long each model waits for the other's done file
 * @param coupling             the coupler's settings, under their config.yaml names, as given (48e Q1 a)
 * @param outputs              the coupler's yearly files to write (plum_coupling_outputs names), in core's order
 * @param craftyConfig         the CRAFTY config.yaml the run's copy is made from
 * @param craftyHeap           CRAFTY's -Xmx
 * @param craftyJvmArgs        extra JVM arguments for CRAFTY
 * @param craftyJar            the coupler jar to run CRAFTY from; null = the jar the launcher runs from
 * @param plumConfig           the PLUM config.properties the run's copy is made from
 * @param plumClasses          PLUM's build folder
 * @param gamsDir              the GAMS folder, holding apifiles/Java/api/GAMSJavaAPI.jar
 * @param plumHeap             PLUM's -Xmx
 * @param plumJvmArgs          extra JVM arguments for PLUM
 * @param calibrationSteps     a calibration run's PLUM END_TIMESTEP; 0 in a scenario run
 */
record MasterConfig(Path file, String runName, Path outputRoot, boolean calibrationRun, Path calibrationRunFolder,
		int endYear, int waitTimeoutMinutes, Map<String, Object> coupling, List<String> outputs, Path craftyConfig,
		String craftyHeap, List<String> craftyJvmArgs, Path craftyJar, Path plumConfig, Path plumClasses, Path gamsDir,
		String plumHeap, List<String> plumJvmArgs, int calibrationSteps) {

	static final int DEFAULT_WAIT_TIMEOUT_MINUTES = 120;

	/**
	 * The coupler's settings the coupling block can hold, under their config.yaml names, with the type each must
	 * have. These, and plum_coupling_outputs (from the outputs block), are set only from the master YAML: a base
	 * config.yaml that sets one stops the run (48e Q1 a).
	 */
	static final Map<String, Class<?>> COUPLING_KEYS = couplingKeys();

	private static final Set<String> TOP_KEYS = Set.of("run_name", "output_root", "is_calibration_run",
			"calibration_run", "end_year", "wait_timeout_minutes", "coupling", "outputs", "crafty", "plum");
	private static final Set<String> CRAFTY_KEYS = Set.of("config", "heap", "jvm_args", "jar");
	private static final Set<String> PLUM_KEYS = Set.of("config", "classes", "gams_dir", "heap", "jvm_args",
			"calibration_steps");
	private static final Pattern RUN_NAME = Pattern.compile("[A-Za-z0-9_.-]+");
	private static final Pattern HEAP = Pattern.compile("[1-9][0-9]*[kKmMgG]?");
	private static final List<String> LAUNCHER_JVM_ARGS = List.of("-Xmx", "-cp", "-classpath", "--class-path", "-jar");

	private static Map<String, Class<?>> couplingKeys() {
		Map<String, Class<?>> keys = new LinkedHashMap<>();
		keys.put("plum_coupling_mode", String.class);
		keys.put("plum_price_scaling", String.class);
		keys.put("plum_price_basis", String.class);
		keys.put("initialise_regional_price_weights", Boolean.class);
		keys.put("plum_consumption", Boolean.class);
		keys.put("dynamic_trade_capitals", Boolean.class);
		keys.put("trade_capital_slope", Number.class);
		keys.put("trade_capital_midpoint", Number.class);
		keys.put("trade_capital_averaging_years", Integer.class);
		keys.put("dynamic_nes_prices", Boolean.class);
		keys.put("dynamic_biodiversity_price", Boolean.class);
		keys.put("biodiversity_metric", String.class);
		keys.put("biodiversity_allocation_capital", String.class);
		return Collections.unmodifiableMap(keys);
	}

	/** Reads and checks a master YAML, stopping with every problem found. */
	static MasterConfig read(Path file) {
		Object raw;
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
		} catch (IOException e) {
			throw new CouplingInputException("Can't read the master YAML " + file + ": " + e);
		} catch (YAMLException e) {
			throw new CouplingInputException("The master YAML " + file + " isn't valid YAML: " + e.getMessage());
		}
		if (!(raw instanceof Map<?, ?> root)) {
			throw new CouplingInputException("The master YAML " + file + " must be a YAML mapping of keys to values");
		}
		Reading r = new Reading(file.toAbsolutePath().getParent());
		r.unknownKeys(root, TOP_KEYS, "");

		String runName = r.text(root, "run_name", "", true);
		if (runName != null && (!RUN_NAME.matcher(runName).matches() || runName.equals(".") || runName.equals(".."))) {
			r.problems.add("run_name must be a folder name of letters, digits, _, - and . only, got: " + runName);
		}
		Path outputRoot = r.path(root, "output_root", "", true);
		Boolean calibrationRun = r.bool(root, "is_calibration_run", "", true);
		Integer timeout = r.integer(root, "wait_timeout_minutes", "", false);
		if (timeout != null && timeout < 1) {
			r.problems.add("wait_timeout_minutes must be 1 or more, got: " + timeout);
		}

		Path calibrationRunFolder = null;
		Integer endYear = null;
		Map<String, Object> coupling = new LinkedHashMap<>();
		if (Boolean.TRUE.equals(calibrationRun)) {
			for (String key : List.of("calibration_run", "end_year", "coupling")) {
				if (root.containsKey(key)) {
					r.problems.add(key + " is only for a scenario run: a calibration run covers CRAFTY's start year "
							+ "only, then PLUM's calibration");
				}
			}
		} else if (Boolean.FALSE.equals(calibrationRun)) {
			calibrationRunFolder = r.path(root, "calibration_run", "", true);
			endYear = r.integer(root, "end_year", "", true);
			coupling = r.coupling(root);
		}
		List<String> outputs = r.outputs(root);

		Map<?, ?> crafty = r.section(root, "crafty");
		Path craftyConfig = null;
		String craftyHeap = null;
		List<String> craftyJvmArgs = List.of();
		Path craftyJar = null;
		if (crafty != null) {
			r.unknownKeys(crafty, CRAFTY_KEYS, "crafty.");
			craftyConfig = r.path(crafty, "config", "crafty.", true);
			craftyHeap = r.heap(crafty, "crafty.");
			craftyJvmArgs = r.jvmArgs(crafty, "crafty.");
			craftyJar = r.path(crafty, "jar", "crafty.", false);
		}

		Map<?, ?> plum = r.section(root, "plum");
		Path plumConfig = null;
		Path plumClasses = null;
		Path gamsDir = null;
		String plumHeap = null;
		List<String> plumJvmArgs = List.of();
		Integer steps = null;
		if (plum != null) {
			r.unknownKeys(plum, PLUM_KEYS, "plum.");
			plumConfig = r.path(plum, "config", "plum.", true);
			plumClasses = r.path(plum, "classes", "plum.", true);
			gamsDir = r.path(plum, "gams_dir", "plum.", true);
			plumHeap = r.heap(plum, "plum.");
			plumJvmArgs = r.jvmArgs(plum, "plum.");
			if (Boolean.TRUE.equals(calibrationRun)) {
				steps = r.integer(plum, "calibration_steps", "plum.", true);
				if (steps != null && steps < 1) {
					r.problems.add("plum.calibration_steps must be 1 or more (PLUM's END_TIMESTEP), got: " + steps);
				}
			} else if (plum.containsKey("calibration_steps")) {
				r.problems.add("plum.calibration_steps is only for a calibration run. A scenario run's PLUM years "
						+ "follow end_year");
			}
		}

		if (!r.problems.isEmpty()) {
			List<String> problems = new ArrayList<>();
			problems.add("The master YAML " + file + " has " + r.problems.size() + " problem(s):");
			problems.addAll(r.problems);
			throw new CouplingInputException(problems);
		}
		return new MasterConfig(file.toAbsolutePath(), runName, outputRoot, calibrationRun, calibrationRunFolder,
				endYear == null ? 0 : endYear, timeout == null ? DEFAULT_WAIT_TIMEOUT_MINUTES : timeout,
				Collections.unmodifiableMap(coupling), outputs, craftyConfig, craftyHeap, craftyJvmArgs, craftyJar,
				plumConfig, plumClasses, gamsDir, plumHeap, plumJvmArgs, steps == null ? 0 : steps);
	}

	/** The run folder: output_root/run_name. */
	Path runFolder() {
		return outputRoot.resolve(runName);
	}

	/** The GAMS Java API jar PLUM needs on its class path, as in PLUM's runPlum.sh. */
	Path gamsJar() {
		return gamsDir.resolve("apifiles").resolve("Java").resolve("api").resolve("GAMSJavaAPI.jar");
	}

	/** The coupler's yearly files that are switched off, in core's order. */
	List<String> outputsOff() {
		List<String> off = new ArrayList<>(ConfigLoader.PLUM_COUPLING_OUTPUTS);
		off.removeAll(outputs);
		return off;
	}

	/** The values read so far and the problems found. */
	private static final class Reading {
		private final Path base;
		private final List<String> problems = new ArrayList<>();

		Reading(Path base) {
			this.base = base;
		}

		void unknownKeys(Map<?, ?> map, Set<String> known, String prefix) {
			Set<String> unknown = new TreeSet<>();
			for (Object key : map.keySet()) {
				if (!known.contains(String.valueOf(key))) {
					unknown.add(prefix + key);
				}
			}
			if (!unknown.isEmpty()) {
				problems.add("Unknown key(s) " + unknown + ". Check the spelling against the master YAML keys in "
						+ "the 48e PLAN, section 1");
			}
		}

		private Object value(Map<?, ?> map, String key, String prefix, boolean required) {
			Object value = map.get(key);
			if (value == null && required) {
				problems.add(prefix + key + " is missing");
			}
			return value;
		}

		String text(Map<?, ?> map, String key, String prefix, boolean required) {
			Object value = value(map, key, prefix, required);
			if (value == null) {
				return null;
			}
			if (value instanceof Map || value instanceof List) {
				problems.add(prefix + key + " must be a single value, got: " + value);
				return null;
			}
			String text = String.valueOf(value).strip();
			if (text.isEmpty()) {
				problems.add(prefix + key + " is empty");
				return null;
			}
			return text;
		}

		Path path(Map<?, ?> map, String key, String prefix, boolean required) {
			String text = text(map, key, prefix, required);
			if (text == null) {
				return null;
			}
			try {
				return base.resolve(Paths.get(text)).toAbsolutePath().normalize();
			} catch (InvalidPathException e) {
				problems.add(prefix + key + " isn't a path: " + text);
				return null;
			}
		}

		Boolean bool(Map<?, ?> map, String key, String prefix, boolean required) {
			Object value = value(map, key, prefix, required);
			if (value == null) {
				return null;
			}
			if (!(value instanceof Boolean b)) {
				problems.add(prefix + key + " must be true or false, got: " + value);
				return null;
			}
			return b;
		}

		Integer integer(Map<?, ?> map, String key, String prefix, boolean required) {
			Object value = value(map, key, prefix, required);
			if (value == null) {
				return null;
			}
			if (!(value instanceof Integer i)) {
				problems.add(prefix + key + " must be a whole number, got: " + value);
				return null;
			}
			return i;
		}

		Map<?, ?> section(Map<?, ?> map, String key) {
			Object value = value(map, key, "", true);
			if (value == null) {
				return null;
			}
			if (!(value instanceof Map<?, ?> section)) {
				problems.add(key + " must be a block of keys, got: " + value);
				return null;
			}
			return section;
		}

		String heap(Map<?, ?> map, String prefix) {
			String heap = text(map, "heap", prefix, true);
			if (heap != null && !HEAP.matcher(heap).matches()) {
				problems.add(prefix + "heap must be a size as for -Xmx, e.g. 16g or 3000m, got: " + heap);
				return null;
			}
			return heap;
		}

		List<String> jvmArgs(Map<?, ?> map, String prefix) {
			Object value = map.get("jvm_args");
			if (value == null) {
				return List.of();
			}
			if (!(value instanceof List<?> list)) {
				problems.add(prefix + "jvm_args must be a list, e.g. [\"-Xlog:gc:file=gc.log\"], got: " + value);
				return List.of();
			}
			List<String> args = new ArrayList<>();
			for (Object arg : list) {
				String text = String.valueOf(arg).strip();
				for (String owned : LAUNCHER_JVM_ARGS) {
					if (text.equals(owned) || (owned.equals("-Xmx") && text.startsWith(owned))) {
						problems.add(prefix + "jvm_args can't hold " + text + ": the launcher sets the heap (from "
								+ prefix + "heap) and the class path");
					}
				}
				args.add(text);
			}
			return List.copyOf(args);
		}

		Map<String, Object> coupling(Map<?, ?> root) {
			Map<String, Object> coupling = new LinkedHashMap<>();
			Object value = root.get("coupling");
			if (value == null) {
				return coupling;
			}
			if (!(value instanceof Map<?, ?> block)) {
				problems.add("coupling must be a block of the coupler's settings, got: " + value);
				return coupling;
			}
			for (Map.Entry<?, ?> e : block.entrySet()) {
				String key = String.valueOf(e.getKey());
				Object v = e.getValue();
				Class<?> type = COUPLING_KEYS.get(key);
				if (type == null) {
					problems.add("coupling." + key + " isn't one of the coupler's settings " + COUPLING_KEYS.keySet()
							+ (key.equals("plum_coupling_outputs") ? ". The files to write go in the outputs block" : ""));
					continue;
				}
				if (!type.isInstance(v)) {
					problems.add("coupling." + key + " must be " + describe(type) + ", got: " + v);
					continue;
				}
				String valueProblem = valueProblem(key, v);
				if (valueProblem != null) {
					problems.add("coupling." + key + " " + valueProblem);
					continue;
				}
				coupling.put(key, v);
			}
			return coupling;
		}

		/** core's rules for the values (ConfigLoader.plumCouplingConfigError), checked here so they're listed together */
		private static String valueProblem(String key, Object v) {
			List<String> allowed = switch (key) {
			case "plum_coupling_mode" -> ConfigLoader.PLUM_COUPLING_MODES;
			case "plum_price_scaling" -> ConfigLoader.PLUM_PRICE_SCALINGS;
			case "plum_price_basis" -> ConfigLoader.PLUM_PRICE_BASES;
			case "biodiversity_metric" -> ConfigLoader.NES_METRICS;
			default -> null;
			};
			if (allowed != null) {
				return allowed.contains(v) ? null : "must be one of " + allowed + ", got: " + v;
			}
			if (key.equals("biodiversity_allocation_capital") && String.valueOf(v).isBlank()) {
				return "must name the capital the biodiversity metric is written into, e.g. biodiversity_suit";
			}
			if (v instanceof Number n) {
				double d = n.doubleValue();
				if (key.equals("trade_capital_slope") && !(Double.isFinite(d) && d > 0)) {
					return "must be a number more than 0, got: " + v;
				}
				if (key.equals("trade_capital_midpoint") && !(Double.isFinite(d) && d >= 0)) {
					return "must be a number of 0 or more, got: " + v;
				}
				if (key.equals("trade_capital_averaging_years") && d < 1) {
					return "must be 1 (no averaging) or more, got: " + v;
				}
			}
			return null;
		}

		private static String describe(Class<?> type) {
			if (type == Boolean.class) {
				return "true or false";
			}
			if (type == Integer.class) {
				return "a whole number";
			}
			if (type == Number.class) {
				return "a number";
			}
			return "a word";
		}

		List<String> outputs(Map<?, ?> root) {
			List<String> on = new ArrayList<>(ConfigLoader.PLUM_COUPLING_OUTPUTS);
			Object value = root.get("outputs");
			if (value == null) {
				return List.copyOf(on);
			}
			if (!(value instanceof Map<?, ?> block)) {
				problems.add("outputs must be a block of the coupler's files with true or false, e.g. "
						+ "export_basis: false, got: " + value);
				return List.copyOf(on);
			}
			for (Map.Entry<?, ?> e : block.entrySet()) {
				String name = String.valueOf(e.getKey());
				if (!ConfigLoader.PLUM_COUPLING_OUTPUTS.contains(name)) {
					problems.add("outputs." + name + " isn't one of the coupler's yearly files "
							+ ConfigLoader.PLUM_COUPLING_OUTPUTS
							+ ". price_factors.csv and baseline_check.csv are always written");
				} else if (!(e.getValue() instanceof Boolean b)) {
					problems.add("outputs." + name + " must be true or false, got: " + e.getValue());
				} else if (!b) {
					on.remove(name);
				}
			}
			return List.copyOf(on);
		}
	}
}
