package de.cesr.crafty.landsymm;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

import de.cesr.crafty.core.cli.Config;
import de.cesr.crafty.core.utils.file.PathTools;

/**
 * The coupler's start-up checks, run by {@link MainLandSymm} in three rounds so that a wrong set-up stops before
 * CRAFTY loads its cells:
 * <ol>
 * <li>{@link #configReport(Config)}: the config alone, before anything loads;</li>
 * <li>{@link #dataReport}: Services.csv, the GIS key, and the production and PLUM paths and years, once the
 * project paths and the scenario are known;</li>
 * <li>{@link #afterLoadProblems}: cheap checks that need the loaded cells.</li>
 * </ol>
 * Each round returns its problems and warnings rather than stopping, so every problem can be reported at once and
 * each check can be tested.
 */
public final class CouplingStartupCheck {

	private static final int MAX_LISTED = 20;

	/** A round's problems (which stop the run) and warnings, and what the data round loaded. */
	public static final class Report {
		private final List<String> problems = new ArrayList<>();
		private final List<String> warnings = new ArrayList<>();
		private ServiceCommodityMap services;
		private RegionCountryKey key;

		public List<String> problems() {
			return problems;
		}

		public List<String> warnings() {
			return warnings;
		}

		/** Services.csv's PLUM links, if they were read without problems; null otherwise. */
		public ServiceCommodityMap services() {
			return services;
		}

		/** The region-country key, if it was read without problems; null otherwise. */
		public RegionCountryKey key() {
			return key;
		}
	}

	private CouplingStartupCheck() {
	}

	/** The settings a coupled run needs, checked before anything loads. */
	public static Report configReport(Config c) {
		Report r = new Report();
		if (!c.coupled_with_plum) {
			r.problems.add("coupled_with_plum is false. MainLandSymm only runs coupled runs; use MainHeadless for a "
					+ "standalone CRAFTY run, or set coupled_with_plum: true");
		}
		if (c.use_price_feedback) {
			r.problems.add("use_price_feedback is true. In a coupled run PLUM sets the prices, and CRAFTY's demand-supply "
					+ "factor would multiply them; set use_price_feedback: false");
		}
		if (c.global_price_feedback) {
			r.problems.add("global_price_feedback is true. It does nothing once use_price_feedback is off, but a coupled "
					+ "config must have it off too; set global_price_feedback: false");
		}
		if (c.initial_demand_supply_equilibrium) {
			r.problems.add("initial_demand_supply_equilibrium is true. Demand plays no part in a coupled run; set "
					+ "initial_demand_supply_equilibrium: false");
		}
		if (!c.use_explicit_price_utility) {
			r.problems.add("use_explicit_price_utility is false. Coupled prices are $ values, which only price-explicit "
					+ "utility uses; set use_explicit_price_utility: true");
		}
		if (!c.regionalisation) {
			r.problems.add("regionalisation is false. CRAFTY's regions are PLUM's countries; set regionalisation: true");
		}
		if (c.crafty_production_path.isBlank()) {
			r.problems.add("crafty_production_path is empty: it says where CRAFTY writes its production for PLUM");
		}
		if (!c.plum_calibration_run) {
			if (c.plum_calibration_path.isBlank()) {
				r.problems.add("plum_calibration_path is empty: a scenario run needs its calibration run's PLUM output");
			}
			if (c.plum_calibration_final_year <= 0) {
				r.problems.add("plum_calibration_final_year is not set: a scenario run needs the calibration's last PLUM "
						+ "year, which is PLUM's start-year state");
			}
			if (c.calibration_production_path.isBlank()) {
				r.problems.add("calibration_production_path is empty: a scenario run needs its calibration run's "
						+ "production folder");
			}
			if (c.plum_output_path.isBlank()) {
				r.problems.add("plum_output_path is empty: a scenario run needs this run's PLUM output folder");
			}
		}
		if (c.dynamic_trade_capitals) {
			r.warnings.add("dynamic_trade_capitals is true: until the net-export rule is in (48g), the stand-in rule "
					+ "sets the trade capitals");
		}
		if (c.plum_consumption && !(c.use_transport_costs && c.spatial_production_costs)) {
			r.warnings.add("plum_consumption is true but transport isn't charged by export share (use_transport_costs "
					+ "and spatial_production_costs), so PLUM's consumption has no effect");
		}
		return r;
	}

