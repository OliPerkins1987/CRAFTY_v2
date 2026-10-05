package de.cesr.crafty.react.decisions;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import de.cesr.crafty.react.data.ReactInputException;
import de.cesr.crafty.react.data.ReactRunContext;

/**
 * What each service react needs is worth this year, in every decision unit, in $ per unit of the service:
 * per tonne for crops and pasture, per m³ for forestry.
 *
 * The price is the service's weight in the unit's region and year. In price-explicit utility the weight is a
 * price in $, and react uses it as it is, with no normalisation (phase 3 plan, Q1). It is looked up once
 * per region and copied into an array by unit, so the decision loop reads it like any other per-unit value.
 *
 * Only the services asked for are looked up (in phase 3, the services of Prospect AFTs when fertiliser is
 * reactive), so a project needs no weight for a service whose AFTs never use a price. The lookup is done as
 * each year starts, not when react starts, because a run coupled to PLUM sets each year's weights during
 * the run.
 *
 * Nothing changes after {@link #forYear}, so it can be read from several threads. The arrays are handed out
 * as they are held: callers must not change them.
 */
public final class YearPrices {

	private final int year;
	private final Map<String, double[]> byService;

	private YearPrices(int year, Map<String, double[]> byService) {
		this.year = year;
		this.byService = byService;
	}

	/**
	 * Looks up this year's prices.
	 *
	 * @param services the services that need a price; a service named twice is looked up once
	 * @param source   where prices come from: in a run, {@link ReactRunContext#prices()}, which reads the
	 *                 service weights from core
	 * @throws ReactInputException naming the service, region and year, if a price is missing or is not a
	 *                             number
	 */
	public static YearPrices forYear(int year, Collection<String> services, DecisionUnits units,
			ReactRunContext.PriceSource source) {
		Map<String, double[]> byService = new LinkedHashMap<>();
		for (String service : services) {
			if (byService.containsKey(service)) {
				continue;
			}
			Map<String, Double> byRegion = new HashMap<>();
			double[] prices = new double[units.size()];
			for (int unit = 0; unit < units.size(); unit++) {
				prices[unit] = byRegion.computeIfAbsent(units.region(unit),
						region -> lookUp(source, service, region, year));
			}
			byService.put(service, prices);
		}
		return new YearPrices(year, Collections.unmodifiableMap(byService));
	}

	private static double lookUp(ReactRunContext.PriceSource source, String service, String region, int year) {
		double price;
		try {
			price = source.price(service, region, year);
		} catch (ReactInputException e) {
			throw new ReactInputException(noPrice(service, region, year) + ": " + e.getMessage(), e);
		}
		if (!Double.isFinite(price)) {
			throw new ReactInputException(noPrice(service, region, year) + ": its weight is " + price);
		}
		return price;
	}

	private static String noPrice(String service, String region, int year) {
		return "CRAFTY-react has no price for " + service + " in region " + region + " in " + year;
	}

	public int year() {
		return year;
	}

	/** The services looked up, in the order they were first asked for. */
	public Set<String> services() {
		return byService.keySet();
	}

	/**
	 * A service's price in every unit ($/t for crops and pasture, $/m³ for forestry), indexed by unit.
	 *
	 * @throws ReactInputException if the service was not looked up this year
	 */
	public double[] price(String service) {
		double[] prices = byService.get(service);
		if (prices == null) {
			throw new ReactInputException("CRAFTY-react looked up no price for " + service + " in " + year
					+ "; it priced " + byService.keySet());
		}
		return prices;
	}
}
