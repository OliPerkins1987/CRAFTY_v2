package de.cesr.crafty.landsymm;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which PLUM crop each trade capital follows, from a PLUM_name column in Capitals.csv: filled for the trade
 * capitals, blank for every other capital, as in Services.csv. Only read with dynamic_trade_capitals on.
 *
 * Stops, listing every problem, if the column is missing, a name isn't a crop a trade capital can follow (PLUM's
 * production crops less pasture, wood and energy crops), a row that isn't a capital (Type = Capital) has a name, two
 * capitals name the same crop, or none names any.
 */
final class TradeCapitalMap {

	static final String PLUM_NAME = "PLUM_name";

	/** The crops a trade capital can follow. */
	static final List<String> TRADE_CROPS = PlumCommodities.PRODUCTION.stream()
			.filter(c -> !c.equals(PlumCommodities.PASTURE) && !c.equals(PlumCommodities.WOOD)
					&& !c.equals(PlumCommodities.ENERGY_CROPS))
			.toList();

	private final Path file;
	private final Map<String, String> cropByCapital; // in Capitals.csv order
	private final List<String> untagged;

	private TradeCapitalMap(Path file, Map<String, String> cropByCapital, List<String> untagged) {
		this.file = file;
		this.cropByCapital = Collections.unmodifiableMap(cropByCapital);
		this.untagged = List.copyOf(untagged);
	}

	/** Reads Capitals.csv's PLUM_name column, stopping with every problem found. */
	static TradeCapitalMap read(Path capitalsCsv) {
		CsvReader.Table table = CsvReader.readAll(capitalsCsv);
		String label = table.hasColumn("Label") ? "Label" : "Name";
		if (!table.hasColumn(PLUM_NAME)) {
			throw new CouplingInputException(capitalsCsv + " has no " + PLUM_NAME + " column. With dynamic_trade_capitals "
					+ "on it says which PLUM crop each trade capital follows (c3_cereals_trade: wheat, ...)");
		}
		List<String> problems = new ArrayList<>();
		Map<String, String> cropByCapital = new LinkedHashMap<>();
		Map<String, String> capitalByCrop = new LinkedHashMap<>();
		List<String> untagged = new ArrayList<>();
		for (int row = 0; row < table.size(); row++) {
			String capital = table.get(row, label);
			String crop = table.get(row, PLUM_NAME);
			String where = capitalsCsv.getFileName() + ", line " + table.lineNumber(row) + " (" + capital + "): ";
			if (crop.isEmpty()) {
				if (capital.endsWith("_trade")) {
					untagged.add(capital);
				}
				continue;
			}
			if (!TRADE_CROPS.contains(crop)) {
				problems.add(where + PLUM_NAME + " \"" + crop + "\" is not a crop a trade capital can follow " + TRADE_CROPS);
				continue;
			}
			String type = table.get(row, "Type");
			if (type != null && !type.equalsIgnoreCase("Capital")) {
				problems.add(where + "has a " + PLUM_NAME + " but its Type is " + type + "; only capitals can be trade "
						+ "capitals");
				continue;
			}
			String other = capitalByCrop.putIfAbsent(crop, capital);
			if (other != null) {
				problems.add(where + "names " + crop + ", which " + other + " already follows");
				continue;
			}
			cropByCapital.put(capital, crop);
		}
		if (cropByCapital.isEmpty() && problems.isEmpty()) {
			problems.add(capitalsCsv + ": no capital has a " + PLUM_NAME + ", so with dynamic_trade_capitals on there is "
					+ "nothing to update");
		}
		if (!problems.isEmpty()) {
			throw new CouplingInputException(problems);
		}
		return new TradeCapitalMap(capitalsCsv, cropByCapital, untagged);
	}

	Path file() {
		return file;
	}

	/** Trade capital to PLUM crop, in Capitals.csv order. */
	Map<String, String> cropByCapital() {
		return cropByCapital;
	}

	/** Capitals named like trade capitals (…_trade) that have no PLUM_name, so stay at their file values. */
	List<String> untagged() {
		return untagged;
	}
}
