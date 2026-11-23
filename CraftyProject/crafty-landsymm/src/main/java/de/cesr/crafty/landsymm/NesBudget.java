package de.cesr.crafty.landsymm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A non-economic service's spend, per region and year, in million US$ (50a PLAN section 3.2).
 *
 * Today it is each region's own weight of the service, from its service-weight file, copied once before the first
 * year: the NES step then writes each year's price over the weight, so the spend has to be kept apart. A policy or
 * institutional agent that sets spending would be another implementation (50a PLAN section 3.7).
 */
interface NesBudget {

	int MAX_LISTED = 20;

	/** The region's spend in the year, million US$. */
	double spendMusd(String region, int year);

	/**
	 * Copies every region's weights of the service, startYear to endYear, as the spend. Stops, listing them, on a
	 * region with no such service, a run year with no weight, or a weight that isn't a number of 0 or more.
	 */
	static NesBudget fromWeights(PlumInputStep.Weights weights, String service, Collection<String> regions,
			int startYear, int endYear) {
		Map<String, Map<Integer, Double>> spend = new TreeMap<>();
		List<String> problems = new ArrayList<>();
		for (String region : new TreeSet<>(regions)) {
			Map<Integer, Double> w = weights.of(region, service);
			if (w == null) {
				problems.add("region " + region + " has no " + service + " service to read its spend from");
				continue;
			}
			Map<Integer, Double> byYear = new HashMap<>();
			List<Integer> missing = new ArrayList<>();
			for (int year = startYear; year <= endYear; year++) {
				Double v = w.get(year);
				if (v == null) {
					missing.add(year);
				} else if (!Double.isFinite(v) || v < 0) {
					problems.add("region " + region + ", " + year + ": its " + service + " weight, the spend in million US$, "
							+ "is " + v + "; it must be a number of 0 or more");
				} else {
					byYear.put(year, v);
				}
			}
			if (!missing.isEmpty()) {
				problems.add("region " + region + " has no " + service + " weight (its spend) for " + missing.size()
						+ " of the run's years, from " + missing.get(0) + ": its weight file must have a row for every year "
						+ "from " + startYear + " (the loader reads rows in order from the start year)");
			}
			spend.put(region, byYear);
		}
		if (!problems.isEmpty()) {
			List<String> listed = new ArrayList<>(problems.subList(0, Math.min(MAX_LISTED, problems.size())));
			if (problems.size() > MAX_LISTED) {
				listed.add("and " + (problems.size() - MAX_LISTED) + " more");
			}
			throw new CouplingInputException(listed);
		}
		return (region, year) -> {
			Double v = spend.getOrDefault(region, Map.of()).get(year);
			if (v == null) {
				throw new CouplingInputException("No " + service + " spend for region " + region + " in " + year);
			}
			return v;
		};
	}
}
