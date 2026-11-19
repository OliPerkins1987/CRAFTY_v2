package de.cesr.crafty.landsymm;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.function.Consumer;

import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.dataLoader.serivces.ServiceSet;

/**
 * Right after PlumInputStep: each market service's stock cost for the year, from the year's PLUM rows (the
 * calculation is in {@link StockCosts}). It runs in every year, the start year included (on the calibration's
 * final-year rows, as the prices do), with use_stock_costs on; otherwise it does nothing.
 *
 * The rates go to core through ProductionCostUpdater.setStockCosts, which Cell.stockCost reads whenever a cell's
 * utility is worked out this year: management, giving up and competition. Every paying service's rate, charged or
 * not, is recorded in plum_coupling/stock_costs.csv.
 */
final class StockCostStep extends CouplerStep {

	static final String STOCK_COSTS = "stock_costs.csv";
	static final List<String> STOCK_COSTS_HEADER = List.of("Year", "PLUM_year", "Service", "PLUM_price_name",
			"World_stock", "World_production", "World_imports", "World_exports", "Consumption", "Stock_ratio",
			"Critical_ratio", "Stock_cost", "Rate");

	private static final CustomLogger LOGGER = new CustomLogger(StockCostStep.class);

	private final PlumYear plumYear; // null when off
	private final StockCosts stockCosts;
	private final CouplingOutputs outputs;
	private final Consumer<double[]> sink;

	/**
	 * Starts stock_costs.csv afresh.
	 *
	 * @param sink where the year's rates go, indexed like CRAFTY's services: ProductionCostUpdater::setStockCosts
	 */
	StockCostStep(PlumYear plumYear, StockCosts stockCosts, CouplingOutputs outputs, Consumer<double[]> sink) {
		this.plumYear = plumYear;
		this.stockCosts = stockCosts;
		this.outputs = outputs;
		this.sink = sink;
		outputs.start(STOCK_COSTS, STOCK_COSTS_HEADER);
	}

	private StockCostStep() {
		this.plumYear = null;
		this.stockCosts = null;
		this.outputs = null;
		this.sink = null;
	}

	/** The step with use_stock_costs off, or in a calibration run. */
	static StockCostStep off() {
		return new StockCostStep();
	}

	@Override
	protected void yearStep(int year) {
		if (plumYear == null) {
			return;
		}
		PlumYearOutputs rows = plumYear.rows(year);
		List<StockCosts.Rate> rates = stockCosts.rates(rows);
		sink.accept(StockCosts.asArray(rates, ServiceSet.getServicesList()));

		List<List<String>> out = new ArrayList<>();
		StringJoiner charged = new StringJoiner(", ");
		for (StockCosts.Rate r : rates) {
			out.add(List.of(String.valueOf(year), String.valueOf(rows.year()), r.service(), r.commodity(),
					ProductionFile.format(r.stock()), ProductionFile.format(r.production()),
					ProductionFile.format(r.imports()), ProductionFile.format(r.exports()),
					ProductionFile.format(r.consumption()),
					r.consumption() > 0 ? ProductionFile.format(r.stockRatio()) : "",
					ProductionFile.format(stockCosts.criticalRatio()), ProductionFile.format(stockCosts.stockCost()),
					ProductionFile.format(r.rate())));
			if (r.rate() > 0) {
				charged.add(r.service() + " " + ProductionFile.format(r.rate()));
			}
		}
		outputs.append(STOCK_COSTS, out);
		LOGGER.info(year + ": stock costs from PLUM's " + rows.year() + " stocks: "
				+ (charged.length() == 0 ? "none charged" : "charged per unit on " + charged) + " (" + rates.size()
				+ " services can pay, above a stock ratio of " + ProductionFile.format(stockCosts.criticalRatio()) + ")");
	}
}
