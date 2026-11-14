package de.cesr.crafty.landsymm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * CRAFTY's production for PLUM: {@code <production folder>/<year>/production.csv}, then an empty {@code done} beside
 * it, which PLUM waits for before reading (PLUM's CraftyProductionReader).
 *
 * The file has the header {@code Country,Commodity,Production} and one row per PLUM country and production commodity
 * ({@link PlumCommodities#PRODUCTION}, in that order), zeros included, with the countries in name order. Nothing is
 * quoted: PLUM splits on commas, so a country name can't contain one (the region key checks this). Values are in
 * PLUM's units (Mt; Mt dry matter for pasture; million m3 for wood) and written in Java's shortest exact form, so PLUM
 * reads back exactly the number CRAFTY computed.
 *
 * It also reads a production file back and compares two: a scenario run's start year against its calibration run's
 * (the 1990 check).
 */
public final class ProductionFile {

	public static final String FILE = "production.csv";
	static final String COUNTRY = "Country";
	static final String COMMODITY = "Commodity";
	static final String PRODUCTION = "Production";
	static final String HEADER = COUNTRY + "," + COMMODITY + "," + PRODUCTION;

	/** In the 1990 check, two values differ if they are more than this far apart, relative to the larger. */
	static final double BASELINE_TOLERANCE = 0.001;

	private static final int MAX_LISTED = 20;
	private static final int LARGEST_LISTED = 10;

	private ProductionFile() {
	}

	/**
	 * PLUM's production by country and commodity, from each region's supply of each market service in CRAFTY's units:
	 * Q(country, k) = the sum, over the market services s sent as k, of supply(region, s) x s's PLUM_quantity_factor.
	 * Every country in the key gets every commodity, 0 where no service supplies it.
	 *
	 * Stops, listing every case, on a supply that is missing, NaN, infinite or below 0, on a region in the key with no
	 * supply, and on a region with supply that the key lacks.
	 *
	 * @return country to commodity to quantity, countries in name order and commodities in PLUM's order
	 */
	static Map<String, Map<String, Double>> aggregate(Map<String, Map<String, Double>> supplyByRegion,
			ServiceCommodityMap services, RegionCountryKey key) {
		List<String> problems = new ArrayList<>();
		for (String region : new TreeSet<>(supplyByRegion.keySet())) {
			if (key.country(region) == null) {
				problems.add("Region " + region + " has supply but no PLUM country in the key");
			}
		}
		Map<String, Map<String, Double>> production = new TreeMap<>();
		for (String region : key.regions()) {
			String country = key.country(region);
			Map<String, Double> byCommodity = new LinkedHashMap<>();
			PlumCommodities.PRODUCTION.forEach(k -> byCommodity.put(k, 0.0));
			production.put(country, byCommodity);
			Map<String, Double> supply = supplyByRegion.get(region);
			if (supply == null) {
				problems.add("Region " + region + " (" + country + ") has no supply: CRAFTY has no cells for it");
				continue;
			}
			for (ServiceCommodityMap.Link link : services.links()) {
				Double value = supply.get(link.service());
				if (value == null) {
					problems.add(region + ": no supply of " + link.service());
				} else if (!Double.isFinite(value) || value < 0) {
					problems.add(region + ", " + link.service() + ": supply is " + value + ", but it must be 0 or more");
				} else {
					byCommodity.merge(link.commodity(), value * link.quantityFactor(), Double::sum);
				}
			}
		}
		if (!problems.isEmpty()) {
			throw new CouplingInputException(capped("CRAFTY's production can't be sent to PLUM", problems));
		}
		return production;
	}

	/**
	 * Writes a year's production: {@code <productionDir>/<year>/production.csv} through a temporary file moved into
	 * place, then the empty {@code done}. Stops if that year's {@code done} is already there, so no run writes over
	 * another's year.
	 *
	 * @return the file written
	 */
	static Path write(Path productionDir, int year, Map<String, Map<String, Double>> production) {
		Path dir = productionDir.resolve(String.valueOf(year));
		Path done = DoneFileWaiter.doneFile(productionDir, year);
		if (Files.exists(done)) {
			throw new CouplingInputException(done + " already exists, so " + year + "'s production has been written "
					+ "already, by this run or another. Each run writes into its own production folder");
		}
		StringBuilder text = new StringBuilder(HEADER).append('\n');
		production.forEach((country, byCommodity) -> byCommodity.forEach((commodity, value) -> text.append(country)
				.append(',').append(commodity).append(',').append(format(value)).append('\n')));

		Path file = dir.resolve(FILE);
		Path temporary = dir.resolve(FILE + ".tmp");
		try {
			Files.createDirectories(dir);
			Files.writeString(temporary, text, StandardCharsets.UTF_8);
			move(temporary, file);
			Files.createFile(done);
		} catch (IOException e) {
			throw new CouplingInputException("Could not write " + file + ": " + e);
		}
		return file;
	}

	private static void move(Path from, Path to) throws IOException {
		try {
			Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** A value as the coupler writes it: Java's shortest exact form, with -0.0 written as 0.0. */
	static String format(double value) {
		return value == 0 ? "0.0" : Double.toString(value);
	}

	/**
	 * Reads a production file, the coupler's or any other in the same format. Stops, listing every problem, on a
	 * value that isn't a number of 0 or more and on a repeated country and commodity.
	 *
	 * @return country to commodity to quantity, countries in name order and commodities in file order
	 */
	static Map<String, Map<String, Double>> read(Path file) {
		Map<String, Map<String, Double>> production = new TreeMap<>();
		List<String> problems = new ArrayList<>();
		CsvReader.forEachRow(file, List.of(COUNTRY, COMMODITY, PRODUCTION), (line, v) -> {
			double value;
			try {
				value = CsvReader.parseNumber(file, line, PRODUCTION, v[2]);
			} catch (CouplingInputException e) {
				problems.addAll(e.getProblems());
				return;
			}
			if (!Double.isFinite(value) || value < 0) {
				problems.add(file + ", line " + line + ": " + PRODUCTION + " must be a number of 0 or more, got " + v[2]);
				return;
			}
			if (production.computeIfAbsent(v[0], c -> new LinkedHashMap<>()).put(v[1], value) != null) {
				problems.add(file + ", line " + line + ": " + v[0] + ", " + v[1] + " appears more than once");
			}
		});
		if (!problems.isEmpty()) {
			throw new CouplingInputException(capped("Production file " + file + " can't be read", problems));
		}
		return production;
	}

	/** Compares a run's production with the calibration run's, pair by country and commodity (the 1990 check). */
	static Comparison compare(Map<String, Map<String, Double>> calibration, Map<String, Map<String, Double>> thisRun) {
		Set<String> countries = new TreeSet<>(calibration.keySet());
		countries.addAll(thisRun.keySet());
		Set<String> others = new TreeSet<>();
		calibration.values().forEach(m -> others.addAll(m.keySet()));
		thisRun.values().forEach(m -> others.addAll(m.keySet()));
		Set<String> commodities = new LinkedHashSet<>(PlumCommodities.PRODUCTION);
		commodities.addAll(others);

		List<Pair> pairs = new ArrayList<>();
		for (String country : countries) {
			Map<String, Double> c = calibration.getOrDefault(country, Map.of());
			Map<String, Double> r = thisRun.getOrDefault(country, Map.of());
			for (String commodity : commodities) {
				if (c.containsKey(commodity) || r.containsKey(commodity)) {
					pairs.add(new Pair(country, commodity, c.get(commodity), r.get(commodity)));
				}
			}
		}
		return new Comparison(pairs);
	}

	/** Whether two values differ by more than {@link #BASELINE_TOLERANCE} of the larger. Two zeros are equal. */
	static boolean differs(double a, double b) {
		return a != b && Math.abs(a - b) > BASELINE_TOLERANCE * Math.max(Math.abs(a), Math.abs(b));
	}

	/** One country and commodity in the 1990 check. A value is null where that file has no such row. */
	record Pair(String country, String commodity, Double calibration, Double thisRun) {

		/** A pair in only one file differs. */
		boolean differs() {
			return calibration == null || thisRun == null || ProductionFile.differs(calibration, thisRun);
		}

		/** This run's value less the calibration's, a missing value counting as 0. */
		double difference() {
			return (thisRun == null ? 0 : thisRun) - (calibration == null ? 0 : calibration);
		}

		/** The difference relative to the calibration's value; null where that is missing or 0. */
		Double relativeDifference() {
			return calibration == null || calibration == 0 ? null : difference() / calibration;
		}
	}

	/** The pairs of the 1990 check, with what is reported from them. */
	static final class Comparison {
		private final List<Pair> pairs;

		Comparison(List<Pair> pairs) {
			this.pairs = List.copyOf(pairs);
		}

		List<Pair> pairs() {
			return pairs;
		}

		/** Every pair is in both files with exactly the same value. */
		boolean identical() {
			return pairs.stream()
					.allMatch(p -> p.calibration() != null && p.thisRun() != null && p.calibration().equals(p.thisRun()));
		}

		/** How many pairs differ by more than 0.1%, or are in only one file. */
		long differing() {
			return pairs.stream().filter(Pair::differs).count();
		}

		/** The world total of each commodity in each file: {calibration, this run}. */
		Map<String, double[]> worldTotals() {
			Map<String, double[]> totals = new LinkedHashMap<>();
			for (Pair p : pairs) {
				double[] t = totals.computeIfAbsent(p.commodity(), k -> new double[2]);
				t[0] += p.calibration() == null ? 0 : p.calibration();
				t[1] += p.thisRun() == null ? 0 : p.thisRun();
			}
			return totals;
		}

		/** The warning logged when pairs differ. */
		String warning(int year, Path calibrationFile) {
			StringBuilder w = new StringBuilder();
			w.append("This run's ").append(year).append(" production differs from the calibration run's (")
					.append(calibrationFile).append(") in ").append(differing()).append(" of ").append(pairs.size())
					.append(" country x commodity pairs, by more than 0.1% or by being in only one file. PLUM's ")
					.append("calibrated state was made on the calibration run's production, so this run starts from ")
					.append("a different one. The run carries on.\n  Largest differences:");
			pairs.stream().filter(Pair::differs)
					.sorted(Comparator.comparingDouble((Pair p) -> Math.abs(p.difference())).reversed())
					.limit(LARGEST_LISTED)
					.forEach(p -> w.append("\n    ").append(p.country()).append(", ").append(p.commodity())
							.append(": calibration ").append(shown(p.calibration())).append(", this run ")
							.append(shown(p.thisRun())).append(percent(p.relativeDifference())));
			w.append("\n  World totals (calibration -> this run):");
			worldTotals().forEach((commodity, t) -> w.append("\n    ").append(commodity).append(": ").append(shown(t[0]))
					.append(" -> ").append(shown(t[1])).append(percent(t[0] == 0 ? null : (t[1] - t[0]) / t[0])));
			return w.toString();
		}

		/** Writes every pair: Country, Commodity, Calibration, This_run, Difference, Relative_difference, Differs. */
		void writeCsv(Path file) {
			StringBuilder text = new StringBuilder(
					"Country,Commodity,Calibration,This_run,Difference,Relative_difference,Differs\n");
			for (Pair p : pairs) {
				text.append(CouplingOutputs.csv(p.country())).append(',').append(CouplingOutputs.csv(p.commodity()))
						.append(',').append(p.calibration() == null ? "" : format(p.calibration())).append(',')
						.append(p.thisRun() == null ? "" : format(p.thisRun())).append(',').append(format(p.difference()))
						.append(',').append(p.relativeDifference() == null ? "" : format(p.relativeDifference()))
						.append(',').append(p.differs()).append('\n');
			}
			try {
				Files.createDirectories(file.getParent());
				Files.writeString(file, text, StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new CouplingInputException("Could not write " + file + ": " + e);
			}
		}

		private static String shown(Double value) {
			return value == null ? "none" : String.format(Locale.ROOT, "%.6g", value);
		}

		private static String percent(Double relative) {
			return relative == null ? "" : String.format(Locale.ROOT, " (%+.2f%%)", 100 * relative);
		}
	}

	/** The first problems, and how many more there are, after a line saying what they stop. */
	static List<String> capped(String what, List<String> problems) {
		List<String> listed = new ArrayList<>();
		listed.add(what + ", with " + problems.size() + " problem(s):");
		problems.stream().limit(MAX_LISTED).forEach(listed::add);
		if (problems.size() > MAX_LISTED) {
			listed.add("... and " + (problems.size() - MAX_LISTED) + " more");
		}
		return listed;
	}
}
