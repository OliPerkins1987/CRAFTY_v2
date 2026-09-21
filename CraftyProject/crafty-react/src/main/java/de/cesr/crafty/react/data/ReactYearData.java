package de.cesr.crafty.react.data;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.cesr.crafty.core.cli.CustomLogger;

/**
 * One model year's LPJ-GUESS data, as arrays indexed by pixel and held in real units.
 *
 * Everything is keyed by **LPJ-GUESS name**, not by AFT: several AFTs share a crop (ten AFTs use six
 * crops in the sandbox), so keying by AFT would hold the same array many times. An AFT reaches its own
 * arrays through {@link AftReactParameters#lpjgName()}.
 *
 * Only what is needed is read. An element's switch decides whether react <em>changes</em> that
 * management, not whether its data is needed, so irrigation demand, runoff and the irrigation cost index
 * are read whenever a reactive AFT irrigates, even with irrigation switched off: water applied is still
 * {@code min(runoff, demand)}, which sets the irrigation level in the yield surface. Capitals are the
 * exception, since a capital is only read when its element reacts.
 *
 * Nothing changes after loading, so later phases can read it from several threads.
 */
public final class ReactYearData {

	private static final CustomLogger LOGGER = new CustomLogger(ReactYearData.class);

	private final int year;
	private final Map<String, float[][]> cropYields;
	private final Map<String, float[]> pastureNpp;
	private final Map<String, float[][]> irrigationDemand;
	private final float[] runoff;
	private final Map<String, float[]> capitals;

	private ReactYearData(int year, Map<String, float[][]> cropYields, Map<String, float[]> pastureNpp,
			Map<String, float[][]> irrigationDemand, float[] runoff, Map<String, float[]> capitals) {
		this.year = year;
		this.cropYields = cropYields;
		this.pastureNpp = pastureNpp;
		this.irrigationDemand = irrigationDemand;
		this.runoff = runoff;
		this.capitals = capitals;
	}

	/** A year with nothing loaded, for a run where no element is reactive. */
	static ReactYearData empty(int year) {
		return new ReactYearData(year, Map.of(), Map.of(), Map.of(), new float[0], Map.of());
	}

	/** Reads one year's files. The files were found, and their headers checked, at startup. */
	static ReactYearData load(ReactConfig config, ReactRunContext context, ReactStartupCheck.Result checked, int year) {
		long start = System.nanoTime();
		Path project = context.projectPath();
		LpjGrid grid = checked.cellKey().grid();
		ReactiveParameters parameters = checked.parameters();
		double yieldFactor = config.yieldFileUnits().toTonnesPerHectare();
		double waterFactor = config.irrigationFileUnits().toCubicMetresPerHectare();

		Map<String, float[][]> cropYields = new LinkedHashMap<>();
		Set<String> crops = lpjgNames(parameters.reactive(LpjgType.CROPS));
		if (!crops.isEmpty()) {
			cropYields.putAll(readByName(checked.suitabilityFiles().get(LpjgType.CROPS).get(year), grid, crops,
					ReactStartupCheck.CROP_LEVELS, yieldFactor));
		}

		Map<String, float[]> pastureNpp = new LinkedHashMap<>();
		Set<String> pastures = lpjgNames(parameters.reactive(LpjgType.PASTURE));
		if (!pastures.isEmpty()) {
			Path file = checked.suitabilityFiles().get(LpjgType.PASTURE).get(year);
			float[][] values = LpjFileReader.read(file, grid, List.copyOf(pastures), yieldFactor);
			int column = 0;
			for (String pasture : pastures) {
				pastureNpp.put(pasture, values[column++]);
			}
		}

		Map<String, float[][]> irrigationDemand = new LinkedHashMap<>();
		float[] runoff = new float[0];
		Set<String> irrigatedCrops = lpjgNames(checked.irrigatedCrops());
		if (!irrigatedCrops.isEmpty()) {
			irrigationDemand.putAll(readByName(config.irrigationDemandFile(project, context.scenario(), year), grid,
					irrigatedCrops, ReactStartupCheck.DEMAND_LEVELS, waterFactor));
			runoff = LpjFileReader.read(config.runoffFile(project, context.scenario(), year), grid,
					List.of(ReactStartupCheck.RUNOFF), waterFactor)[0];
		}

		Map<String, float[]> capitals = new LinkedHashMap<>();
		Set<String> capitalNames = parameters.capitalsNamed(context.reactive());
		if (!capitalNames.isEmpty()) {
			Path file = checked.capitalsFiles().get(year);
			float[][] values = LpjFileReader.read(file, grid, List.copyOf(capitalNames), 1.0);
			int column = 0;
			for (String capital : capitalNames) {
				capitals.put(capital, values[column++]);
			}
		}

		LOGGER.info(String.format("CRAFTY-react loaded year %d in %.2f s: crops %s, pasture %s, irrigation demand %s,"
				+ " runoff %s, capitals %s", year, (System.nanoTime() - start) / 1e9, cropYields.keySet(),
				pastureNpp.keySet(), irrigationDemand.keySet(), runoff.length > 0 ? "yes" : "not needed",
				capitals.keySet()));

		return new ReactYearData(year, Collections.unmodifiableMap(cropYields), Collections.unmodifiableMap(pastureNpp),
				Collections.unmodifiableMap(irrigationDemand), runoff, Collections.unmodifiableMap(capitals));
	}

