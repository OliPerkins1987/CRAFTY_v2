package de.cesr.crafty.core.dataLoader.costs;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.dataLoader.CsvProcessors;

public class GlobalCostData {
	private static final CustomLogger LOGGER = new CustomLogger(GlobalCostData.class);

	private double nfertUnitCost = 0.0;
	private double stockingUnitCost = 0.0;
	private Map<String, Double> intensityCosts = new HashMap<>();
	// Transport cost per unit of production, keyed by service: the <service>_transport rows
	private Map<String, Double> transportCosts = new HashMap<>();

	/** The suffix that marks a transport cost row: {@code <service>_transport}. */
	public static final String TRANSPORT_SUFFIX = "_transport";

	public GlobalCostData(Path globalCostsCsv) {
		Map<String, List<String>> csv = CsvProcessors.ReadAsaHash(globalCostsCsv);
		if (csv == null) {
			LOGGER.fatal("Failed to read global_costs.csv from: " + globalCostsCsv);
			return;
		}
		List<String> items = csv.get("Item");
		List<String> costs = csv.get("Cost");
		if (items == null || costs == null) {
			LOGGER.fatal("global_costs.csv must have 'Item' and 'Cost' columns");
			return;
		}
		/*
		 * Most rows are per-service intensity costs, keyed by service name. A few are
		 * unit costs for a specific input and must be kept out of that map, or they
		 * would be looked up against a service's productivity level and silently
		 * mis-applied if a service ever shared their name.
		 */
		for (int i = 0; i < items.size(); i++) {
			String item = items.get(i).trim();
			double cost = Double.parseDouble(costs.get(i).trim());

			if (item.equalsIgnoreCase("Nfert")) {
				nfertUnitCost = cost;
				continue;
			}
			if (item.equalsIgnoreCase("Stocking")) {
				stockingUnitCost = cost;
				continue;
			}
			if (item.equalsIgnoreCase("Water")) {
				/*
				 * The irrigation unit cost. Core never applies it: irrigation costs arrive
				 * per cell as finished $/ha, and this row is consumed upstream when those
				 * files are generated. Excluded here, and deliberately not stored.
				 */
				continue;
			}
			if (item.endsWith(TRANSPORT_SUFFIX)) {
				// Kept out of the intensity map, where it would never match a service name.
				transportCosts.put(item.substring(0, item.length() - TRANSPORT_SUFFIX.length()), cost);
				continue;
			}

			intensityCosts.put(item, cost);
		}
		LOGGER.info("Loaded global costs: Nfert=" + nfertUnitCost + ", Stocking=" + stockingUnitCost
				+ ", intensity items=" + intensityCosts.keySet() + ", transport items=" + transportCosts.keySet());
	}

	public double getNfertUnitCost() {
		return nfertUnitCost;
	}

	public double getStockingUnitCost() {
		return stockingUnitCost;
	}

	public Map<String, Double> getIntensityCosts() {
		return intensityCosts;
	}

	/** Transport cost per unit of production, by service (the {@code <service>_transport} rows). */
	public Map<String, Double> getTransportCosts() {
		return transportCosts;
	}
}
