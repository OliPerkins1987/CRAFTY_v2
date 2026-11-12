package de.cesr.crafty.landsymm;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which PLUM commodity each CRAFTY market service is, from the PLUM columns of Services.csv:
 * <ul>
 * <li>{@code PLUM_name}: the PLUM production commodity the service is sent as (several services may share one,
 * such as sugar cane and beet, or timber and fuelwood, and are then summed);</li>
 * <li>{@code PLUM_quantity_factor}: CRAFTY's unit to PLUM's (t to Mt is 0.000001);</li>
 * <li>{@code PLUM_price_name}: the PLUM price the service follows (pasture follows ruminants);</li>
 * <li>{@code PLUM_price_factor}: PLUM's price to CRAFTY's, used by {@code factor} price scaling.</li>
 * </ul>
 * Only market goods ({@code Market_good = Yes}) are coupled. A non-market good must leave the PLUM columns blank,
 * and every PLUM production commodity except the optional ones must have at least one market service, or PLUM
 * would be sent 0 of it. Every problem in the file is reported together.
 */
public final class ServiceCommodityMap {

	/** One market service's link to PLUM. */
	public record Link(String service, String commodity, double quantityFactor, String priceName,
			double priceFactor) {
	}

	static final String MARKET_GOOD = "Market_good";
	static final String PLUM_NAME = "PLUM_name";
	static final String QUANTITY_FACTOR = "PLUM_quantity_factor";
	static final String PRICE_FACTOR = "PLUM_price_factor";
	static final String PRICE_NAME = "PLUM_price_name";
	static final List<String> PLUM_COLUMNS = List.of(PLUM_NAME, QUANTITY_FACTOR, PRICE_FACTOR, PRICE_NAME);

	private final Path file;
	private final Map<String, Link> links; // by service, in file order

	private ServiceCommodityMap(Path file, Map<String, Link> links) {
		this.file = file;
		this.links = Collections.unmodifiableMap(links);
	}

	/** Reads Services.csv, stopping with every problem found. */
	public static ServiceCommodityMap read(Path servicesCsv) {
		CsvReader.Table table = CsvReader.readAll(servicesCsv);
		List<String> problems = new ArrayList<>();

		String label = table.hasColumn("Label") ? "Label" : "Name";
		List<String> required = new ArrayList<>(List.of(label, MARKET_GOOD));
		required.addAll(PLUM_COLUMNS);
		List<String> missing = required.stream().filter(c -> !table.hasColumn(c)).toList();
		if (!missing.isEmpty()) {
			throw new CouplingInputException(servicesCsv + " has no column(s) " + missing
					+ ", which the coupling needs");
		}

		Map<String, Link> links = new LinkedHashMap<>();
		for (int i = 0; i < table.size(); i++) {
			final int row = i;
			String service = table.get(row, label);
			String where = servicesCsv.getFileName() + ", line " + table.lineNumber(row) + " (" + service + "): ";
			String marketGood = table.get(row, MARKET_GOOD);
			if (marketGood.equalsIgnoreCase("No")) {
				List<String> filled = PLUM_COLUMNS.stream().filter(c -> !table.get(row, c).isEmpty()).toList();
				if (!filled.isEmpty()) {
					problems.add(where + "not a market good, but has " + filled
							+ ". Only market goods are coupled; blank these or set Market_good to Yes");
				}
				continue;
			}
			if (!marketGood.equalsIgnoreCase("Yes")) {
				problems.add(where + MARKET_GOOD + " must be Yes or No, got \"" + marketGood + "\"");
				continue;
			}

			String commodity = table.get(row, PLUM_NAME);
			if (!PlumCommodities.PRODUCTION.contains(commodity)) {
				problems.add(where + PLUM_NAME + " \"" + commodity + "\" is not one of PLUM's production commodities "
						+ PlumCommodities.PRODUCTION);
			}
			String priceName = table.get(row, PRICE_NAME);
			if (!PlumCommodities.PRICE_NAMES.contains(priceName)) {
				problems.add(where + PRICE_NAME + " \"" + priceName + "\" is not one of PLUM's prices "
						+ PlumCommodities.PRICE_NAMES);
			}
			double quantityFactor = positiveNumber(table.get(row, QUANTITY_FACTOR), where + QUANTITY_FACTOR, problems);
			double priceFactor = positiveNumber(table.get(row, PRICE_FACTOR), where + PRICE_FACTOR, problems);
			if (links.containsKey(service)) {
				problems.add(where + "the service appears twice");
			}
			links.put(service, new Link(service, commodity, quantityFactor, priceName, priceFactor));
		}

		for (String commodity : PlumCommodities.PRODUCTION) {
			if (PlumCommodities.OPTIONAL_PRODUCTION.contains(commodity)) {
				continue;
			}
			if (links.values().stream().noneMatch(l -> l.commodity().equals(commodity))) {
				problems.add(servicesCsv.getFileName() + ": no market service has " + PLUM_NAME + " " + commodity
						+ ", so PLUM would be sent 0 of it");
			}
		}

		if (!problems.isEmpty()) {
			throw new CouplingInputException(problems);
		}
		return new ServiceCommodityMap(servicesCsv, links);
	}

	private static double positiveNumber(String text, String what, List<String> problems) {
		try {
			double value = Double.parseDouble(text.trim());
			if (Double.isFinite(value) && value > 0) {
				return value;
			}
		} catch (NumberFormatException e) {
			// reported below
		}
		problems.add(what + " must be a number greater than 0, got \"" + text + "\"");
		return Double.NaN;
	}

	public Path file() {
		return file;
	}

	/** Every market service's link, in Services.csv order. */
	public List<Link> links() {
		return List.copyOf(links.values());
	}

	/** The service's link, or null if it isn't a coupled market service. */
	public Link link(String service) {
		return links.get(service);
	}

	/** The market services sent as a PLUM commodity, in Services.csv order; empty if none. */
	public List<Link> servicesFor(String commodity) {
		return links.values().stream().filter(l -> l.commodity().equals(commodity)).toList();
	}
}
