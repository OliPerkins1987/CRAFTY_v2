package de.cesr.crafty.landsymm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Each region's consumption of its market services, for transport export shares, from PLUM's domestic use (48d
 * PLAN section 4).
 *
 * PLUM's domestic use U of a commodity, in PLUM's units:
 * <ul>
 * <li>crops: Production + Net_imports in domestic.txt;</li>
 * <li>pasture: pasture Production x (1 - f), where f = max(0, -Net_imports) / Production for ruminants: the grass
 * inside exported ruminants counts as exported;</li>
 * <li>wood: Production + Net_imports in wood.txt.</li>
 * </ul>
 * A U below 0 can only come from rounding, so it counts as 0 (the raw value is still reported).
 *
 * In CRAFTY's units, per service: C(s) = U(c) / PLUM_quantity_factor(s) x S(s) / (sum of S over the services of c),
 * with S this year's supply before competition. Services that share a commodity (cane and beet, timber and fuelwood)
 * split its use by supply, equally where none of them supplies any.
 */
final class PlumConsumption {

	/** One market service's consumption, and what it was made from. */
	record ServiceConsumption(String service, String commodity, double plumUse, double supply, double consumption) {
	}

	private PlumConsumption() {
	}

	/**
	 * PLUM's domestic use of a production commodity in a country, in PLUM's units, as written (it may be slightly
	 * below 0 from rounding). Stops if a row it needs is missing.
	 */
	static double domesticUse(PlumYearOutputs rows, String country, String commodity) {
		String where = country + ", " + commodity + " in " + rows.year();
		if (PlumCommodities.WOOD.equals(commodity)) {
			PlumYearOutputs.Wood w = rows.wood(country);
			if (w == null) {
				throw new CouplingInputException("PLUM has no " + PlumYearOutputs.WOOD_FILE + " row for " + where);
			}
			return w.trade().production() + w.trade().netImports();
		}
		if (PlumCommodities.PASTURE.equals(commodity)) {
			PlumYearOutputs.Domestic pasture = rows.domestic(country, PlumCommodities.PASTURE);
			PlumYearOutputs.Domestic ruminants = rows.domestic(country, PlumCommodities.RUMINANTS);
			if (pasture == null || ruminants == null) {
				throw new CouplingInputException("PLUM has no " + PlumYearOutputs.DOMESTIC_FILE + " row for " + country
						+ ", " + (pasture == null ? PlumCommodities.PASTURE : PlumCommodities.RUMINANTS) + " in "
						+ rows.year() + ", which pasture's domestic use needs");
			}
			return pasture.trade().production() * (1 - ruminantExportShare(ruminants.trade()));
		}
		PlumYearOutputs.Domestic d = rows.domestic(country, commodity);
		if (d == null) {
			throw new CouplingInputException("PLUM has no " + PlumYearOutputs.DOMESTIC_FILE + " row for " + where);
		}
		return d.trade().production() + d.trade().netImports();
	}

	/** The share of a country's ruminant production it exports: max(0, -net imports) / production, at most 1. */
	static double ruminantExportShare(PlumYearOutputs.Trade ruminants) {
		if (ruminants.production() <= 0) {
			return 0;
		}
		return Math.min(1, Math.max(0, -ruminants.netImports()) / ruminants.production());
	}

	/**
	 * A region's consumption of each market service, in Services.csv order.
	 *
	 * @param supply the region's supply of each service now (core's regional supply, after SupplyUpdater)
	 */
	static List<ServiceConsumption> consumption(PlumYearOutputs rows, String country, ServiceCommodityMap services,
			Map<String, Double> supply) {
		List<String> problems = new ArrayList<>();
		List<ServiceConsumption> result = new ArrayList<>();
		for (String commodity : PlumCommodities.PRODUCTION) {
			List<ServiceCommodityMap.Link> links = services.servicesFor(commodity);
			if (links.isEmpty()) {
				continue;
			}
			double use;
			try {
				use = domesticUse(rows, country, commodity);
			} catch (CouplingInputException e) {
				problems.addAll(e.getProblems());
				continue;
			}
			double total = 0;
			for (ServiceCommodityMap.Link link : links) {
				Double s = supply.get(link.service());
				if (s == null) {
					problems.add(country + ": no supply of " + link.service() + " to split " + commodity + "'s use by");
				} else {
					total += s;
				}
			}
			for (ServiceCommodityMap.Link link : links) {
				double s = supply.getOrDefault(link.service(), 0.0);
				double share = total > 0 ? s / total : 1.0 / links.size();
				double consumption = Math.max(0, use) / link.quantityFactor() * share;
				result.add(new ServiceConsumption(link.service(), commodity, use, s, consumption));
			}
		}
		if (!problems.isEmpty()) {
			throw new CouplingInputException(problems);
		}
		return result;
	}

	/**
	 * The array core's ProductionCostUpdater.setConsumption takes: indexed like CRAFTY's service list, with 0 for
	 * services that aren't coupled.
	 */
	static double[] asArray(List<ServiceConsumption> consumption, List<String> craftyServices) {
		double[] array = new double[craftyServices.size()];
		for (ServiceConsumption c : consumption) {
			int i = craftyServices.indexOf(c.service());
			if (i < 0) {
				throw new CouplingInputException("Market service " + c.service() + " isn't in CRAFTY's service list "
						+ craftyServices);
			}
			array[i] = c.consumption();
		}
		return array;
	}
}