	/**
	 * Services.csv, the region key, and the production and PLUM paths and years, checked once the project paths and
	 * the scenario are known but before the cells load.
	 *
	 * @param servicesCsv     Services.csv (ProjectLoader's service metadata)
	 * @param gisFile         the GIS file (see {@link #locateGisFile(Config)}); null if none was found
	 * @param startYear       the scenario's first year
	 * @param scenarioEndYear the scenario's last year
	 */
	public static Report dataReport(Config c, Path servicesCsv, Path gisFile, int startYear, int scenarioEndYear) {
		Report r = new Report();
		try {
			r.services = ServiceCommodityMap.read(servicesCsv);
		} catch (CouplingInputException e) {
			r.problems.addAll(e.getProblems());
		}
		if (gisFile == null) {
			r.problems.add("No GIS file, so no region key: set gis_path, or put the regions file under the project's GIS "
					+ "folder");
		} else {
			try {
				r.key = RegionCountryKey.read(gisFile);
			} catch (CouplingInputException e) {
				r.problems.addAll(e.getProblems());
			}
		}

		if (!c.crafty_production_path.isBlank()) {
			Path done = DoneFileWaiter.doneFile(Paths.get(c.crafty_production_path), startYear);
			if (Files.exists(done)) {
				r.problems.add(done + " already exists. Each run writes its production into its own folder; start this "
						+ "run in a new folder");
			}
		}

		if (!c.plum_calibration_run) {
			if (!c.plum_calibration_path.isBlank() && c.plum_calibration_final_year > 0) {
				Path calibration = Paths.get(c.plum_calibration_path);
				Path done = DoneFileWaiter.doneFile(calibration, c.plum_calibration_final_year);
				if (!Files.exists(done)) {
					r.problems.add("The calibration isn't finished: no " + done
							+ ". A scenario run starts from a finished calibration run");
				}
				for (String file : List.of(PlumYearOutputs.PRICES_FILE, PlumYearOutputs.DOMESTIC_FILE,
						PlumYearOutputs.WOOD_FILE)) {
					if (!Files.isRegularFile(calibration.resolve(file))) {
						r.problems.add("The calibration has no " + calibration.resolve(file));
					}
				}
			}
			if (!c.calibration_production_path.isBlank()) {
				Path production = Paths.get(c.calibration_production_path).resolve(String.valueOf(startYear))
						.resolve("production.csv");
				if (!Files.isRegularFile(production)) {
					r.problems.add("The calibration run has no " + production
							+ ", which this run's start year is compared with");
				}
			}
			if (c.plum_calibration_final_year > 0 && c.plum_calibration_final_year <= startYear) {
				r.problems.add("plum_calibration_final_year (" + c.plum_calibration_final_year
						+ ") must be later than the start year (" + startYear + ")");
			}
			if (c.coupled_end_year != 0 && (c.coupled_end_year <= startYear || c.coupled_end_year > scenarioEndYear)) {
				r.problems.add("coupled_end_year (" + c.coupled_end_year + ") must be 0 or a year from " + (startYear + 1)
						+ " to the scenario's end year, " + scenarioEndYear);
			}
		}
		return r;
	}

	/**
	 * Checks that need the loaded cells: CRAFTY read the same GIS file as the key, kept its regions, and has no
	 * region the key lacks.
	 *
	 * @param key            the key from {@link #dataReport}
	 * @param loadedGisFile  the GIS file CRAFTY read (GisLoader.getGisFile())
	 * @param loadedRegions  CRAFTY's regions (CellsLoader.regions)
	 * @param regionalised   whether CRAFTY is still regionalised (CellsLoader.regionalisation)
	 */
	public static List<String> afterLoadProblems(RegionCountryKey key, Path loadedGisFile,
			Collection<String> loadedRegions, boolean regionalised) {
		List<String> problems = new ArrayList<>();
		if (!regionalised) {
			problems.add("CRAFTY fell back to a single region (usually because regional demand files are missing), "
					+ "but CRAFTY's regions must be PLUM's countries");
		}
		if (loadedGisFile == null || !samePath(key.file(), loadedGisFile)) {
			problems.add("CRAFTY read its regions from " + loadedGisFile + " but the coupler's key came from "
					+ key.file());
		}
		TreeSet<String> unknown = new TreeSet<>(loadedRegions);
		unknown.removeAll(key.regions());
		if (regionalised && !unknown.isEmpty()) {
			List<String> first = unknown.stream().limit(MAX_LISTED).toList();
			problems.add("CRAFTY region(s) with no PLUM country in the key: " + first
					+ (unknown.size() > MAX_LISTED ? " and " + (unknown.size() - MAX_LISTED) + " more" : ""));
		}
		return problems;
	}

	/**
	 * The GIS file CRAFTY will read, found as core's GisLoader finds it: gis_path if that is a file, otherwise the
	 * first file under the project's GIS folder. Null if there is none.
	 */
	public static Path locateGisFile(Config c) {
		if (c.gis_path != null && !c.gis_path.isBlank() && Paths.get(c.gis_path).toFile().isFile()) {
			return Paths.get(c.gis_path);
		}
		List<Path> found = PathTools.fileFilter(true, File.separator + "GIS" + File.separator);
		return found == null || found.isEmpty() ? null : found.get(0);
	}

	private static boolean samePath(Path a, Path b) {
		return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
	}
}
