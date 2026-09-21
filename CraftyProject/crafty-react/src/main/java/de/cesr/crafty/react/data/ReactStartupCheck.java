package de.cesr.crafty.react.data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import de.cesr.crafty.core.cli.CustomLogger;

/**
 * CRAFTY-react's startup checks: everything that can be checked before any year's data is loaded.
 * Every problem is collected, and then all of them are reported in one {@link ReactInputException}, so
 * one failed start shows everything that needs fixing.
 *
 * Numbering follows the phase 1 plan (§2.6), as revised by the 27b plan:
 * <ol>
 * <li>each year folder has exactly one file for every year of the run, and the named per-year
 * files exist;</li>
 * <li>the parameters sheet and core list the same AFTs (masks included);</li>
 * <li>each reactive AFT's service is in Services.csv and in the model (in {@link ReactiveParameters});</li>
 * <li>its LPJG_type is crops or pasture (in {@link ReactiveParameters});</li>
 * <li>the LPJ-GUESS columns react needs are in every year's file (headers only);</li>
 * <li>each capital react reads is a column in every year's capitals file;</li>
 * <li>values are in range (in {@link ReactiveParameters});</li>
 * <li>global_costs.csv has the base costs the switched-on elements need;</li>
 * <li>every model cell has a pixel in the cell key;</li>
 * <li>(check 10, every pixel present in each LPJ-GUESS file, runs as each year loads, in 27c);</li>
 * <li>core will apply what react writes: core found the cost file of each switched-on element for every
 * year, and each reactive AFT is on core's list for that cost.</li>
 * </ol>
 * Files for an element are only required when the element is switched on and some reactive AFT uses
 * it.
 */
public final class ReactStartupCheck {

	private static final CustomLogger LOGGER = new CustomLogger(ReactStartupCheck.class);

	/** The yield columns needed for each crop: N 0, 200 and 1000, rainfed and irrigated. */
	public static final List<String> CROP_LEVELS = List.of("0", "0200", "1000", "i0", "i0200", "i1000");

	/** The irrigation demand columns needed for each irrigated crop. */
	public static final List<String> DEMAND_LEVELS = List.of("i0", "i0200", "i1000");

	/** The runoff file's value column. */
	public static final String RUNOFF = "Total";

	/**
	 * Everything the checks loaded, for the per-year loading (27c) to use.
	 *
	 * @param irrigationCost   null unless irrigation is reactive and some reactive AFT irrigates
	 * @param suitabilityFiles for each type in use, the file for each year
	 * @param capitalsFiles    the capitals file for each year; empty if no capital is read
	 */
	public record Result(ReactiveParameters parameters, ReactServiceKey services, ReactBaseCosts baseCosts,
			CellKey cellKey, IrrigationCostGrid irrigationCost, Map<LpjgType, Map<Integer, Path>> suitabilityFiles,
			Map<Integer, Path> capitalsFiles) {
	}

	private final ReactConfig config;
	private final ReactRunContext context;
	private final Path project;
	private final List<String> problems = new ArrayList<>();

	private ReactStartupCheck(ReactConfig config, ReactRunContext context) {
		this.config = config;
		this.context = context;
		this.project = context.projectPath();
	}

	/** Runs every check. Throws one exception listing all problems, if there are any. */
	public static Result run(ReactConfig config, ReactRunContext context) {
		return new ReactStartupCheck(config, context).run();
	}

	private Result run() {
		ReactServiceKey services = attempt(() -> ReactServiceKey.load(context.servicesFile()));
		ReactiveParameters parameters = services == null ? null
				: attempt(() -> ReactiveParameters.read(config.parametersFile(project), services, context, problems));
		if (parameters != null) {
			checkAftLists(parameters);
		}

		ReactBaseCosts baseCosts = attempt(() -> ReactBaseCosts.load(config.baseCostsFile(project)));
		if (baseCosts != null && parameters != null) {
			checkBaseCosts(parameters, baseCosts);
		}

		CellKey cellKey = attempt(() -> CellKey.load(config.cellKeyFile(project)));
		if (cellKey != null) {
			attempt(() -> {
				cellKey.requireEveryCell(context.cellIds());
				return null;
			});
		}

		IrrigationCostGrid irrigationCost = null;
		Map<LpjgType, Map<Integer, Path>> suitabilityFiles = new EnumMap<>(LpjgType.class);
		Map<Integer, Path> capitalsFiles = new LinkedHashMap<>();
		if (parameters != null && context.anyReactive()) {
			if (cellKey != null && !irrigatedCrops(parameters).isEmpty()) {
				irrigationCost = attempt(() -> IrrigationCostGrid.load(config.irrigationCostFile(project), cellKey.grid()));
			}
			checkSuitabilities(parameters, suitabilityFiles);
			checkCapitals(parameters, capitalsFiles);
			checkIrrigationFiles(parameters);
			checkCoreCostFiles(parameters);
		}

		if (!problems.isEmpty()) {
			throw new ReactInputException("CRAFTY-react cannot start: " + problems.size() + " problem(s)\n  - "
					+ String.join("\n  - ", problems));
		}
		LOGGER.info("CRAFTY-react startup checks passed: " + parameters.reactive().size() + " reactive AFT(s) "
				+ parameters.reactive().stream().map(AftReactParameters::label).toList() + ", reactive elements "
				+ context.reactive() + ", " + cellKey.cellCount() + " cells in " + cellKey.grid().size() + " pixels");
		return new Result(parameters, services, baseCosts, cellKey, irrigationCost, suitabilityFiles, capitalsFiles);
	}

