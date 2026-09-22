package de.cesr.crafty.react.data;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CRAFTY-react's own settings, from {@code AFTs/react/react_config.yaml} (see {@link ReactConfigLoader}).
 * Every setting has a default, so a project needs the file only to change something.
 *
 * File locations are templates relative to the project folder. Placeholders:
 * <ul>
 * <li>{@code {scenario}}: the run's scenario;</li>
 * <li>{@code {year}}: the model year (named per-year files only);</li>
 * <li>{@code {type}}: a service's {@link LpjgType} label (the suitabilities folder only).</li>
 * </ul>
 * Suitabilities and capitals are year folders, searched with {@link YearFileFinder}; irrigation demand
 * and runoff share a folder, so they are named per-year files.
 */
public final class ReactConfig {

	/** Units of the crop yield and pasture NPP files, and the factor that converts them to t/ha. */
	public enum YieldUnits {
		KG_PER_M2("kg_per_m2", 10), T_PER_HA("t_per_ha", 1);

		private final String label;
		private final double toTonnesPerHectare;

		YieldUnits(String label, double toTonnesPerHectare) {
			this.label = label;
			this.toTonnesPerHectare = toTonnesPerHectare;
		}

		public double toTonnesPerHectare() {
			return toTonnesPerHectare;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	/** Units of the irrigation demand and runoff files, and the factor that converts them to m³/ha. */
	public enum WaterUnits {
		MM("mm", 10), M3_PER_HA("m3_per_ha", 1);

		private final String label;
		private final double toCubicMetresPerHectare;

		WaterUnits(String label, double toCubicMetresPerHectare) {
			this.label = label;
			this.toCubicMetresPerHectare = toCubicMetresPerHectare;
		}

		public double toCubicMetresPerHectare() {
			return toCubicMetresPerHectare;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]*)\\}");

	// ---- inputs (the loader sets these) ----
	String parameters = "AFTs/react/Reactive_parameters.csv";
	String baseCosts = "costs/global/global_costs.csv";
	String cellKey = "worlds/react/cell_key.csv";
	String irrigationCost = "worlds/react/irrigation/Irrigation_cost.csv";
	String suitabilities = "worlds/react/suitabilities/{scenario}/{type}";
	String capitals = "worlds/react/capitals/{scenario}";
	String irrigationDemand = "worlds/react/irrigation/{scenario}/Irrigation_demand_{year}.csv";
	String runoff = "worlds/react/irrigation/{scenario}/Runoff_{year}.csv";
	YieldUnits yieldFileUnits = YieldUnits.KG_PER_M2;
	WaterUnits irrigationFileUnits = WaterUnits.MM;

	// ---- tunable settings (phases 3 and 4 use these) ----
	int spinupIterations = 5;
	double nMaxFactor = 1.5;
	double nAdjustmentScale = 0.15;
	// The prospect reference point is not a setting here: it is each Prospect AFT's react_N_par
	// (an absolute amount per kg N; 0 when blank).
	double prospectAlpha = 0.88;
	double prospectBeta = 0.88;
	double prospectLambda = 2.25;
	double stockingInitial = 0.5;
	double stockingStep = 0.05;
	double stockingMin = 0.05;
	double stockingMax = 1.0;
	// The most of the pasture NPP that can be taken off (0-1); the stocking rate sets how much of it is.
	double stockingHarvest = 0.5;
	// Crop yield change per year from technology: the yield surface is multiplied by
	// (1 + yield_tech_change x years since start_year). 0 means no change.
	double yieldTechChange = 0;

	/** A config holding every default. */
	public static ReactConfig defaults() {
		return new ReactConfig();
	}

	ReactConfig() {
	}

	// ---- file locations ----

	public Path parametersFile(Path projectPath) {
		return resolve(projectPath, "parameters", parameters, null, null, null);
	}

	public Path baseCostsFile(Path projectPath) {
		return resolve(projectPath, "base_costs", baseCosts, null, null, null);
	}

	public Path cellKeyFile(Path projectPath) {
		return resolve(projectPath, "cell_key", cellKey, null, null, null);
	}

	public Path irrigationCostFile(Path projectPath) {
		return resolve(projectPath, "irrigation_cost", irrigationCost, null, null, null);
	}

	/** The year folder for one type of suitability. */
	public Path suitabilityFolder(Path projectPath, String scenario, LpjgType type) {
		return resolve(projectPath, "suitabilities", suitabilities, scenario, null, type);
	}

	/** The year folder for react's capitals. */
	public Path capitalsFolder(Path projectPath, String scenario) {
		return resolve(projectPath, "capitals", capitals, scenario, null, null);
	}

	public Path irrigationDemandFile(Path projectPath, String scenario, int year) {
		return resolve(projectPath, "irrigation_demand", irrigationDemand, scenario, year, null);
	}

	public Path runoffFile(Path projectPath, String scenario, int year) {
		return resolve(projectPath, "runoff", runoff, scenario, year, null);
	}

	private static Path resolve(Path projectPath, String key, String template, String scenario, Integer year,
			LpjgType type) {
		Matcher matcher = PLACEHOLDER.matcher(template);
		StringBuilder filled = new StringBuilder();
		while (matcher.find()) {
			String value = switch (matcher.group(1)) {
			case "scenario" -> scenario;
			case "year" -> year == null ? null : year.toString();
			case "type" -> type == null ? null : type.label();
			default -> null;
			};
			if (value == null) {
				throw new ReactInputException("react_config.yaml: " + key + " cannot use {" + matcher.group(1) + "}");
			}
			matcher.appendReplacement(filled, Matcher.quoteReplacement(value));
		}
		matcher.appendTail(filled);
		return projectPath.resolve(filled.toString()).normalize();
	}

