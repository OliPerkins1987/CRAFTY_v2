package de.cesr.crafty.react.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
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
 * <li>the parameters sheet and core list the same AFTs (masks included, but not the model's own
 * {@value #ABANDONED});</li>
 * <li>each reactive AFT's service is in Services.csv and in the model (in {@link ReactiveParameters});</li>
 * <li>its LPJG_type is crops, pasture or forestry (in {@link ReactiveParameters});</li>
 * <li>the LPJ-GUESS columns react needs are in every year's file, with {@code Lon} and {@code Lat} or
 * {@code x} and {@code y} (headers only);</li>
 * <li>each capital react reads is a column in every year's capitals file;</li>
 * <li>values are in range (in {@link ReactiveParameters}), and, with forestry on, each forestry AFT's
 * initial rotation is on the rotation grid;</li>
 * <li>global_costs.csv has the base costs the switched-on elements need;</li>
 * <li>every model cell has a pixel in the cell key;</li>
 * <li>(check 10, every pixel present in each LPJ-GUESS file, runs as each year loads, in 27c);</li>
 * <li>(check 11, that core would read react's cost files, was retired in phase 5: react hands its values
 * to the model in memory);</li>
 * <li>the cell key's regions are the model's;</li>
 * <li>each {@code <AFT>_suit} capital react hands a value to is one of the model's capitals (phase 5).</li>
 * </ol>
 * A land use's files are only required, and only loaded, when it is in use: one of its elements is on
 * and it has a reactive AFT. Crops (fertiliser, irrigation, other intensity): the yields, and for irrigated
 * AFTs the irrigation demand, runoff and irrigation cost index. Pasture (other intensity, stocking): the
 * NPP. Forestry (forestry): the forestry yields. Capitals are read per element.
 *
 * Some checks only warn: a {@code <AFT>_suit} react hands over that is typed Capital, when the model
 * separates production from competitiveness (with check 13); that every AFT the model charges a cost gets
 * it from somewhere, either react's handover or a column in the model's own cost file; the same for
 * every capital the model has, and its own capitals files (phase 5 plan, §3.4); and reactive forestry AFTs
 * with forestry switched off. The warnings are logged, and kept in the {@link Result}.
 */
public final class ReactStartupCheck {

	private static final CustomLogger LOGGER = new CustomLogger(ReactStartupCheck.class);

	/** The yield columns needed for each crop: N 0, 200 and 1000, rainfed and irrigated. */
	public static final List<String> CROP_LEVELS = List.of("0", "0200", "1000", "i0", "i0200", "i1000");

	/** The irrigation demand columns needed for each irrigated crop. */
	public static final List<String> WATER_DEMAND_LEVELS = List.of("i0", "i0200", "i1000");

	/** The runoff file's value column. */
	public static final String RUNOFF = "Total";

	/** The start of a forestry file's column for a rotation: {@code harvest_age_<H>}, H in years. */
	public static final String HARVEST_AGE = "harvest_age_";

	/**
	 * The AFT the model adds itself, for land nobody manages: {@code AFTsLoader} puts it in the AFT list
	 * alongside the ones read from {@code AFTsMetaData.csv}. It is not in the metadata, so the react
	 * sheet is not expected to have a row for it, and it cannot react.
	 */
	public static final String ABANDONED = "Abandoned";

	/** The end of an AFT's suitability capital's name: react hands its yield or production to {@code <AFT>_suit}. */
	public static final String SUIT = "_suit";

	/** The elements whose being on makes react hand over the crops AFTs' {@code _suit}, as the cropland stage has them. */
	private static final Set<ReactElement> CROP_ELEMENTS = EnumSet.of(ReactElement.FERTILISER, ReactElement.IRRIGATION,
			ReactElement.OTHER_INTENSITY);

	/** The same for the pasture AFTs, as the pasture stage has them. */
	private static final Set<ReactElement> PASTURE_ELEMENTS = EnumSet.of(ReactElement.OTHER_INTENSITY,
			ReactElement.STOCKING);

	/** The same for the forestry AFTs. */
	private static final Set<ReactElement> FORESTRY_ELEMENTS = EnumSet.of(ReactElement.FORESTRY);

	/** The irrigation cost files' one column for every irrigated AFT, which core reads instead of the AFTs' own. */
	static final String SHARED_IRRIGATION_COLUMN = "IRRIGATION_COST";

	/**
	 * Everything the checks loaded, for the per-year loading (27c) to use.
	 *
	 * @param irrigationCost   null unless crops are in use and some reactive crops AFT irrigates
	 * @param suitabilityFiles for each land use in use, the file for each year
	 * @param capitalsFiles    the capitals file for each year; empty if no capital is read
	 * @param irrigatedCrops   the reactive crops AFTs that irrigate, when crops are in use; otherwise empty
	 * @param warnings         the startup warnings (see the class comment), as logged
	 */
	public record Result(ReactiveParameters parameters, ReactServiceKey services, ReactBaseCosts baseCosts,
			CellKey cellKey, IrrigationCostGrid irrigationCost, Map<LpjgType, Map<Integer, Path>> suitabilityFiles,
			Map<Integer, Path> capitalsFiles, List<AftReactParameters> irrigatedCrops, List<String> warnings) {
	}

	private final ReactConfig config;
	private final ReactRunContext context;
	private final Path project;
	private final List<String> problems = new ArrayList<>();
	private final List<String> warnings = new ArrayList<>();
	/** The model's input files read for the warnings, so that none is read twice. */
	private final Map<Path, CoreFile> coreFiles = new HashMap<>();

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
			checkRegions(cellKey);
		}

		IrrigationCostGrid irrigationCost = null;
		Map<LpjgType, Map<Integer, Path>> suitabilityFiles = new EnumMap<>(LpjgType.class);
		Map<Integer, Path> capitalsFiles = new LinkedHashMap<>();
		List<AftReactParameters> irrigatedCrops = parameters == null || !inUse(parameters, LpjgType.CROPS) ? List.of()
				: irrigatedCrops(parameters);
		if (parameters != null && context.anyReactive()) {
			if (cellKey != null && !irrigatedCrops.isEmpty()) {
				irrigationCost = attempt(() -> IrrigationCostGrid.load(config.irrigationCostFile(project), cellKey.grid()));
			}
			checkSuitabilities(parameters, suitabilityFiles);
			checkCapitals(parameters, capitalsFiles);
			checkIrrigationFiles(irrigatedCrops);
			checkInitialRotations(parameters);
			checkSuitCapitals(parameters);
		}

		if (!problems.isEmpty()) {
			throw new ReactInputException("CRAFTY-react cannot start: " + problems.size() + " problem(s)\n  - "
					+ String.join("\n  - ", problems));
		}
		if (!context.anyReactive()) {
			LOGGER.warn("CRAFTY-react is on but no element is reactive, so react changes nothing. The startup checks"
					+ " have run; no year data is loaded");
		}
		warnIfIrrigationIsLeftBehind(irrigatedCrops);
		forestryMessages(parameters);
		warnings.addAll(costFileWarnings(parameters));
		warnings.addAll(capitalsFileWarnings(parameters));
		warnings.forEach(LOGGER::warn);
		LOGGER.info("CRAFTY-react startup checks passed: " + parameters.reactive().size() + " reactive AFT(s) "
				+ parameters.reactive().stream().map(AftReactParameters::label).toList() + ", reactive elements "
				+ context.reactive() + ", " + cellKey.cellCount() + " cells in " + cellKey.grid().size() + " pixels, "
				+ cellKey.regions().size() + " region(s), " + cellKey.pixelsSpanningRegions()
				+ " pixel(s) spanning more than one region");
		return new Result(parameters, services, baseCosts, cellKey, irrigationCost, suitabilityFiles, capitalsFiles,
				irrigatedCrops, List.copyOf(warnings));
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
			if (ABANDONED.equals(label)) {
				continue;
			}
			if (!parameters.labels().contains(label)) {
				problems.add(sheet + " has no row for AFT " + label + "; every AFT, masks included, needs a row"
						+ " (use react_isReactive = 0 for AFTs that don't react)");
			}
		}
		if (parameters.isReactive(ABANDONED)) {
			problems.add(sheet + ": " + ABANDONED + " cannot be reactive. The model makes it itself, for land nobody"
					+ " manages, and it produces nothing");
		}
	}

	/**
	 * Fertiliser reactive with irrigation not reactive is an odd pairing, so say plainly what react will
	 * do: irrigation demand is read at the baseline N and stays there while N moves, and the irrigation
	 * costs the model charges come from its own pre-written files, so they do not follow that year's
	 * runoff. Switch irrigation on if you want irrigation to respond to N and to the water available.
	 */
	private void warnIfIrrigationIsLeftBehind(List<AftReactParameters> irrigatedCrops) {
		if (irrigatedCrops.isEmpty() || !context.isReactive(ReactElement.FERTILISER)
				|| context.isReactive(ReactElement.IRRIGATION)) {
			return;
		}
		LOGGER.warn("CRAFTY-react: fertiliser is reactive but irrigation is not, for "
				+ irrigatedCrops.stream().map(AftReactParameters::label).toList() + ". Their irrigation demand is read"
				+ " at the baseline N (Nfert_rate), so water applied stays at min(runoff, demand at baseline N) while"
				+ " N changes, and the model charges the irrigation costs in its own spatial files, which do not"
				+ " follow that year's runoff. Switch reactive_irrigation on for irrigation to respond");
	}

	/**
	 * When the forestry switch and the sheet's forestry AFTs don't go together, say so: reactive forestry
	 * AFTs with forestry off keep the model's own values (a warning); forestry on with no reactive forestry
	 * AFT changes nothing (an info line).
	 */
	private void forestryMessages(ReactiveParameters parameters) {
		List<String> forestryAfts = parameters.reactive(LpjgType.FORESTRY).stream().map(AftReactParameters::label)
				.toList();
		boolean forestry = context.isReactive(ReactElement.FORESTRY);
		if (!forestry && !forestryAfts.isEmpty()) {
			warnings.add("CRAFTY-react: " + forestryAfts + " are reactive forestry AFTs, but reactive_forestry is off,"
					+ " so they keep the model's own suitabilities and intensity costs");
		} else if (forestry && forestryAfts.isEmpty()) {
			LOGGER.info("CRAFTY-react: reactive_forestry is on, but no AFT in " + parameters.file()
					+ " is a reactive forestry AFT, so it changes nothing");
		}
	}

	// ---- check 12: regions ----

	/**
	 * The key's regions must be ones the model knows, because react prices a pixel's produce with that
	 * region's service weights. Cells whose key region differs from the model's are counted in one
	 * warning: a few are expected where a pixel straddles a border, but many mean the key is wrong.
	 *
	 * A project that is not regionalised has a single region covering every cell (core names it after
	 * the GIS file, and falls back to it when there is no demand file per region). The key's own regions
	 * are then neither matched nor used: everything is priced in that one region. They are left in the
	 * key, where they cost nothing and become useful if the project is regionalised later.
	 */
	private void checkRegions(CellKey cellKey) {
		if (context.regions().size() <= 1) {
			String only = context.regions().isEmpty() ? "none" : context.regions().iterator().next();
			LOGGER.info("CRAFTY-react: the model has a single region (" + only + "), so every pixel is priced there."
					+ " The " + cellKey.regions().size() + " region(s) named in " + config.cellKeyFile(project)
					+ " are not used, and are not checked, until the project is regionalised");
			return;
		}
		List<String> unknown = cellKey.regions().stream().filter(r -> !context.regions().contains(r)).toList();
		if (!unknown.isEmpty()) {
			problems.add(config.cellKeyFile(project) + ": region(s) " + unknown + " are not regions of this model "
					+ context.regions());
		}
		int differing = 0;
		String example = null;
		for (String cell : context.cellIds()) {
			String keyRegion = cellKey.regionOfCell(cell);
			if (keyRegion != null && !keyRegion.equals(context.regionOfCell(cell))) {
				differing++;
				if (example == null) {
					example = cell + " (key " + keyRegion + ", model " + context.regionOfCell(cell) + ")";
				}
			}
		}
		if (differing > 0) {
			LOGGER.warn("CRAFTY-react: " + differing + " of " + context.cellIds().size()
					+ " cells are in a different region in " + config.cellKeyFile(project)
					+ " than in the model, e.g. " + example
					+ ". A few are expected where a pixel straddles a border; many mean the key is wrong");
		}
	}

	// ---- check 8: base costs ----

	private void checkBaseCosts(ReactiveParameters parameters, ReactBaseCosts baseCosts) {
		Map<String, String> needed = new LinkedHashMap<>();
		if (context.isReactive(ReactElement.FERTILISER) && !parameters.reactive(LpjgType.CROPS).isEmpty()) {
			needed.put(ReactBaseCosts.NFERT, "fertiliser is reactive");
		}
		if (context.isReactive(ReactElement.IRRIGATION) && !irrigatedCrops(parameters).isEmpty()) {
			// Only react's own irrigation costs use the base water price; with the switch off, the costs
			// come from core's pre-written files.
			needed.put(ReactBaseCosts.WATER, "irrigation is reactive");
		}
		if (context.isReactive(ReactElement.STOCKING) && !parameters.reactive(LpjgType.PASTURE).isEmpty()) {
			needed.put(ReactBaseCosts.STOCKING, "stocking is reactive");
		}
		if (context.isReactive(ReactElement.OTHER_INTENSITY)) {
			// Other intensity changes crops and pasture AFTs only: a forestry AFT's intensity is its rotation.
			for (AftReactParameters aft : parameters.reactive()) {
				if (!aft.isForestry()) {
					needed.putIfAbsent(aft.service(), "other intensity is reactive and a reactive AFT produces "
							+ aft.service());
				}
			}
		}
		if (context.isReactive(ReactElement.STOCKING)) {
			// The stocking decision weighs the husbandry cost, husbandry x the service's cost, whether or not
			// other intensity is reactive (phase 4 plan, Q4).
			for (AftReactParameters aft : parameters.reactive(LpjgType.PASTURE)) {
				needed.putIfAbsent(aft.service(), "stocking is reactive, and " + aft.label()
						+ "'s stocking decision weighs its husbandry at the cost of " + aft.service());
			}
		}
		if (context.isReactive(ReactElement.FORESTRY)) {
			for (AftReactParameters aft : parameters.reactive(LpjgType.FORESTRY)) {
				needed.putIfAbsent(aft.service(), "forestry is reactive, and " + aft.label()
						+ "'s rotation cost is the cost of one harvest of " + aft.service());
			}
		}
		for (String item : baseCosts.missing(needed.keySet())) {
			problems.add(baseCosts.file() + " has no row for " + item + ", which is needed because " + needed.get(item));
		}
	}

	// ---- check 13: the _suit capitals react hands over ----

	/**
	 * React hands each reactive AFT's yield (crops) or production (pasture) to the model as its
	 * {@code <AFT>_suit} capital, when one of its land use's elements is on (phase 3 plan, Q6). That capital
	 * must be one of the model's (phase 5 plan, Q2): core only reads an AFT's sensitivity to the capitals in
	 * {@code Capitals.csv}, so otherwise react's value would be ignored.
	 *
	 * Its type only matters when {@code separate_production_competitiveness} is on: then production counts
	 * only the capitals typed Suitability, so a {@code _suit} typed Capital would drive the AFT's
	 * competitiveness but not its production. That may be intended, so it is a warning, not a stop; with the
	 * switch off the type makes no difference and nothing is said. The AFT's sensitivity to its {@code _suit}
	 * and its production level are not checked: they are the model's choice.
	 */
	private void checkSuitCapitals(ReactiveParameters parameters) {
		List<String> missing = new ArrayList<>();
		List<String> typedCapital = new ArrayList<>();
		for (AftReactParameters aft : suitsHandedOver(parameters)) {
			String capital = aft.label() + SUIT;
			Boolean suitability = context.capitals().get(capital);
			if (suitability == null) {
				missing.add(capital);
			} else if (!suitability) {
				typedCapital.add(capital);
			}
		}
		if (!missing.isEmpty()) {
			problems.add("The model has no capital(s) " + missing + ". React hands each reactive AFT's yield or"
					+ " production to its _suit capital, so add them to Capitals.csv");
		}
		if (!typedCapital.isEmpty() && context.separateProductionCompetitiveness()) {
			warnings.add("CRAFTY-react: capital(s) " + typedCapital + " are typed Capital in Capitals.csv and"
					+ " separate_production_competitiveness is on, so react's yield or production counts towards those"
					+ " AFTs' competitiveness but not their production. Type them Suitability if it should count"
					+ " towards both");
		}
	}

	/**
	 * The reactive AFTs whose {@code _suit} react hands over: the crops AFTs when a crop element is on, the
	 * pasture AFTs when a pasture element is on, as the two stages have them.
	 */
	private List<AftReactParameters> suitsHandedOver(ReactiveParameters parameters) {
		List<AftReactParameters> handedOver = new ArrayList<>();
		if (landUseOn(LpjgType.CROPS)) {
			handedOver.addAll(parameters.reactive(LpjgType.CROPS));
		}
		if (landUseOn(LpjgType.PASTURE)) {
			handedOver.addAll(parameters.reactive(LpjgType.PASTURE));
		}
		return handedOver;
	}

	// ---- warning: costs the model charges that nothing gives ----

	/**
	 * For each cost, the AFTs the model charges it to (core's lists) that get no value, in some year, from
	 * either react or the model's cost file: one warning per cost, naming the AFTs and years (phase 5 plan,
	 * §3.4). They are charged 0, which may be intended (a blank file for an AFT that really is free), so
	 * this does not stop the run.
	 * <ul>
	 * <li>React gives an AFT the cost when the element is on and the AFT is reactive: a crops AFT for N, an
	 * irrigated crops AFT for irrigation, a pasture AFT for stocking, either for other intensity.</li>
	 * <li>A file gives it when its header has the AFT's column, matched as core matches it (any case, quotes
	 * and spaces ignored), or for irrigation the shared {@value #SHARED_IRRIGATION_COLUMN} column; and it has
	 * a line after the header. Only those two lines are read. A file that cannot be read gives nothing.</li>
	 * </ul>
	 */
	private List<String> costFileWarnings(ReactiveParameters parameters) {
		List<String> warnings = new ArrayList<>();
		for (ReactElement element : ReactElement.values()) {
			if (element == ReactElement.FORESTRY) {
				// Forestry's cost is the intensity cost, whose file and AFTs the other-intensity entry covers.
				continue;
			}
			List<String> charged = context.chargedAfts().getOrDefault(element, List.of());
			Set<String> handedOver = costsHandedOver(parameters, element);
			Map<String, List<Integer>> yearsUncovered = new LinkedHashMap<>();
			for (int year = context.firstYear(); year <= context.lastYear(); year++) {
				CoreFile file = coreFile(context.costFiles().getOrDefault(year, Map.of()).get(element));
				for (String label : charged) {
					boolean inFile = file.has(label)
							|| element == ReactElement.IRRIGATION && file.has(SHARED_IRRIGATION_COLUMN);
					if (!handedOver.contains(label) && !inFile) {
						yearsUncovered.computeIfAbsent(label, l -> new ArrayList<>()).add(year);
					}
				}
			}
			if (!yearsUncovered.isEmpty()) {
				warnings.add("CRAFTY-react: " + element.costFile() + " has no column (or no rows) for "
						+ whichInWhichYears(yearsUncovered) + ", and react does not hand over their " + element
						+ " costs, so the model charges them 0");
			}
		}
		return warnings;
	}

	/**
	 * The same for the model's capitals files: each capital the model has must get its value from react (an
	 * {@code <AFT>_suit} react hands over) or from that year's capitals file (its column, matched as core
	 * matches it, and a line after the header). Core gives a capital whose column is missing 0 in every
	 * cell. Any that get neither are named, with the years, in one warning. A year whose file core didn't
	 * find is skipped: core stops the run itself.
	 */
	private List<String> capitalsFileWarnings(ReactiveParameters parameters) {
		Set<String> handedOver = new HashSet<>();
		suitsHandedOver(parameters).forEach(aft -> handedOver.add(aft.label() + SUIT));
		Map<String, List<Integer>> yearsUncovered = new LinkedHashMap<>();
		for (int year = context.firstYear(); year <= context.lastYear(); year++) {
			Path path = context.modelCapitalsFiles().get(year);
			if (path == null) {
				continue;
			}
			CoreFile file = coreFile(path);
			for (String capital : context.capitals().keySet()) {
				if (!handedOver.contains(capital) && !file.has(capital)) {
					yearsUncovered.computeIfAbsent(capital, c -> new ArrayList<>()).add(year);
				}
			}
		}
		if (yearsUncovered.isEmpty()) {
			return List.of();
		}
		return List.of("CRAFTY-react: the model's capitals file has no column (or no rows) for "
				+ whichInWhichYears(yearsUncovered) + ", and react does not hand them over, so the model gives them 0");
	}

	/** For example {@code IntFodder, AF in 2020-2030; Solar in 2025}: names grouped by the years they share. */
	private static String whichInWhichYears(Map<String, List<Integer>> yearsByName) {
		Map<List<Integer>, List<String>> namesByYears = new LinkedHashMap<>();
		yearsByName.forEach((name, years) -> namesByYears.computeIfAbsent(years, y -> new ArrayList<>()).add(name));
		List<String> groups = new ArrayList<>();
		namesByYears.forEach((years, names) -> groups.add(String.join(", ", names) + " in " + yearRanges(years)));
		return String.join("; ", groups);
	}

	/** The AFTs whose cost for an element react hands to the model. */
	private Set<String> costsHandedOver(ReactiveParameters parameters, ReactElement element) {
		if (!context.isReactive(element)) {
			return Set.of();
		}
		List<AftReactParameters> afts = new ArrayList<>();
		switch (element) {
			case FERTILISER -> afts.addAll(parameters.reactive(LpjgType.CROPS));
			case IRRIGATION -> afts.addAll(irrigatedCrops(parameters));
			case OTHER_INTENSITY -> {
				afts.addAll(parameters.reactive(LpjgType.CROPS));
				afts.addAll(parameters.reactive(LpjgType.PASTURE));
			}
			case STOCKING -> afts.addAll(parameters.reactive(LpjgType.PASTURE));
		}
		Set<String> labels = new HashSet<>();
		afts.forEach(aft -> labels.add(aft.label()));
		return labels;
	}

	/** Years as ranges, for example {@code 2020-2025, 2030}. */
	static String yearRanges(List<Integer> years) {
		List<String> ranges = new ArrayList<>();
		int i = 0;
		while (i < years.size()) {
			int j = i;
			while (j + 1 < years.size() && years.get(j + 1) == years.get(j) + 1) {
				j++;
			}
			ranges.add(i == j ? String.valueOf(years.get(i)) : years.get(i) + "-" + years.get(j));
			i = j + 1;
		}
		return String.join(", ", ranges);
	}

	/** One of the model's input files, read once; a missing path (no file) gives nothing. */
	private CoreFile coreFile(Path path) {
		return path == null ? CoreFile.NONE : coreFiles.computeIfAbsent(path, CoreFile::read);
	}

	/**
	 * What the model reads from one of its cost or capitals files: the header's columns, as core matches
	 * them, and whether there is a line after the header.
	 */
	private record CoreFile(Set<String> columns, boolean hasRows) {

		static final CoreFile NONE = new CoreFile(Set.of(), false);

		/** Reads the first two lines, as core would see them. */
		static CoreFile read(Path file) {
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
				String header = reader.readLine();
				if (header == null) {
					return NONE;
				}
				Set<String> columns = new HashSet<>();
				for (String column : header.split(",", -1)) {
					// As core's CsvProcessors.buildIndex, so a column matches here exactly when it matches there.
					columns.add(column.trim().toUpperCase().replace("\"", ""));
				}
				String row = reader.readLine();
				return new CoreFile(columns, row != null && !row.isBlank());
			} catch (IOException e) {
				return NONE;
			}
		}

		/** Whether the file gives the cells values from a column, as core's loaders read it. */
		boolean has(String column) {
			return hasRows && columns.contains(column.toUpperCase());
		}
	}

	// ---- checks 1 and 5: suitability year folders and their columns ----

	private void checkSuitabilities(ReactiveParameters parameters, Map<LpjgType, Map<Integer, Path>> found) {
		for (LpjgType type : LpjgType.values()) {
			if (!inUse(parameters, type)) {
				continue;
			}
			Set<String> columns = new LinkedHashSet<>();
			if (type == LpjgType.FORESTRY) {
				// One table for all wood: every forestry AFT reads the same columns.
				config.forestryRotations().forEach(rotation -> columns.add(forestryColumn(rotation)));
			}
			for (AftReactParameters aft : parameters.reactive(type)) {
				if (type == LpjgType.CROPS) {
					CROP_LEVELS.forEach(level -> columns.add(aft.lpjgName() + level));
				} else if (type == LpjgType.PASTURE) {
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

	/** For the irrigated crops AFTs, when crops are in use (see {@link #run()}). */
	private void checkIrrigationFiles(List<AftReactParameters> irrigated) {
		if (irrigated.isEmpty()) {
			return;
		}
		Set<String> waterDemandColumns = new LinkedHashSet<>();
		for (AftReactParameters aft : irrigated) {
			WATER_DEMAND_LEVELS.forEach(level -> waterDemandColumns.add(aft.lpjgName() + level));
		}
		List<Path> waterDemandFiles = new ArrayList<>();
		List<Path> runoffFiles = new ArrayList<>();
		for (int year = context.firstYear(); year <= context.lastYear(); year++) {
			collectIfPresent(config.irrigationWaterDemandFile(project, context.scenario(), year), waterDemandFiles);
			collectIfPresent(config.runoffFile(project, context.scenario(), year), runoffFiles);
		}
		requireColumns(waterDemandFiles, waterDemandColumns);
		requireColumns(runoffFiles, List.of(RUNOFF));
	}

	private void collectIfPresent(Path file, List<Path> files) {
		if (Files.isRegularFile(file)) {
			files.add(file);
		} else {
			problems.add("File not found: " + file);
		}
	}

	// ---- check 7: forestry's initial rotations, on the grid ----

	/**
	 * With forestry on, each reactive forestry AFT's initial rotation (its {@code Other_intensity}, in years)
	 * must be one of {@code forestry.rotations}: its rotation starts there and moves along the grid. With
	 * forestry off react doesn't use it, so it isn't checked.
	 */
	private void checkInitialRotations(ReactiveParameters parameters) {
		if (!context.isReactive(ReactElement.FORESTRY)) {
			return;
		}
		List<Integer> rotations = config.forestryRotations();
		for (AftReactParameters aft : parameters.reactive(LpjgType.FORESTRY)) {
			double rotation = aft.initialRotation();
			if (rotations.stream().noneMatch(r -> r == rotation)) {
				problems.add("AFTsMetaData.csv: Other_intensity of " + aft.label() + " is its initial rotation in years,"
						+ " and must be one of forestry.rotations " + rotations + ", not " + rotation
						+ (rotation == 1.0 ? " (core reads a blank Other_intensity as 1.0)" : ""));
			}
		}
	}

	// ---- helpers ----

	/** A forestry file's column for a rotation of so many years: {@code harvest_age_<H>}. */
	public static String forestryColumn(int rotation) {
		return HARVEST_AGE + rotation;
	}

	/** Whether one of a land use's elements is switched on. */
	private boolean landUseOn(LpjgType type) {
		Set<ReactElement> elements = switch (type) {
			case CROPS -> CROP_ELEMENTS;
			case PASTURE -> PASTURE_ELEMENTS;
			case FORESTRY -> FORESTRY_ELEMENTS;
		};
		return elements.stream().anyMatch(context::isReactive);
	}

	/** Whether a land use is in use: one of its elements is switched on, and it has a reactive AFT. */
	private boolean inUse(ReactiveParameters parameters, LpjgType type) {
		return landUseOn(type) && !parameters.reactive(type).isEmpty();
	}

	/**
	 * The reactive crops AFTs that irrigate, whether or not irrigation is reactive. The switch decides
	 * whether react changes the water applied, not whether the AFT irrigates: with irrigation switched
	 * off, water applied is still min(runoff, demand), which sets the irrigation level in the yield
	 * surface. So the demand, runoff and irrigation cost files are needed either way, as long as crops are
	 * in use.
	 */
	private List<AftReactParameters> irrigatedCrops(ReactiveParameters parameters) {
		return parameters.reactive(LpjgType.CROPS).stream().filter(AftReactParameters::isIrrigated).toList();
	}

	/**
	 * Adds a problem for each file whose header lacks any of the columns, or both pairs of coordinates
	 * ({@code Lon} and {@code Lat}, or {@code x} and {@code y}, as {@link LpjFileReader} reads them).
	 */
	private void requireColumns(Collection<Path> files, Collection<String> columns) {
		for (Path file : files) {
			List<String> header = attempt(() -> ReactCsv.readHeader(file));
			if (header == null) {
				continue;
			}
			boolean noCoordinates = LpjFileReader.coordinateColumns(header) == null;
			List<String> missing = new ArrayList<>();
			if (noCoordinates) {
				missing.add(LpjFileReader.LON);
				missing.add(LpjFileReader.LAT);
			}
			columns.stream().filter(c -> !header.contains(c)).forEach(missing::add);
			if (!missing.isEmpty()) {
				problems.add(file + " is missing column(s) " + missing + (noCoordinates ? LpjFileReader.OR_X_AND_Y : ""));
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