	private static Set<String> lpjgNames(Iterable<AftReactParameters> afts) {
		Set<String> names = new LinkedHashSet<>();
		afts.forEach(aft -> names.add(aft.lpjgName()));
		return names;
	}

	/**
	 * Reads every name's levels from one file in a single pass, and hands back one array per level for
	 * each name. Reading the file once matters: an LPJ-GUESS year file has 62 500 rows and 80-odd
	 * columns, so a pass per crop would cost several seconds a year.
	 */
	private static Map<String, float[][]> readByName(Path file, LpjGrid grid, Set<String> names, List<String> levels,
			double factor) {
		List<String> columns = new ArrayList<>();
		for (String name : names) {
			for (String level : levels) {
				columns.add(name + level);
			}
		}
		float[][] values = LpjFileReader.read(file, grid, columns, factor);

		Map<String, float[][]> byName = new LinkedHashMap<>();
		int column = 0;
		for (String name : names) {
			float[][] forName = new float[levels.size()][];
			for (int level = 0; level < levels.size(); level++) {
				forName[level] = values[column++];
			}
			byName.put(name, forName);
		}
		return byName;
	}

	public int year() {
		return year;
	}

	/**
	 * A crop's yield at one N level, in t/ha, for every pixel.
	 *
	 * @param level one of {@link ReactStartupCheck#CROP_LEVELS}: {@code 0}, {@code 0200}, {@code 1000},
	 *              {@code i0}, {@code i0200}, {@code i1000}
	 */
	public float[] crop(String lpjgName, String level) {
		return column(cropYields, lpjgName, ReactStartupCheck.CROP_LEVELS, level, "crop yield");
	}

	/** A pasture service's NPP, in t/ha, for every pixel. */
	public float[] pasture(String lpjgName) {
		float[] values = pastureNpp.get(lpjgName);
		if (values == null) {
			throw new ReactInputException("Year " + year + " holds no pasture NPP for " + lpjgName);
		}
		return values;
	}

	/**
	 * A crop's irrigation demand at one N level, in m³/ha, for every pixel.
	 *
	 * @param level one of {@link ReactStartupCheck#DEMAND_LEVELS}: {@code i0}, {@code i0200}, {@code i1000}
	 */
	public float[] demand(String lpjgName, String level) {
		return column(irrigationDemand, lpjgName, ReactStartupCheck.DEMAND_LEVELS, level, "irrigation demand");
	}

	/** Runoff, in m³/ha, for every pixel. */
	public float[] runoff() {
		if (runoff.length == 0) {
			throw new ReactInputException("Year " + year + " holds no runoff: no reactive AFT irrigates");
		}
		return runoff;
	}

	/** A react capital's value for every pixel, as it was written. */
	public float[] capital(String name) {
		float[] values = capitals.get(name);
		if (values == null) {
			throw new ReactInputException("Year " + year + " holds no capital " + name);
		}
		return values;
	}

	/** The crops loaded, by LPJ-GUESS name. */
	public Set<String> crops() {
		return cropYields.keySet();
	}

	/** The pasture services loaded, by LPJ-GUESS name. */
	public Set<String> pastures() {
		return pastureNpp.keySet();
	}

	/** The crops whose irrigation demand was loaded. */
	public Set<String> irrigatedCrops() {
		return irrigationDemand.keySet();
	}

	/** The capitals loaded. */
	public Set<String> capitals() {
		return capitals.keySet();
	}

	private float[] column(Map<String, float[][]> byName, String lpjgName, List<String> levels, String level,
			String what) {
		float[][] values = byName.get(lpjgName);
		if (values == null) {
			throw new ReactInputException("Year " + year + " holds no " + what + " for " + lpjgName);
		}
		int index = levels.indexOf(level);
		if (index < 0) {
			throw new ReactInputException(what + " level " + level + " is not one of " + levels);
		}
		return values[index];
	}
}