	// ---- units ----

	public YieldUnits yieldFileUnits() {
		return yieldFileUnits;
	}

	public WaterUnits irrigationFileUnits() {
		return irrigationFileUnits;
	}

	// ---- tunable settings ----

	public int spinupIterations() {
		return spinupIterations;
	}

	public double nMaxFactor() {
		return nMaxFactor;
	}

	public double nAdjustmentScale() {
		return nAdjustmentScale;
	}

	public double prospectAlpha() {
		return prospectAlpha;
	}

	public double prospectBeta() {
		return prospectBeta;
	}

	public double prospectLambda() {
		return prospectLambda;
	}

	public double stockingInitial() {
		return stockingInitial;
	}

	public double stockingStep() {
		return stockingStep;
	}

	public double stockingMin() {
		return stockingMin;
	}

	public double stockingMax() {
		return stockingMax;
	}

	/** The most of the pasture NPP that can be taken off, 0–1 (R's {@code harvest_frac}). */
	public double stockingHarvest() {
		return stockingHarvest;
	}

	/** Crop yield change per year from technology, as a fraction of the LPJ-GUESS yield (0 = none). */
	public double yieldTechChange() {
		return yieldTechChange;
	}

	// ---- checks ----

	/** Everything wrong with the settings, as messages; empty if they can be used. */
	List<String> problems() {
		List<String> problems = new ArrayList<>();
		checkTemplate(problems, "parameters", parameters, List.of());
		checkTemplate(problems, "base_costs", baseCosts, List.of());
		checkTemplate(problems, "cell_key", cellKey, List.of());
		checkTemplate(problems, "irrigation_cost", irrigationCost, List.of());
		checkTemplate(problems, "suitabilities", suitabilities, List.of("scenario", "type"));
		checkTemplate(problems, "capitals", capitals, List.of("scenario"));
		checkTemplate(problems, "irrigation_demand", irrigationDemand, List.of("scenario", "year"));
		checkTemplate(problems, "runoff", runoff, List.of("scenario", "year"));
		if (!suitabilities.contains("{type}")) {
			problems.add("inputs.suitabilities must contain {type}, so crops and pasture are read from different folders");
		}
		if (!irrigationDemand.contains("{year}")) {
			problems.add("inputs.irrigation_demand must contain {year}");
		}
		if (!runoff.contains("{year}")) {
			problems.add("inputs.runoff must contain {year}");
		}

		if (spinupIterations < 0) {
			problems.add("spinup_iterations must be 0 or more");
		}
		if (nMaxFactor < 1) {
			problems.add("n_max_factor must be at least 1, so the most N an AFT can use is not below its anchor");
		}
		if (nAdjustmentScale <= 0) {
			problems.add("n_adjustment_scale must be above 0");
		}
		if (prospectAlpha <= 0 || prospectAlpha > 1) {
			problems.add("prospect.alpha must be above 0 and at most 1");
		}
		if (prospectBeta <= 0 || prospectBeta > 1) {
			problems.add("prospect.beta must be above 0 and at most 1");
		}
		if (prospectLambda <= 0) {
			problems.add("prospect.lambda must be above 0");
		}
		if (stockingStep <= 0) {
			problems.add("stocking.step must be above 0");
		}
		if (!(0 < stockingHarvest && stockingHarvest <= 1)) {
			problems.add("stocking.harvest must be above 0 and at most 1: it is a fraction of the pasture NPP");
		}
		if (!(0 < stockingMin && stockingMin <= stockingInitial && stockingInitial <= stockingMax)) {
			problems.add("stocking values must satisfy 0 < min <= initial <= max");
		}
		if (yieldTechChange <= -1) {
			problems.add("yield_tech_change must be above -1, so a year's technology change cannot take yields to 0");
		}
		return problems;
	}

	private static void checkTemplate(List<String> problems, String key, String template, List<String> allowed) {
		if (template.isBlank()) {
			problems.add("inputs." + key + " is blank");
			return;
		}
		Matcher matcher = PLACEHOLDER.matcher(template);
		while (matcher.find()) {
			if (!allowed.contains(matcher.group(1))) {
				problems.add("inputs." + key + " cannot use {" + matcher.group(1) + "}"
						+ (allowed.isEmpty() ? "" : "; it may use " + allowed.stream().map(p -> "{" + p + "}").toList()));
			}
		}
	}

	@Override
	public String toString() {
		return String.join("\n",
				"inputs.parameters: " + parameters,
				"inputs.base_costs: " + baseCosts,
				"inputs.cell_key: " + cellKey,
				"inputs.irrigation_cost: " + irrigationCost,
				"inputs.suitabilities: " + suitabilities,
				"inputs.capitals: " + capitals,
				"inputs.irrigation_demand: " + irrigationDemand,
				"inputs.runoff: " + runoff,
				"inputs.yield_file_units: " + yieldFileUnits + " (x" + yieldFileUnits.toTonnesPerHectare() + " -> t/ha)",
				"inputs.irrigation_file_units: " + irrigationFileUnits + " (x"
						+ irrigationFileUnits.toCubicMetresPerHectare() + " -> m3/ha)",
				"spinup_iterations: " + spinupIterations,
				"n_max_factor: " + nMaxFactor,
				"n_adjustment_scale: " + nAdjustmentScale,
				"prospect: alpha " + prospectAlpha + ", beta " + prospectBeta
						+ ", lambda " + prospectLambda,
				"stocking: initial " + stockingInitial + ", step " + stockingStep + ", min " + stockingMin + ", max "
						+ stockingMax + ", harvest " + stockingHarvest,
				"yield_tech_change: " + yieldTechChange);
	}
}
