package de.cesr.crafty.landsymm;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One year of PLUM's outputs, as the coupler uses them: from {@code prices.txt}, each commodity's
 * {@code New export price} (the world price PLUM trades at the following year); from {@code domestic.txt} and
 * {@code wood.txt}, each country's production, net imports and import and export prices.
 *
 * PLUM appends a block to each file every year, so the files are read one line at a time and only the year's rows
 * are kept. Header names are trimmed (PLUM writes {@code Year, Country, Crop}). Values are kept as PLUM wrote them,
 * NaN included; whoever uses a value checks it. The read stops, listing every problem, on a missing file, a year
 * with no rows in a file, a commodity or country × crop that appears twice in the year (for example a restarted run
 * appending to the same files), or a value that isn't a number.
 */
public final class PlumYearOutputs {

	public static final String PRICES_FILE = "prices.txt";
	public static final String DOMESTIC_FILE = "domestic.txt";
	public static final String WOOD_FILE = "wood.txt";

	/**
	 * A country's trade in one commodity: production, net imports (negative for net exports; surplus counted as
	 * exports), and the import and export prices PLUM's country used that year.
	 */
	public record Trade(double production, double netImports, double importPrice, double exportPrice) {
	}

	/** A country's crop row in domestic.txt: its trade, and the ruminant feed taken from it (grass, for pasture). */
	public record Domestic(Trade trade, double ruminantFeed) {
	}

	/** A country's row in wood.txt: its trade, and its roundwood and fuelwood demand. */
	public record Wood(Trade trade, double roundwoodDemand, double woodfuelDemand) {
	}

	private final Path folder;
	private final int year;
	private final Map<String, Double> prices;
	private final Map<String, Map<String, Domestic>> domestic; // country, crop
	private final Map<String, Wood> wood; // country

	private PlumYearOutputs(Path folder, int year, Map<String, Double> prices,
			Map<String, Map<String, Domestic>> domestic, Map<String, Wood> wood) {
		this.folder = folder;
		this.year = year;
		this.prices = Collections.unmodifiableMap(prices);
		this.domestic = Collections.unmodifiableMap(domestic);
		this.wood = Collections.unmodifiableMap(wood);
	}

	/** Reads a year of PLUM's outputs from its output folder, stopping with every problem found. */
	public static PlumYearOutputs read(Path plumOutputFolder, int year) {
		List<String> problems = new ArrayList<>();
		Map<String, Double> prices = readPrices(plumOutputFolder.resolve(PRICES_FILE), year, problems);
		Map<String, Map<String, Domestic>> domestic = readDomestic(plumOutputFolder.resolve(DOMESTIC_FILE), year,
				problems);
		Map<String, Wood> wood = readWood(plumOutputFolder.resolve(WOOD_FILE), year, problems);
		if (!problems.isEmpty()) {
			throw new CouplingInputException(problems);
		}
		return new PlumYearOutputs(plumOutputFolder, year, prices, domestic, wood);
	}

	private static Map<String, Double> readPrices(Path file, int year, List<String> problems) {
		Map<String, Double> prices = new TreeMap<>();
		readYear(file, year, List.of("Year", "Crop", "New export price"), problems, (line, v) -> {
			if (prices.put(v[1], CsvReader.parseNumber(file, line, "New export price", v[2])) != null) {
				problems.add(file + ", line " + line + ": " + v[1] + " appears twice in " + year);
			}
		});
		return prices;
	}

	private static Map<String, Map<String, Domestic>> readDomestic(Path file, int year, List<String> problems) {
		Map<String, Map<String, Domestic>> rows = new TreeMap<>();
		List<String> columns = List.of("Year", "Country", "Crop", "Production", "Net_imports", "Import_price",
				"Export_price", "Rum_feed_amount");
		readYear(file, year, columns, problems, (line, v) -> {
			Trade trade = new Trade(number(file, line, columns, v, 3), number(file, line, columns, v, 4),
					number(file, line, columns, v, 5), number(file, line, columns, v, 6));
			Domestic row = new Domestic(trade, number(file, line, columns, v, 7));
			if (rows.computeIfAbsent(v[1], c -> new TreeMap<>()).put(v[2], row) != null) {
				problems.add(file + ", line " + line + ": " + v[1] + ", " + v[2] + " appears twice in " + year);
			}
		});
		return rows;
	}

	private static Map<String, Wood> readWood(Path file, int year, List<String> problems) {
		Map<String, Wood> rows = new TreeMap<>();
		List<String> columns = List.of("Year", "Country", "Production", "Net_imports", "Import_price", "Export_price",
				"RoundwoodDemand", "WoodfuelDemand");
		readYear(file, year, columns, problems, (line, v) -> {
			Trade trade = new Trade(number(file, line, columns, v, 2), number(file, line, columns, v, 3),
					number(file, line, columns, v, 4), number(file, line, columns, v, 5));
			Wood row = new Wood(trade, number(file, line, columns, v, 6), number(file, line, columns, v, 7));
			if (rows.put(v[1], row) != null) {
				problems.add(file + ", line " + line + ": " + v[1] + " appears twice in " + year);
			}
		});
		return rows;
	}

	private static double number(Path file, int line, List<String> columns, String[] values, int i) {
		return CsvReader.parseNumber(file, line, columns.get(i), values[i]);
	}

	/**
	 * Hands the year's rows of a file to the handler (column 0 must be Year). A problem with the file, or a year
	 * with no rows, is added to the list rather than thrown, so all three files are checked.
	 */
	private static void readYear(Path file, int year, List<String> columns, List<String> problems,
			CsvReader.RowHandler handler) {
		int[] found = { 0 };
		try {
			CsvReader.forEachRow(file, columns, (line, v) -> {
				if ((int) CsvReader.parseNumber(file, line, "Year", v[0]) == year) {
					found[0]++;
					handler.row(line, v);
				}
			});
		} catch (CouplingInputException e) {
			problems.addAll(e.getProblems());
			return;
		}
		if (found[0] == 0) {
			problems.add(file + " has no rows for " + year);
		}
	}

	public Path folder() {
		return folder;
	}

	public int year() {
		return year;
	}

	/** A commodity's New export price; NaN if prices.txt has no row for it. */
	public double price(String priceName) {
		return prices.getOrDefault(priceName, Double.NaN);
	}

	/** Every commodity in prices.txt for the year. */
	public Set<String> priceNames() {
		return prices.keySet();
	}

	/** The countries in domestic.txt for the year. */
	public Set<String> countries() {
		return domestic.keySet();
	}

	/** A country's crop row in domestic.txt, or null if there is none. */
	public Domestic domestic(String country, String crop) {
		Map<String, Domestic> crops = domestic.get(country);
		return crops == null ? null : crops.get(crop);
	}

	/** A country's row in wood.txt, or null if there is none. */
	public Wood wood(String country) {
		return wood.get(country);
	}

	/**
	 * A country's trade in the commodity a price name stands for: from wood.txt for wood, from domestic.txt
	 * otherwise. Null if there is no row.
	 */
	public Trade trade(String country, String priceName) {
		if (PlumCommodities.WOOD.equals(priceName)) {
			Wood w = wood(country);
			return w == null ? null : w.trade();
		}
		Domestic d = domestic(country, priceName);
		return d == null ? null : d.trade();
	}
}
