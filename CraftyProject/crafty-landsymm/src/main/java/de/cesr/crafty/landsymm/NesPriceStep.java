package de.cesr.crafty.landsymm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.crafty.Cell;
import de.cesr.crafty.core.utils.general.DeterministicAggregation;

/**
 * Straight before SupplyUpdater: the year's prices of the non-economic services (NES) that are switched on, from
 * CRAFTY's own land use (50a PLAN). Each service is a {@link NesPricing}; today only Biodiversity
 * (dynamic_biodiversity_price), all under dynamic_nes_prices. With no service on, or in a calibration run, the step
 * does nothing.
 *
 * Where it sits: after CapitalUpdater, which reloads the capitals file the step writes over, and LandMaskUpdater, so
 * the counted land sees the year's masks; before SupplyUpdater, so the year's production uses the capital it writes;
 * and before RegionsModelRunnerUpdater, whose utilities read the price and whose region files record it.
 * ServicesUpdater's earlier snapshot of the weights (used only for marginal utility, which explicit-price utility
 * doesn't use) still holds the spend.
 *
 * It runs in every year, the start year included, on the land use at that point: last year's competition and this
 * year's masks. Every service's price in every region is worked out before anything is written, so a problem stops
 * the run with no capital or weight changed. Every term is recorded in plum_coupling/nes_prices.csv.
 */
final class NesPriceStep extends CouplerStep {

	static final String NES_PRICES = "nes_prices.csv";
	static final List<String> NES_PRICES_HEADER = List.of("Year", "Service", "Region", "Spend_MUSD", "Budget",
			"Counted_area", "Metric_area", "Mean_pixel_metric", "Floor_used", "Price", "Production", "Paid", "Paid_share");

	private static final CustomLogger LOGGER = new CustomLogger(NesPriceStep.class);

	private final List<NesPricing> services; // empty when off
	private final CouplingOutputs outputs;
	private final ProductionStep.Regions regions;
	private final Set<String> floorWarned = ConcurrentHashMap.newKeySet(); // service|region

	/** Starts nes_prices.csv afresh. */
	NesPriceStep(List<NesPricing> services, CouplingOutputs outputs, ProductionStep.Regions regions) {
		this.services = List.copyOf(services);
		this.outputs = outputs;
		this.regions = regions;
		outputs.start(NES_PRICES, NES_PRICES_HEADER);
	}

	private NesPriceStep() {
		this.services = List.of();
		this.outputs = null;
		this.regions = null;
	}

	/** The step with dynamic_nes_prices off, no service on, or in a calibration run. */
	static NesPriceStep off() {
		return new NesPriceStep();
	}

	/** The services priced each year, in order. */
	List<String> services() {
		return services.stream().map(NesPricing::service).toList();
	}

	@Override
	protected void yearStep(int year) {
		if (services.isEmpty()) {
			return;
		}
		// Each region's cells once, in a stable order, so every sum is the same from run to run
		Map<String, List<Cell>> ordered = new TreeMap<>();
		Map<String, Collection<Cell>> cells = regions.cells();
		cells.entrySet().parallelStream()
				.map(e -> Map.entry(e.getKey(), DeterministicAggregation.cellsInStableOrder(e.getValue())))
				.toList().forEach(e -> ordered.put(e.getKey(), e.getValue()));

		// Every price first, so a problem changes nothing
		List<String> problems = new ArrayList<>();
		List<List<NesPricing.Price>> prices = new ArrayList<>();
		for (NesPricing s : services) {
			List<NesPricing.Price> servicePrices = ordered.entrySet().parallelStream().map(e -> {
				try {
					return s.price(year, e.getKey(), e.getValue());
				} catch (CouplingInputException ex) {
					synchronized (problems) {
						problems.addAll(ex.getProblems());
					}
					return null;
				}
			}).toList();
			prices.add(servicePrices);
		}
		if (!problems.isEmpty()) {
			throw new CouplingInputException(problems.stream().sorted().toList());
		}

		List<List<String>> rows = new ArrayList<>();
		for (int i = 0; i < services.size(); i++) {
			NesPricing s = services.get(i);
			List<NesPricing.Result> results = prices.get(i).parallelStream()
					.map(p -> s.apply(p, ordered.get(p.in().region()))).toList();
			results.forEach(r -> rows.add(row(r)));
			log(year, s.service(), results);
		}
		outputs.append(NES_PRICES, rows);
	}

	private static List<String> row(NesPricing.Result r) {
		NesPricing.Price p = r.price();
		NesPriceRule.Inputs in = p.in();
		return List.of(String.valueOf(in.year()), in.service(), in.region(), ProductionFile.format(p.spendMusd()),
				ProductionFile.format(in.budget()), ProductionFile.format(in.countedArea()),
				ProductionFile.format(in.metricArea()), ProductionFile.format(in.meanPixelMetric()),
				String.valueOf(floorUsed(in)), ProductionFile.format(p.price()), ProductionFile.format(r.production()),
				ProductionFile.format(r.paid()), in.budget() > 0 ? ProductionFile.format(r.paid() / in.budget()) : "");
	}

	/** Whether the floor set the price: there was spend, and less counted land than one average pixel. */
	private static boolean floorUsed(NesPriceRule.Inputs in) {
		return in.belowFloor() && in.budget() > 0;
	}

	private static String format(String pattern, double value) {
		return String.format(Locale.ROOT, pattern, value);
	}

	private void log(int year, String service, List<NesPricing.Result> results) {
		double spend = 0;
		double countedArea = 0;
		double paid = 0;
		int floor = 0;
		List<Double> price = new ArrayList<>();
		for (NesPricing.Result r : results) {
			NesPriceRule.Inputs in = r.price().in();
			spend += in.budget();
			countedArea += in.countedArea();
			paid += r.paid();
			price.add(r.price().price());
			if (floorUsed(in)) {
				floor++;
				if (floorWarned.add(service + "|" + in.region())) {
					LOGGER.warn(year + ": " + service + ": region " + in.region() + " has less counted land ("
							+ ProductionFile.format(in.metricArea()) + ") than one average pixel ("
							+ ProductionFile.format(in.meanPixelMetric()) + "), so its price is its spend over one average pixel"
							+ " (warned once per region)");
				}
			}
		}
		price.sort(Double::compare);
		double median = price.isEmpty() ? Double.NaN
				: price.size() % 2 == 1 ? price.get(price.size() / 2)
						: (price.get(price.size() / 2 - 1) + price.get(price.size() / 2)) / 2;
		LOGGER.info(year + ": " + service + " prices set in " + results.size() + " regions from their spend ($"
				+ format("%.3f", spend / 1e9) + " bn; counted land " + format("%.1f", countedArea / 1e6) + " Mha; median $"
				+ format("%.3f", median) + "/ha; "
				+ (spend > 0 ? format("%.1f", 100 * paid / spend) + "% paid out" : "nothing to pay") + "; " + floor
				+ " region(s) at the floor)");
	}
}
