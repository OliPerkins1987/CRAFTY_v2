package de.cesr.crafty.landsymm;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.crafty.Cell;

/**
 * One non-economic service's yearly prices (50a PLAN sections 1 and 3.4): the sub-routine {@link NesPriceStep} runs
 * for each service that's switched on (today only Biodiversity). For each region r in year t:
 *
 * <pre>
 * B = spend(r, t) x 1e6                                        $, from the budget (million US$)
 * A = Σ area x c(owner)                                        counted area, ha
 * D = Σ area x c(owner) x q                                    counted land, weighted by the metric q
 * M = mean over the region's cells of area x q                 the mean pixel metric
 * P = rule(B, D, M, ...)                                       today B / max(D, M), 0 when B = 0
 * </pre>
 *
 * c is the owner's counted fraction ({@link CountedLand}). Working out the prices ({@link #prices}) changes nothing;
 * {@link #apply} then writes q into the allocation capital on every cell, sums the region's production of the service
 * from the capitals as just written (what SupplyUpdater will compute a moment later), and sets P as the region's
 * weight of the service for year t.
 */
final class NesPricing {

	/** The weight files' spend is in million US$. */
	static final double MILLION = 1e6;

	/** One region's price, worked out before anything is written. */
	record Price(NesPriceRule.Inputs in, double spendMusd, double price) {
	}

	/** One region's price once set, with the region's production of the service that it pays. */
	record Result(Price price, double production) {
		double paid() {
			return price.price() * production;
		}
	}

	private final String service;
	private final String allocationCapital;
	private final NesMetric metric;
	private final CountedLand counted;
	private final NesBudget budget;
	private final NesPriceRule rule;
	private final PlumInputStep.Weights weights;
	private final Map<String, Double> lastPrice = new ConcurrentHashMap<>();

	NesPricing(String service, String allocationCapital, NesMetric metric, CountedLand counted, NesBudget budget,
			NesPriceRule rule, PlumInputStep.Weights weights) {
		this.service = service;
		this.allocationCapital = allocationCapital;
		this.metric = metric;
		this.counted = counted;
		this.budget = budget;
		this.rule = rule;
		this.weights = weights;
	}

	String service() {
		return service;
	}

	String allocationCapital() {
		return allocationCapital;
	}

	CountedLand counted() {
		return counted;
	}

	/**
	 * The region's price for the year, from its cells in a stable order (so the sums are the same every run). Nothing
	 * is written. Stops if the rule's price isn't a number of 0 or more.
	 */
	Price price(int year, String region, List<Cell> cells) {
		double countedArea = 0;
		double metricArea = 0;
		double pixelMetric = 0;
		for (Cell c : cells) {
			double area = c.getArea();
			double f = counted.fraction(c.getOwner());
			double q = metric.on(c);
			countedArea += area * f;
			metricArea += area * f * q;
			pixelMetric += area * q;
		}
		double spendMusd = budget.spendMusd(region, year);
		NesPriceRule.Inputs in = new NesPriceRule.Inputs(service, region, year, spendMusd * MILLION, countedArea,
				metricArea, cells.isEmpty() ? 0 : pixelMetric / cells.size(), lastPrice.getOrDefault(region, Double.NaN));
		double price = rule.price(in);
		if (!Double.isFinite(price) || price < 0) {
			throw new CouplingInputException("region " + region + ", " + service + " in " + year + ": the price is "
					+ price + " (spend $" + in.budget() + ", counted land " + metricArea + ", mean pixel " + in.meanPixelMetric()
					+ "); it must be a number of 0 or more");
		}
		return new Price(in, spendMusd, price);
	}

	/**
	 * Writes the metric into the allocation capital on each of the region's cells, sums the region's production of the
	 * service from them, and sets the price as the region's weight for the year.
	 */
	Result apply(Price p, List<Cell> cells) {
		String region = p.in().region();
		int year = p.in().year();
		boolean byArea = ConfigLoader.config.use_cell_area;
		double production = 0;
		for (Cell c : cells) {
			c.setOneCapitals(allocationCapital, metric.on(c));
			double perUnit = c.productivity(c.getOwner(), service);
			production += byArea ? perUnit * c.getArea() : perUnit;
		}
		Map<Integer, Double> w = weights.of(region, service);
		if (w == null) {
			throw new IllegalStateException("region " + region + " has no " + service + " service");
		}
		w.put(year, p.price());
		lastPrice.put(region, p.price());
		return new Result(p, production);
	}
}
