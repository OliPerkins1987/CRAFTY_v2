package de.cesr.crafty.landsymm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The stock cost of each market service in a year (49a), from PLUM's world rows for the commodity the service is priced
 * on (its PLUM_price_name), all from the same PLUM year:
 *
 * <pre>
 * consumption = production + imports - exports
 * stock ratio = stock / consumption
 * rate        = Stock_cost x stock ratio    when stock ratio > stock_target x stock_cost_threshold
 *             = 0                           otherwise
 * </pre>
 *
 * The stock, imports and exports are the world values in prices.txt. The production is the sum of Production over
 * every country in domestic.txt, or wood.txt for wood. The rate is a cost per unit produced, in CRAFTY's units (as the
 * transport costs are), and it's the same in every region. Core charges each AFT its Pays_stock_cost share of its
 * production x the rate (Cell.stockCost). Services that share a commodity share its rate: cane and beet pay sugar's,
 * and Timber and Fuelwood pay wood's.
 *
 * The commodity is the PLUM_price_name, not the PLUM_name, so that a service is charged on the stocks of what it is
 * priced on. The two are the same for every service but Pasture: PLUM keeps no stock of grass, so Pasture is charged
 * on ruminant stocks, at the ruminants rate per unit of grass. The services that pay are fixed when the run starts:
 * those whose PLUM_price_name has a row in prices.txt.
 */
final class StockCosts {

	/** One service's stock cost in a year: the commodity's world values, its stock ratio, and the rate. */
	record Rate(String service, String commodity, double stock, double production, double imports, double exports,
			double rate) {

		/** World consumption: production + imports - exports. */
		double consumption() {
			return production + imports - exports;
		}

		/** Stock / consumption. */
		double stockRatio() {
			return stock / consumption();
		}
	}

	private final List<ServiceCommodityMap.Link> payers;
	private final List<String> exempt;
	private final double stockCost;
	private final double target;
	private final double threshold;

	private StockCosts(List<ServiceCommodityMap.Link> payers, List<String> exempt, double stockCost, double target,
			double threshold) {
		this.payers = Collections.unmodifiableList(payers);
		this.exempt = Collections.unmodifiableList(exempt);
		this.stockCost = stockCost;
		this.target = target;
		this.threshold = threshold;
	}

	/**
	 * The services that pay are every market service whose PLUM_price_name has a row in the rows (the calibration's
	 * final year, at start-up). Stops if a number is out of range (core checks the config values too).
	 *
	 * @param stockCost the Stock_cost row of global_costs.csv, per unit produced
	 * @param target    stock_target, the target stock ratio
	 * @param threshold stock_cost_threshold: costs start above target x threshold
	 */
	static StockCosts create(ServiceCommodityMap services, PlumYearOutputs rows, double stockCost, double target,
			double threshold) {
		List<String> problems = new ArrayList<>();
		if (!Double.isFinite(stockCost) || stockCost < 0) {
			problems.add("use_stock_costs: Stock_cost in global_costs.csv must be a number of 0 or more, got: "
					+ stockCost);
		}
		if (!Double.isFinite(target) || target <= 0) {
			problems.add("use_stock_costs: stock_target must be a number more than 0, got: " + target);
		}
		if (!Double.isFinite(threshold) || threshold < 0) {
			problems.add("use_stock_costs: stock_cost_threshold must be a number of 0 or more, got: " + threshold);
		}
		if (!problems.isEmpty()) {
			throw new CouplingInputException(problems);
		}
		List<ServiceCommodityMap.Link> payers = new ArrayList<>();
		List<String> exempt = new ArrayList<>();
		for (ServiceCommodityMap.Link link : services.links()) {
			if (rows.priceNames().contains(link.priceName())) {
				payers.add(link);
			} else {
				exempt.add(link.service());
			}
		}
		return new StockCosts(payers, exempt, stockCost, target, threshold);
	}

	/** The market services that pay, in Services.csv order. */
	List<ServiceCommodityMap.Link> payers() {
		return payers;
	}

	/**
	 * The market services that never pay, because their PLUM_price_name has no row in prices.txt, in Services.csv
	 * order.
	 */
	List<String> exempt() {
		return exempt;
	}

	/** The Stock_cost per unit produced. */
	double stockCost() {
		return stockCost;
	}

	/** The stock ratio above which costs are charged: stock_target x stock_cost_threshold. */
	double criticalRatio() {
		return target * threshold;
	}

	/** The rate per unit produced for a stock ratio. */
	static double rate(double stockRatio, double criticalRatio, double stockCost) {
		return stockRatio > criticalRatio ? stockCost * stockRatio : 0.0;
	}

	/**
	 * Every paying service's rate from a year's rows. Stops, listing every problem, on a commodity with no stock,
	 * imports, exports or production in the rows, or with a stock above 0 and a consumption of 0 or less, which has no
	 * ratio.
	 */
	List<Rate> rates(PlumYearOutputs rows) {
		List<Rate> rates = new ArrayList<>();
		List<String> problems = new ArrayList<>();
		for (ServiceCommodityMap.Link link : payers) {
			String commodity = link.priceName();
			String what = commodity + " (" + link.service() + ")";
			String where = "use_stock_costs: PLUM's " + rows.year() + " rows";
			double stock = rows.stock(commodity);
			double imports = rows.worldImports(commodity);
			double exports = rows.worldExports(commodity);
			double production = rows.worldProduction(commodity);
			if (Double.isNaN(stock) || Double.isNaN(imports) || Double.isNaN(exports)) {
				problems.add(where + " have no stock, imports or exports for " + what + " in "
						+ PlumYearOutputs.PRICES_FILE + " (stock " + stock + ", imports " + imports + ", exports "
						+ exports + ")");
				continue;
			}
			if (Double.isNaN(production)) {
				problems.add(where + " have no world production for " + what);
				continue;
			}
			double consumption = production + imports - exports;
			if (consumption <= 0 && stock > 0) {
				problems.add(where + " have a stock of " + stock + " for " + what + " but a consumption (production "
						+ "+ imports - exports) of " + consumption + ", so the stock ratio can't be worked out");
				continue;
			}
			double rate = consumption <= 0 ? 0.0 : rate(stock / consumption, criticalRatio(), stockCost);
			rates.add(new Rate(link.service(), commodity, stock, production, imports, exports, rate));
		}
		if (!problems.isEmpty()) {
			throw new CouplingInputException(problems);
		}
		return rates;
	}

	/** The rates indexed like CRAFTY's services, with 0 for every service that pays none. */
	static double[] asArray(List<Rate> rates, List<String> craftyServices) {
		double[] array = new double[craftyServices.size()];
		for (Rate r : rates) {
			int i = craftyServices.indexOf(r.service());
			if (i >= 0) {
				array[i] = r.rate();
			}
		}
		return array;
	}
}