	// ---- check 2: the same AFTs in the sheet and in core ----

	private void checkAftLists(ReactiveParameters parameters) {
		Path sheet = parameters.file();
		for (String label : parameters.labels()) {
			if (!context.afts().containsKey(label)) {
				problems.add(sheet + ": AFT " + label + " is not in the model's AFTsMetaData.csv");
			}
		}
		for (String label : context.afts().keySet()) {
			if (!parameters.labels().contains(label)) {
				problems.add(sheet + " has no row for AFT " + label + "; every AFT, masks included, needs a row"
						+ " (use react_isReactive = 0 for AFTs that don't react)");
			}
		}
	}

	// ---- check 8: base costs ----

	private void checkBaseCosts(ReactiveParameters parameters, ReactBaseCosts baseCosts) {
		Map<String, String> needed = new LinkedHashMap<>();
		if (context.isReactive(ReactElement.FERTILISER) && !parameters.reactive(LpjgType.CROPS).isEmpty()) {
			needed.put(ReactBaseCosts.NFERT, "fertiliser is reactive");
		}
		if (context.isReactive(ReactElement.IRRIGATION) && !irrigatedCrops(parameters).isEmpty()) {
			needed.put(ReactBaseCosts.WATER, "irrigation is reactive");
		}
		if (context.isReactive(ReactElement.STOCKING) && !parameters.reactive(LpjgType.PASTURE).isEmpty()) {
			needed.put(ReactBaseCosts.STOCKING, "stocking is reactive");
		}
		if (context.isReactive(ReactElement.OTHER_INTENSITY)) {
			for (String service : parameters.servicesInUse()) {
				needed.putIfAbsent(service, "other intensity is reactive and a reactive AFT produces " + service);
			}
		}
		for (String item : baseCosts.missing(needed.keySet())) {
			problems.add(baseCosts.file() + " has no row for " + item + ", which is needed because " + needed.get(item));
		}
	}

	// ---- checks 1 and 5: suitability year folders and their columns ----

	private void checkSuitabilities(ReactiveParameters parameters, Map<LpjgType, Map<Integer, Path>> found) {
		for (LpjgType type : List.of(LpjgType.CROPS, LpjgType.PASTURE)) {
			List<AftReactParameters> afts = parameters.reactive(type);
			if (afts.isEmpty()) {
				continue;
			}
			Set<String> columns = new LinkedHashSet<>();
			for (AftReactParameters aft : afts) {
				if (type == LpjgType.CROPS) {
					CROP_LEVELS.forEach(level -> columns.add(aft.lpjgName() + level));
				} else {
					columns.add(aft.lpjgName());
				}
			}
			Map<Integer, Path> files = attempt(() -> YearFileFinder.findAll(
					config.suitabilityFolder(project, context.scenario(), type), context.firstYear(), context.lastYear()));
			if (files != null) {
				found.put(type, files);
				requireColumns(files.values(), columns);
			}
		}
	}

	// ---- checks 1 and 6: capitals year folder and its columns ----

	private void checkCapitals(ReactiveParameters parameters, Map<Integer, Path> found) {
		Set<String> capitals = parameters.capitalsNamed(context.reactive());
		if (capitals.isEmpty()) {
			return;
		}
		Map<Integer, Path> files = attempt(() -> YearFileFinder.findAll(config.capitalsFolder(project, context.scenario()),
				context.firstYear(), context.lastYear()));
		if (files != null) {
			found.putAll(files);
			requireColumns(files.values(), capitals);
		}
	}

