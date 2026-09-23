package de.cesr.crafty.react.data;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import de.cesr.crafty.core.cli.CustomLogger;

/**
 * Reads {@code <project_path>/AFTs/react/react_config.yaml} into a {@link ReactConfig}.
 *
 * Only react knows about this file; core's config.yaml has no key for it. Every key is optional, and a
 * missing file means "all defaults". Unknown keys, repeated keys, values of the wrong kind and values
 * out of range all stop the run, and every problem is reported in one message. The settings in use are
 * logged, so a run's log shows what came from the file and what was a default.
 */
public final class ReactConfigLoader {

	/** Where the file lives, relative to the project folder. */
	public static final Path LOCATION = Path.of("AFTs", "react", "react_config.yaml");

	private static final CustomLogger LOGGER = new CustomLogger(ReactConfigLoader.class);

	private ReactConfigLoader() {
	}

	/** The project's react settings: the file if there is one, otherwise the defaults. */
	public static ReactConfig load(Path projectPath) {
		Path file = projectPath.resolve(LOCATION);
		ReactConfig config;
		if (Files.exists(file)) {
			config = read(file);
			LOGGER.info("CRAFTY-react settings from " + file + ":\n" + config);
		} else {
			config = ReactConfig.defaults();
			LOGGER.info("No " + file + "; CRAFTY-react uses its default settings:\n" + config);
		}
		return config;
	}

	/** Reads one file. */
	static ReactConfig read(Path file) {
		Object document;
		LoaderOptions options = new LoaderOptions();
		options.setAllowDuplicateKeys(false);
		try (InputStream input = Files.newInputStream(file)) {
			document = new Yaml(new SafeConstructor(options)).load(input);
		} catch (IOException | YAMLException e) {
			throw new ReactInputException(file + " could not be read: " + e.getMessage(), e);
		}

		ReactConfig config = ReactConfig.defaults();
		List<String> problems = new ArrayList<>();
		if (document != null) {
			if (!(document instanceof Map<?, ?> map)) {
				throw new ReactInputException(file + " must hold key: value settings");
			}
			bind(new Section("", map, problems), config);
		}
		problems.addAll(config.problems());
		if (!problems.isEmpty()) {
			throw new ReactInputException(file + " has problems:\n  - " + String.join("\n  - ", problems));
		}
		return config;
	}

	private static void bind(Section root, ReactConfig c) {
		Section inputs = root.section("inputs");
		c.parameters = inputs.text("parameters", c.parameters);
		c.baseCosts = inputs.text("base_costs", c.baseCosts);
		c.cellKey = inputs.text("cell_key", c.cellKey);
		c.irrigationCost = inputs.text("irrigation_cost", c.irrigationCost);
		c.suitabilities = inputs.text("suitabilities", c.suitabilities);
		c.capitals = inputs.text("capitals", c.capitals);
		c.irrigationWaterDemand = inputs.text("irrigation_demand", c.irrigationWaterDemand);
		c.runoff = inputs.text("runoff", c.runoff);
		c.yieldFileUnits = inputs.choice("yield_file_units", c.yieldFileUnits, ReactConfig.YieldUnits.values());
		c.irrigationFileUnits = inputs.choice("irrigation_file_units", c.irrigationFileUnits,
				ReactConfig.WaterUnits.values());
		inputs.rejectUnknownKeys();

		c.spinupIterations = root.wholeNumber("spinup_iterations", c.spinupIterations);
		c.nMaxFactor = root.number("n_max_factor", c.nMaxFactor);
		c.nAdjustmentScale = root.number("n_adjustment_scale", c.nAdjustmentScale);

		Section prospect = root.section("prospect");
		c.prospectAlpha = prospect.number("alpha", c.prospectAlpha);
		c.prospectBeta = prospect.number("beta", c.prospectBeta);
		c.prospectLambda = prospect.number("lambda", c.prospectLambda);
		prospect.rejectUnknownKeys();

		Section stocking = root.section("stocking");
		c.stockingInitial = stocking.number("initial", c.stockingInitial);
		c.stockingStep = stocking.number("step", c.stockingStep);
		c.stockingMin = stocking.number("min", c.stockingMin);
		c.stockingMax = stocking.number("max", c.stockingMax);
		c.stockingHarvest = stocking.number("harvest", c.stockingHarvest);
		stocking.rejectUnknownKeys();

		c.yieldTechChange = root.number("yield_tech_change", c.yieldTechChange);

		root.rejectUnknownKeys();
	}

	/**
	 * One level of the file. Each lookup records the key as known, so that
	 * {@link #rejectUnknownKeys()} can report everything else. A wrong value is recorded as a problem
	 * and the default is kept, so that one message can list every problem.
	 */
	private static final class Section {
		private final String prefix;
		private final Map<?, ?> values;
		private final List<String> problems;
		private final Set<String> known = new LinkedHashSet<>();

		Section(String prefix, Map<?, ?> values, List<String> problems) {
			this.prefix = prefix;
			this.values = values;
			this.problems = problems;
		}

		Section section(String key) {
			known.add(key);
			Object value = values.get(key);
			if (value == null) {
				return new Section(prefix + key + ".", Map.of(), problems);
			}
			if (!(value instanceof Map<?, ?> map)) {
				problems.add(prefix + key + " must hold key: value settings");
				return new Section(prefix + key + ".", Map.of(), problems);
			}
			return new Section(prefix + key + ".", map, problems);
		}

		String text(String key, String current) {
			Object value = lookup(key);
			if (value == null) {
				return current;
			}
			if (!(value instanceof String text)) {
				problems.add(prefix + key + " must be text");
				return current;
			}
			return text.trim();
		}

		double number(String key, double current) {
			Object value = lookup(key);
			if (value == null) {
				return current;
			}
			if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) {
				problems.add(prefix + key + " must be a number");
				return current;
			}
			return n.doubleValue();
		}

		int wholeNumber(String key, int current) {
			Object value = lookup(key);
			if (value == null) {
				return current;
			}
			if (!(value instanceof Integer n)) {
				problems.add(prefix + key + " must be a whole number");
				return current;
			}
			return n;
		}

		<E extends Enum<E>> E choice(String key, E current, E[] options) {
			Object value = lookup(key);
			if (value == null) {
				return current;
			}
			for (E option : options) {
				if (option.toString().equals(value)) {
					return option;
				}
			}
			problems.add(prefix + key + " must be one of " + Arrays.toString(options));
			return current;
		}

		void rejectUnknownKeys() {
			for (Object key : values.keySet()) {
				if (!known.contains(String.valueOf(key))) {
					problems.add("unknown setting " + prefix + key);
				}
			}
		}

		private Object lookup(String key) {
			known.add(key);
			return values.get(key);
		}
	}
}
