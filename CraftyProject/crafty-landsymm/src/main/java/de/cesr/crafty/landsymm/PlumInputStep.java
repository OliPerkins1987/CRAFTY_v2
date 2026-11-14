package de.cesr.crafty.landsymm;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.crafty.Region;
import de.cesr.crafty.core.crafty.Service;
import de.cesr.crafty.core.dataLoader.land.CellsLoader;

/**
 * First in CRAFTY's year: the year's PLUM rows, and from them every region's market-service prices (48d PLAN
 * sections 1 and 3).
 * <ul>
 * <li>The rows: in the start year, the calibration's final year (PLUM's start-year state, read at start-up); in
 * year t after it, PLUM's year t - 1, once its done file is there. PLUM solves year t - 1 on CRAFTY's production for
 * t - 1, and "New export price" in that row is the world price for year t.</li>
 * <li>The countries in the rows must be CRAFTY's.</li>
 * <li>Each region's price for each market service, PriceScaling's factor x the region's PLUM price, goes into the
 * region's Service weights for the year, which price-explicit utility, ServicesUpdater, the region outputs and react
 * all read. Under index scaling the start year's prices are the file weights again, to rounding. Non-market services
 * keep their file weights.</li>
 * <li>The rows are kept in the shared PlumYear for TradeCapitalStep and ConsumptionStep, later in the year.</li>
 * </ul>
 * It records every price set in plum_coupling/prices_received.csv, and each region's ungrazed grass in
 * ungrazed_grass.csv.
 */
final class PlumInputStep extends CouplerStep {

	static final String PRICES_RECEIVED = "prices_received.csv";
	static final String UNGRAZED_GRASS = "ungrazed_grass.csv";
	static final List<String> PRICES_RECEIVED_HEADER = List.of("Year", "Region", "Country", "Service",
			"PLUM_price_name", "World_price", "Import_markup", "Import_share", "PLUM_price", "Factor", "File_weight",
			"CRAFTY_weight");
	static final List<String> UNGRAZED_GRASS_HEADER = List.of("Year", "Region", "Country", "Pasture_production",
			"Ruminant_feed", "Ungrazed", "Ungrazed_share");

	private static final CustomLogger LOGGER = new CustomLogger(PlumInputStep.class);

	/** Each region's weights (prices) of a service by year, where the step puts the year's price. */
	interface Weights {
		/** The region's weights by year for the service, or null if it has no such service. */
		Map<Integer, Double> of(String region, String service);

		/** The loaded model's regions' Service weights. */
		static Weights ofModel() {
			return (region, service) -> {
				Region r = CellsLoader.regions.get(region);
				Service s = r == null ? null : r.getServicesHash().get(service);
				return s == null ? null : s.getWeights();
			};
		}
	}

	/** PLUM's rows for a year, once they're complete. */
	interface Source {
		PlumYearOutputs await(int plumYear);

		/** A PLUM output folder: waits for {@code <folder>/<year>/done}, then reads the year's rows. */
		static Source plumOutput(Path folder, Duration timeout) {
			DoneFileWaiter waiter = new DoneFileWaiter();
			return year -> {
				waiter.await(folder, year, timeout);
				return PlumYearOutputs.read(folder, year);
			};
		}
	}

	private final PlumYear plumYear; // null in a calibration run
	private final PriceScaling scaling;
	private final RegionCountryKey key;
	private final CouplingOutputs outputs;
	private final Weights weights;
	private final Source source;
	private final int startYear;
	private final PlumYearOutputs calibrationRows;

	/** Starts prices_received.csv and ungrazed_grass.csv afresh. */
	PlumInputStep(PlumYear plumYear, PriceScaling scaling, RegionCountryKey key, CouplingOutputs outputs,
			Weights weights, Source source, int startYear, PlumYearOutputs calibrationRows) {
		this.plumYear = plumYear;
		this.scaling = scaling;
		this.key = key;
		this.outputs = outputs;
		this.weights = weights;
		this.source = source;
		this.startYear = startYear;
		this.calibrationRows = calibrationRows;
		outputs.start(PRICES_RECEIVED, PRICES_RECEIVED_HEADER);
		outputs.start(UNGRAZED_GRASS, UNGRAZED_GRASS_HEADER);
	}

	private PlumInputStep() {
		this.plumYear = null;
		this.scaling = null;
		this.key = null;
		this.outputs = null;
		this.weights = null;
		this.source = null;
		this.startYear = 0;
		this.calibrationRows = null;
	}

	/** A calibration run's step, which never runs: a calibration run has no CRAFTY year. */
	static PlumInputStep inCalibrationRun() {
		return new PlumInputStep();
	}

