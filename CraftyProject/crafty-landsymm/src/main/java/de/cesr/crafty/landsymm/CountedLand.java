package de.cesr.crafty.landsymm;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import de.cesr.crafty.core.crafty.Aft;

/**
 * How much of each owner's land counts towards a non-economic service's counted land (50a PLAN section 3.2): the
 * {@code <service>_counted_land} column of AFTsMetaData.csv (e.g. {@code biodiversity_counted_land}), 0-1, blank = 0,
 * for AFT and mask rows alike. An owner the file doesn't list (CRAFTY's built-in Abandoned) and a cell with no owner
 * count 0.
 */
final class CountedLand {

	private final String column;
	private final Map<String, Double> fractions; // label -> fraction, every listed row

	private CountedLand(String column, Map<String, Double> fractions) {
		this.column = column;
		this.fractions = Collections.unmodifiableMap(fractions);
	}

	/** The AFTsMetaData column for a service: its name in lower case + "_counted_land". */
	static String column(String service) {
		return service.toLowerCase(Locale.ROOT) + "_counted_land";
	}

	/**
	 * Reads the service's column, stopping with every problem found: no such column, a value that isn't blank or a
	 * number from 0 to 1, or no row above 0 (nothing would ever count).
	 */
	static CountedLand read(Path aftsMetaData, String service) {
		String column = column(service);
		CsvReader.Table table = CsvReader.readAll(aftsMetaData);
		if (!table.hasColumn("Label")) {
			throw new CouplingInputException(aftsMetaData + " has no Label column");
		}
		if (!table.hasColumn(column)) {
			throw new CouplingInputException(aftsMetaData + " has no " + column + " column. With " + service
					+ " prices on, it gives each AFT and mask the share of its land (0 to 1, blank = 0) that counts towards "
					+ service + "'s counted land, e.g. 1 for the Nonex AFTs and UL");
		}
		List<String> problems = new ArrayList<>();
		Map<String, Double> fractions = new HashMap<>();
		for (int i = 0; i < table.size(); i++) {
			String label = table.get(i, "Label");
			String text = table.get(i, column);
			if (text.isEmpty()) {
				fractions.put(label, 0.0);
				continue;
			}
			double value;
			try {
				value = Double.parseDouble(text);
			} catch (NumberFormatException e) {
				value = Double.NaN;
			}
			if (!(value >= 0 && value <= 1)) {
				problems.add(aftsMetaData.getFileName() + ", line " + table.lineNumber(i) + " (" + label + "): " + column
						+ " must be blank or a number from 0 to 1, got \"" + text + "\"");
				continue;
			}
			fractions.put(label, value);
		}
		if (problems.isEmpty() && fractions.values().stream().noneMatch(f -> f > 0)) {
			problems.add(aftsMetaData.getFileName() + ": no row has a " + column + " above 0, so no land would ever count "
					+ "and every region would be priced at the floor");
		}
		if (!problems.isEmpty()) {
			throw new CouplingInputException(problems);
		}
		return new CountedLand(column, fractions);
	}

	/** A table for tests: label -> fraction. */
	static CountedLand of(String service, Map<String, Double> fractions) {
		return new CountedLand(column(service), new HashMap<>(fractions));
	}

	String column() {
		return column;
	}

	/** The share of the owner's land that counts: 0 for no owner or an owner the file doesn't list. */
	double fraction(Aft owner) {
		return owner == null ? 0 : fractions.getOrDefault(owner.getLabel(), 0.0);
	}

	/** The listed owners whose land counts (fraction above 0), by label. */
	Map<String, Double> counted() {
		Map<String, Double> counted = new TreeMap<>();
		fractions.forEach((label, f) -> {
			if (f > 0) {
				counted.put(label, f);
			}
		});
		return counted;
	}
}
