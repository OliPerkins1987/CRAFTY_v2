package de.cesr.crafty.react.data;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * React's base unit costs, from {@code costs/global/global_costs.csv} ({@code Item,Cost}; other columns,
 * such as {@code Notes}, are ignored).
 *
 * React reads this file itself rather than through core's {@code GlobalCostData}, because the numbers
 * mean something different here: react multiplies them by quantities it works out (N applied, water
 * applied, stocking rate, husbandry), whereas core's global mode uses them as final costs and does not
 * keep the {@code Water} row at all.
 *
 * Items: {@value #NFERT} ($/kg N), {@value #WATER} ($/m³), {@value #STOCKING}, and one row per service.
 * Which items must be present depends on which elements are switched on; the caller checks that with
 * {@link #missing(Collection)}.
 */
public final class ReactBaseCosts {

	public static final String ITEM = "Item";
	public static final String COST = "Cost";

	public static final String NFERT = "Nfert";
	public static final String WATER = "Water";
	public static final String STOCKING = "Stocking";

	private final Path file;
	private final Map<String, Double> costs;

	private ReactBaseCosts(Path file, Map<String, Double> costs) {
		this.file = file;
		this.costs = costs;
	}

	public static ReactBaseCosts load(Path file) {
		ReactCsv.Table table = ReactCsv.readAll(file);
		table.requireColumns(ITEM, COST);

		Map<String, Double> costs = new LinkedHashMap<>();
		for (int row = 0; row < table.rowCount(); row++) {
			String where = file + ", line " + table.lineNumber(row) + ": ";
			String item = table.get(row, ITEM);
			if (item.isEmpty()) {
				throw new ReactInputException(where + ITEM + " is blank");
			}
			double cost = table.number(row, COST);
			if (cost < 0) {
				throw new ReactInputException(where + "the cost of " + item + " is negative (" + cost + ")");
			}
			if (costs.put(item, cost) != null) {
				throw new ReactInputException(where + item + " appears more than once");
			}
		}
		return new ReactBaseCosts(file, costs);
	}

	public boolean has(String item) {
		return costs.containsKey(item);
	}

	/** The cost of an item, which must be in the file. */
	public double get(String item) {
		Double cost = costs.get(item);
		if (cost == null) {
			throw new ReactInputException(file + " has no row for " + item);
		}
		return cost;
	}

	/** The listed items that the file has no row for, in the order given. */
	public List<String> missing(Collection<String> items) {
		List<String> missing = new ArrayList<>();
		for (String item : items) {
			if (!has(item)) {
				missing.add(item);
			}
		}
		return missing;
	}

	public Path file() {
		return file;
	}
}