	/** Each region's start-year weight of each market service (null where there is none), for index scaling. */
	static Map<String, Map<String, Double>> startWeights(Weights weights, ServiceCommodityMap services,
			RegionCountryKey key, int startYear) {
		Map<String, Map<String, Double>> start = new TreeMap<>();
		for (String region : key.regions()) {
			Map<String, Double> byService = new LinkedHashMap<>();
			for (ServiceCommodityMap.Link link : services.links()) {
				Map<Integer, Double> w = weights.of(region, link.service());
				byService.put(link.service(), w == null ? null : w.get(startYear));
			}
			start.put(region, byService);
		}
		return start;
	}

	@Override
	protected void yearStep(int year) {
		if (plumYear == null) {
			throw new IllegalStateException("A calibration run has no CRAFTY year and no PLUM rows");
		}
		PlumYearOutputs rows = year == startYear ? calibrationRows : source.await(year - 1);
		List<String> countries = PlumCountryCheck.problems(rows, key);
		if (!countries.isEmpty()) {
			throw new CouplingInputException(countries);
		}

		// Every price first, so a problem leaves every weight as it was
		List<String> problems = new ArrayList<>();
		List<Price> prices = new ArrayList<>();
		for (PriceScaling.Factor f : scaling.factors()) {
			double price;
			try {
				price = scaling.price(f.region(), f.service(), rows);
			} catch (CouplingInputException e) {
				problems.addAll(e.getProblems());
				continue;
			}
			Map<Integer, Double> w = weights.of(f.region(), f.service());
			if (w == null) {
				problems.add("region " + f.region() + " has no service " + f.service() + " to set the price of");
			} else if (!Double.isFinite(price) || price <= 0) {
				problems.add("region " + f.region() + ", " + f.service() + ": the price from PLUM's " + rows.year()
						+ " rows is " + price + "; it must be a number greater than 0");
			} else {
				prices.add(new Price(f, w, price));
			}
		}
		if (!problems.isEmpty()) {
			throw new CouplingInputException(problems);
		}

		List<List<String>> received = new ArrayList<>();
		for (Price p : prices) {
			Double fileWeight = p.weights().put(year, p.price());
			received.add(receivedRow(year, p, fileWeight, rows));
		}
		outputs.append(PRICES_RECEIVED, received);
		outputs.append(UNGRAZED_GRASS, ungrazedRows(year, rows));
		plumYear.set(year, rows);
		LOGGER.info(year + ": " + prices.size() + " prices set from PLUM's "
				+ (year == startYear ? "calibration, final year " + rows.year() : rows.year() + " rows"));
	}

	private record Price(PriceScaling.Factor factor, Map<Integer, Double> weights, double price) {
	}

	private List<String> receivedRow(int year, Price p, Double fileWeight, PlumYearOutputs rows) {
		PriceScaling.Factor f = p.factor();
		PlumYearOutputs.Trade trade = rows.trade(f.country(), f.priceName());
		String where = f.country() + ", " + f.priceName() + " in " + rows.year();
		String markUp = "";
		String importShare = "";
		if (trade != null) {
			try {
				markUp = ProductionFile.format(PriceScaling.markUp(trade, where));
				importShare = ProductionFile.format(PriceScaling.importShare(trade, where));
			} catch (CouplingInputException e) {
				// not needed for this basis, so only left blank
			}
		}
		double plumPrice = PriceScaling.plumPrice(scaling.basis(), rows, f.country(), f.priceName());
		return List.of(String.valueOf(year), f.region(), f.country(), f.service(), f.priceName(),
				ProductionFile.format(rows.price(f.priceName())), markUp, importShare, ProductionFile.format(plumPrice),
				ProductionFile.format(f.factor()), fileWeight == null ? "" : ProductionFile.format(fileWeight),
				ProductionFile.format(p.price()));
	}

	/** Each region's grass that PLUM's ruminants didn't eat: pasture Production - Rum_feed_amount. */
	private List<List<String>> ungrazedRows(int year, PlumYearOutputs rows) {
		List<List<String>> out = new ArrayList<>();
		for (String region : key.regions()) {
			String country = key.country(region);
			PlumYearOutputs.Domestic pasture = rows.domestic(country, PlumCommodities.PASTURE);
			if (pasture == null) {
				continue;
			}
			double production = pasture.trade().production();
			double ungrazed = production - pasture.ruminantFeed();
			out.add(List.of(String.valueOf(year), region, country, ProductionFile.format(production),
					ProductionFile.format(pasture.ruminantFeed()), ProductionFile.format(ungrazed),
					production > 0 ? ProductionFile.format(ungrazed / production) : ""));
		}
		return out;
	}
}
