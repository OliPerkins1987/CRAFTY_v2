package de.cesr.crafty.landsymm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.crafty.Cell;

/**
 * After CapitalUpdater: with dynamic_trade_capitals on, each region's trade capitals from the year's PLUM rows (48d
 * PLAN section 5). It runs in every year, the start year included; with the switch off it does nothing.
 *
 * For each region and each trade capital in Capitals.csv's PLUM_name column, the rule ({@link TradeCapitalRule},
 * normally {@link SelfSufficiencyLogisticRule}) gets PLUM's production and domestic use of the capital's crop, the
 * region's value last year and this year's file value, and returns its value. The region's new value averages it in
 * over k years (trade_capital_averaging_years): (1 - 1/k) x last year's value + 1/k x the rule's value, starting from
 * the file value in the start year. The step writes the new value over every cell of the region, right after
 * CapitalUpdater has loaded the year's file. That also replaces the few edge cells holding a neighbouring country's
 * value, a downscaling error.
 *
 * A region's file value is the median of its cells' values, weighted by cell area: the country's own value, which
 * the edge cells don't move. The files on disk aren't touched; every value set is recorded in
 * plum_coupling/trade_capitals.csv.
 */
final class TradeCapitalStep extends CouplerStep {

	static final String TRADE_CAPITALS = "trade_capitals.csv";
	static final List<String> TRADE_CAPITALS_HEADER = List.of("Year", "Region", "Country", "Capital",
			"PLUM_commodity", "Production", "Domestic_use", "Self_sufficiency", "Slope", "Midpoint", "Averaging_years",
			"Last_value", "File_value", "Rule_value", "New_value");

	private static final CustomLogger LOGGER = new CustomLogger(TradeCapitalStep.class);

	private final PlumYear plumYear; // null when off
	private final TradeCapitalMap capitals;
	private final TradeCapitalRule rule;
	private final double slope;
	private final double midpoint;
	private final int averagingYears;
	private final RegionCountryKey key;
	private final CouplingOutputs outputs;
	private final ProductionStep.Regions regions;
	private final Map<String, Map<String, Double>> last = new HashMap<>(); // region, capital

	/**
	 * Starts trade_capitals.csv afresh.
	 *
	 * @param slope          the rule's slope, recorded in trade_capitals.csv
	 * @param midpoint       the rule's midpoint, recorded in trade_capitals.csv
	 * @param averagingYears k: each year the value moves 1/k of the way to the rule's value; 1 = no averaging
	 */
	TradeCapitalStep(PlumYear plumYear, TradeCapitalMap capitals, TradeCapitalRule rule, double slope, double midpoint,
			int averagingYears, RegionCountryKey key, CouplingOutputs outputs, ProductionStep.Regions regions) {
		this.plumYear = plumYear;
		this.capitals = capitals;
		this.rule = rule;
		this.slope = slope;
		this.midpoint = midpoint;
		this.averagingYears = averagingYears;
		this.key = key;
		this.outputs = outputs;
		this.regions = regions;
		outputs.start(TRADE_CAPITALS, TRADE_CAPITALS_HEADER);
	}

	private TradeCapitalStep() {
		this.plumYear = null;
		this.capitals = null;
		this.rule = null;
		this.slope = Double.NaN;
		this.midpoint = Double.NaN;
		this.averagingYears = 1;
		this.key = null;
		this.outputs = null;
		this.regions = null;
	}

	/** The step with dynamic_trade_capitals off, or in a calibration run. */
	static TradeCapitalStep off() {
		return new TradeCapitalStep();
	}