	// ---- checks 1 and 5: irrigation demand and runoff ----

	private void checkIrrigationFiles(ReactiveParameters parameters) {
		List<AftReactParameters> irrigated = irrigatedCrops(parameters);
		if (irrigated.isEmpty()) {
			return;
		}
		Set<String> demandColumns = new LinkedHashSet<>();
		for (AftReactParameters aft : irrigated) {
			DEMAND_LEVELS.forEach(level -> demandColumns.add(aft.lpjgName() + level));
		}
		List<Path> demandFiles = new ArrayList<>();
		List<Path> runoffFiles = new ArrayList<>();
		for (int year = context.firstYear(); year <= context.lastYear(); year++) {
			collectIfPresent(config.irrigationDemandFile(project, context.scenario(), year), demandFiles);
			collectIfPresent(config.runoffFile(project, context.scenario(), year), runoffFiles);
		}
		requireColumns(demandFiles, demandColumns);
		requireColumns(runoffFiles, List.of(RUNOFF));
	}

	private void collectIfPresent(Path file, List<Path> files) {
		if (Files.isRegularFile(file)) {
			files.add(file);
		} else {
			problems.add("File not found: " + file);
		}
	}

	// ---- check 11: core will apply what react writes ----

	private void checkCoreCostFiles(ReactiveParameters parameters) {
		Map<ReactElement, Boolean> used = new EnumMap<>(ReactElement.class);
		used.put(ReactElement.FERTILISER, !parameters.reactive(LpjgType.CROPS).isEmpty());
		used.put(ReactElement.IRRIGATION, !irrigatedCrops(parameters).isEmpty());
		used.put(ReactElement.OTHER_INTENSITY, !parameters.reactive().isEmpty());
		used.put(ReactElement.STOCKING, !parameters.reactive(LpjgType.PASTURE).isEmpty());

		for (ReactElement element : context.reactive()) {
			if (!used.get(element)) {
				continue;
			}
			List<Integer> missing = new ArrayList<>();
			for (int year = context.firstYear(); year <= context.lastYear(); year++) {
				if (!context.costFilesByYear().getOrDefault(year, Set.of()).contains(element)) {
					missing.add(year);
				}
			}
			if (!missing.isEmpty()) {
				problems.add("The model found no spatial " + element.costFile() + " file for year(s) " + missing
						+ ". React writes its " + element + " costs into that file, so without it they would never"
						+ " reach the model");
			}
		}

		// Core charges a stocking cost only to AFTs that produce Pasture. (The Nfert_rate > 0 rule for
		// fertiliser is checked with the rest of each row, in ReactiveParameters; irrigation already
		// follows core's Irrigated; intensity applies to every AFT.)
		if (context.isReactive(ReactElement.STOCKING)) {
			for (AftReactParameters aft : parameters.reactive(LpjgType.PASTURE)) {
				if (!aft.baseline().producesPasture()) {
					problems.add("AFT " + aft.label() + " is a reactive pasture AFT but does not produce Pasture in the"
							+ " model, so the model would not charge it the stocking costs react writes");
				}
			}
		}
	}

	// ---- helpers ----

	/** The reactive crops AFTs that irrigate, when irrigation is reactive; otherwise none. */
	private List<AftReactParameters> irrigatedCrops(ReactiveParameters parameters) {
		if (!context.isReactive(ReactElement.IRRIGATION)) {
			return List.of();
		}
		return parameters.reactive(LpjgType.CROPS).stream().filter(AftReactParameters::isIrrigated).toList();
	}

	/** Adds a problem for each file whose header lacks any of the columns (or Lon/Lat). */
	private void requireColumns(Collection<Path> files, Collection<String> columns) {
		List<String> wanted = new ArrayList<>();
		wanted.add(LpjFileReader.LON);
		wanted.add(LpjFileReader.LAT);
		wanted.addAll(columns);
		for (Path file : files) {
			List<String> header = attempt(() -> ReactCsv.readHeader(file));
			if (header == null) {
				continue;
			}
			List<String> missing = wanted.stream().filter(c -> !header.contains(c)).toList();
			if (!missing.isEmpty()) {
				problems.add(file + " is missing column(s) " + missing);
			}
		}
	}

	/** Runs a step; a ReactInputException becomes a problem, and the step's result is null. */
	private <T> T attempt(Supplier<T> step) {
		try {
			return step.get();
		} catch (ReactInputException e) {
			problems.add(e.getMessage());
			return null;
		}
	}
}