	@Override
	protected void yearStep(int year) {
		if (plumYear == null) {
			return;
		}
		PlumYearOutputs rows = plumYear.rows(year);
		Map<String, Collection<Cell>> cells = regions.cells();
		List<Update> updates = new ArrayList<>();
		List<String> problems = new ArrayList<>();
		for (String region : key.regions()) {
			String country = key.country(region);
			Collection<Cell> regionCells = cells.get(region);
			if (regionCells == null || regionCells.isEmpty()) {
				problems.add("Region " + region + " has no cells to set trade capitals on");
				continue;
			}
			for (Map.Entry<String, String> e : capitals.cropByCapital().entrySet()) {
				String capital = e.getKey();
				String crop = e.getValue();
				PlumYearOutputs.Domestic d = rows.domestic(country, crop);
				if (d == null) {
					problems.add("PLUM has no " + PlumYearOutputs.DOMESTIC_FILE + " row for " + country + ", " + crop
							+ " in " + rows.year() + ", which " + capital + " follows");
					continue;
				}
				double fileValue = weightedMedian(regionCells, capital);
				if (Double.isNaN(fileValue)) {
					problems.add("No cell in region " + region + " has a value of " + capital);
					continue;
				}
				double lastValue = last.getOrDefault(region, Map.of()).getOrDefault(capital, fileValue);
				double production = d.trade().production();
				TradeCapitalRule.Inputs in = new TradeCapitalRule.Inputs(region, country, capital, crop, production,
						production + d.trade().netImports(), lastValue, fileValue);
				double ruleValue = rule.next(in);
				if (!Double.isFinite(ruleValue) || ruleValue < 0 || ruleValue > 1) {
					problems.add("Region " + region + ", " + capital + ": the rule gave " + ruleValue
							+ "; a trade capital must be from 0 to 1");
					continue;
				}
				updates.add(new Update(in, ruleValue, averaged(lastValue, ruleValue, averagingYears), regionCells));
			}
		}
		if (!problems.isEmpty()) {
			throw new CouplingInputException(problems);
		}

		List<List<String>> recorded = new ArrayList<>();
		for (Update u : updates) {
			u.cells().parallelStream().forEach(c -> c.setOneCapitals(u.in().capital(), u.value()));
			last.computeIfAbsent(u.in().region(), r -> new HashMap<>()).put(u.in().capital(), u.value());
			TradeCapitalRule.Inputs in = u.in();
			recorded.add(List.of(String.valueOf(year), in.region(), in.country(), in.capital(), in.crop(),
					ProductionFile.format(in.production()), ProductionFile.format(in.domesticUse()),
					in.selfSufficiency() == Double.POSITIVE_INFINITY ? "Inf" : ProductionFile.format(in.selfSufficiency()),
					ProductionFile.format(slope), ProductionFile.format(midpoint), String.valueOf(averagingYears),
					ProductionFile.format(in.lastValue()), ProductionFile.format(in.fileValue()),
					ProductionFile.format(u.ruleValue()), ProductionFile.format(u.value())));
		}
		outputs.append(TRADE_CAPITALS, recorded);
		LOGGER.info(year + ": " + updates.size() + " trade capital values set from PLUM's " + rows.year() + " rows");
	}

	private record Update(TradeCapitalRule.Inputs in, double ruleValue, double value, Collection<Cell> cells) {
	}

	/** Last year's value moved 1/k of the way to the rule's value: (1 - 1/k) x last + 1/k x rule. */
	static double averaged(double last, double ruleValue, int k) {
		return (1 - 1.0 / k) * last + ruleValue / k;
	}

	/**
	 * The median of the cells' values of a capital, weighted by cell area: the value at which half the area lies
	 * below. Cells with no value are left out; NaN if none has one.
	 */
	static double weightedMedian(Collection<Cell> cells, String capital) {
		List<double[]> values = new ArrayList<>(cells.size());
		double total = 0;
		for (Cell c : cells) {
			double v = c.getCapitals().getOrDefault(capital, Double.NaN);
			if (Double.isNaN(v)) {
				continue;
			}
			values.add(new double[] { v, c.getArea() });
			total += c.getArea();
		}
		if (values.isEmpty()) {
			return Double.NaN;
		}
		values.sort(Comparator.comparingDouble(a -> a[0]));
		double cumulative = 0;
		for (double[] va : values) {
			cumulative += va[1];
			if (cumulative >= total / 2) {
				return va[0];
			}
		}
		return values.get(values.size() - 1)[0];
	}
}
