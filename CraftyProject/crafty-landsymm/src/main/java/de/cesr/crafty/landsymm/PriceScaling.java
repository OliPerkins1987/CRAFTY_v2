package de.cesr.crafty.landsymm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns PLUM's prices into CRAFTY's: which PLUM price each region's market services follow
 * ({@code plum_price_basis}), and the factor from that price to CRAFTY's ({@code plum_price_scaling}).
 *
 * <b>Basis.</b> From PLUM's year-t outputs, for CRAFTY's year t + 1, region r's price for a PLUM price name is:
 * <ul>
 * <li>{@code exporter}: P, PLUM's world {@code New export price}, the same for every region;</li>
 * <li>{@code importer}: P x m, where m = Import_price / Export_price is country r's import mark-up (PLUM's
 * Export_price is the world price in force that year, so the ratio is (1 + transport cost) x (1 + trade barrier x
 * multiplier) / (1 - transport losses));</li>
 * <li>{@code domestic}: P x (1 + w x (m - 1)), the import and export prices weighted by country r's import share
 * w = max(0, net imports / (production + net imports)), with w = 1 when it produces none. This is PLUM's own
 * country price (PRICE_CALCULATION = weightedImportsExports): the price PLUM's country r uses in year t + 1.</li>
 * </ul>
 *
 * <b>Scaling.</b> {@code index}: each region's factor for a service is its start-year file weight divided by its
 * PLUM price in PLUM's start-year state (the calibration's last year), so CRAFTY starts at its own price and follows
 * PLUM's relative changes. {@code factor}: the service's PLUM_price_factor from Services.csv.
 *
 * <b>Regional start (initialise_regional_price_weights).</b> The file weights are global prices. With index scaling,
 * each region can instead start at its file weight x its country's PLUM price / PLUM's world price, so its start
 * carries its country's mark-up over the world price. Its factor is then the file weight / the world price, the same
 * for every region, and its price follows its country's PLUM price from there. With the exporter basis the country
 * price is the world price, so it changes nothing.
 *
 * Factors are per region and service. They are fixed for the run and can be written to price_factors.csv.
 */
public final class PriceScaling {

	public enum Basis {
		DOMESTIC, EXPORTER, IMPORTER;

		/** The basis for a plum_price_basis value. */
		public static Basis of(String name) {
			return valueOf(name.toUpperCase(Locale.ROOT));
		}

		public String key() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	public enum Scaling {
		INDEX, FACTOR;

		/** The scaling for a plum_price_scaling value. */
		public static Scaling of(String name) {
			return valueOf(name.toUpperCase(Locale.ROOT));
		}

		public String key() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	/**
	 * One region's factor for one service, and what it was made from: the file weight, the region's start weight
	 * (the file weight, or with a regional start the file weight x country / world price) and its PLUM start price.
	 * The weights are NaN under factor scaling, which doesn't use them.
	 */
	public record Factor(String region, String country, String service, String priceName, double fileWeight,
			double startWeight, double startPrice, double factor) {
	}

	private final Basis basis;
	private final Scaling scaling;
	private final ServiceCommodityMap services;
	private final RegionCountryKey key;
	private final Map<String, Map<String, Factor>> factors; // region, service

	private PriceScaling(Basis basis, Scaling scaling, ServiceCommodityMap services, RegionCountryKey key,
			Map<String, Map<String, Factor>> factors) {
		this.basis = basis;
		this.scaling = scaling;
		this.services = services;
		this.key = key;
		this.factors = Collections.unmodifiableMap(factors);
	}

	/** {@link #create(Scaling, Basis, ServiceCommodityMap, RegionCountryKey, Map, PlumYearOutputs, boolean)} with every
	 * region starting at its file weight. */
	public static PriceScaling create(Scaling scaling, Basis basis, ServiceCommodityMap services,
			RegionCountryKey key, Map<String, Map<String, Double>> startWeights, PlumYearOutputs startState) {
		return create(scaling, basis, services, key, startWeights, startState, false);
	}

	/**
	 * Works out every region's factor for every market service, stopping with every problem found.
	 *
	 * @param startWeights  each region's start-year file weight for each service (CRAFTY's price in the start year);
	 *                      only read with index scaling
	 * @param startState    PLUM's outputs for its start-year state: the calibration's last year
	 * @param regionalStart with index scaling, start each region at its file weight x its country's PLUM price /
	 *                      PLUM's world price (initialise_regional_price_weights)
	 */
	public static PriceScaling create(Scaling scaling, Basis basis, ServiceCommodityMap services,
			RegionCountryKey key, Map<String, Map<String, Double>> startWeights, PlumYearOutputs startState,
			boolean regionalStart) {
		List<String> problems = new ArrayList<>();
		Map<String, Map<String, Factor>> factors = new TreeMap<>();
		for (String region : new java.util.TreeSet<>(startWeights.keySet())) {
			String country = key.country(region);
			if (country == null) {
				problems.add("region " + region + " has no PLUM country in " + key.file().getFileName());
				continue;
			}
			Map<String, Factor> byService = new LinkedHashMap<>();
			for (ServiceCommodityMap.Link link : services.links()) {
				double startPrice;
				try {
					startPrice = plumPrice(basis, startState, country, link.priceName());
				} catch (CouplingInputException e) {
					problems.addAll(e.getProblems());
					continue;
				}
				double fileWeight = Double.NaN;
				double startWeight = Double.NaN;
				double factor;
				if (scaling == Scaling.INDEX) {
					Double w = startWeights.get(region).get(link.service());
					if (w == null || !Double.isFinite(w) || w <= 0) {
						problems.add("region " + region + ": the start-year price (weight) of " + link.service()
								+ " is " + w + "; index scaling needs a number greater than 0");
						continue;
					}
					fileWeight = w;
					if (regionalStart) {
						// w x country / world: the factor is w / world, and the start is that x the country price
						factor = w / startState.price(link.priceName());
						startWeight = factor * startPrice;
					} else {
						factor = w / startPrice;
						startWeight = w;
					}
				} else {
					factor = link.priceFactor();
				}
				byService.put(link.service(), new Factor(region, country, link.service(), link.priceName(), fileWeight,
						startWeight, startPrice, factor));
			}
			factors.put(region, byService);
		}
		if (!problems.isEmpty()) {
			throw new CouplingInputException(problems);
		}
		return new PriceScaling(basis, scaling, services, key, factors);
	}

	/** CRAFTY's price for a region's service, from PLUM's outputs for a year: the factor x the region's PLUM price. */
	public double price(String region, String service, PlumYearOutputs outputs) {
		Factor f = factor(region, service);
		if (f == null) {
			throw new CouplingInputException("no price factor for " + service + " in region " + region);
		}
		return f.factor() * plumPrice(basis, outputs, f.country(), f.priceName());
	}

	/**
	 * A country's PLUM price for a price name on a basis, from PLUM's outputs for year t (for CRAFTY's year t + 1).
	 * Stops if a value it needs is missing, not finite, or not greater than 0.
	 */
	public static double plumPrice(Basis basis, PlumYearOutputs outputs, String country, String priceName) {
		double world = outputs.price(priceName);
		if (!Double.isFinite(world) || world <= 0) {
			throw new CouplingInputException("PLUM's New export price for " + priceName + " in " + outputs.year()
					+ " is " + world + "; it must be a number greater than 0");
		}
		if (basis == Basis.EXPORTER) {
			return world;
		}
		PlumYearOutputs.Trade trade = outputs.trade(country, priceName);
		String where = country + ", " + priceName + " in " + outputs.year();
		if (trade == null) {
			throw new CouplingInputException("PLUM has no row for " + where + " ("
					+ (PlumCommodities.WOOD.equals(priceName) ? PlumYearOutputs.WOOD_FILE : PlumYearOutputs.DOMESTIC_FILE)
					+ "), which the " + basis.key() + " price needs");
		}
		double m = markUp(trade, where);
		if (basis == Basis.IMPORTER) {
			return world * m;
		}
		if (m < 1) {
			return world * m; // PLUM's rule: the country price never rises above the import price
		}
		return world * (1 + importShare(trade, where) * (m - 1));
	}

	/** Import_price / Export_price: the country's import mark-up on the world price. */
	static double markUp(PlumYearOutputs.Trade trade, String where) {
		double importPrice = trade.importPrice();
		double exportPrice = trade.exportPrice();
		if (!Double.isFinite(importPrice) || importPrice <= 0 || !Double.isFinite(exportPrice) || exportPrice <= 0) {
			throw new CouplingInputException("PLUM's import and export prices for " + where + " are " + importPrice
					+ " and " + exportPrice + "; both must be numbers greater than 0");
		}
		return importPrice / exportPrice;
	}

	/**
	 * The country's import share, as PLUM weights its country price: max(0, net imports / (production + net
	 * imports)); 1 when it produces none (everything it uses is imported); 0 when it exports all it produces.
	 */
	static double importShare(PlumYearOutputs.Trade trade, String where) {
		double production = trade.production();
		double netImports = trade.netImports();
		if (!Double.isFinite(production) || !Double.isFinite(netImports)) {
			throw new CouplingInputException("PLUM's production and net imports for " + where + " are " + production
					+ " and " + netImports + "; both must be numbers");
		}
		if (production <= 0) {
			return 1.0;
		}
		double use = production + netImports;
		if (use <= 0) {
			return 0.0;
		}
		return Math.max(0, netImports / use);
	}

	public Basis basis() {
		return basis;
	}

	public Scaling scaling() {
		return scaling;
	}

	/** A region's factor for a service, or null if there is none. */
	public Factor factor(String region, String service) {
		Map<String, Factor> byService = factors.get(region);
		return byService == null ? null : byService.get(service);
	}

	/** Every factor, by region then in Services.csv order. */
	public List<Factor> factors() {
		List<Factor> all = new ArrayList<>();
		factors.values().forEach(byService -> all.addAll(byService.values()));
		return all;
	}

	/** Writes every factor to a CSV file (price_factors.csv). */
	public void writeCsv(Path file) {
		List<String> lines = new ArrayList<>();
		lines.add("Region,Country,Service,PLUM_price_name,Basis,Scaling,File_weight,CRAFTY_start_weight,PLUM_start_price,"
				+ "Factor");
		for (Factor f : factors()) {
			lines.add(String.join(",", csv(f.region()), csv(f.country()), csv(f.service()), csv(f.priceName()),
					basis.key(), scaling.key(), number(f.fileWeight()), number(f.startWeight()), number(f.startPrice()),
					number(f.factor())));
		}
		try {
			Files.createDirectories(file.toAbsolutePath().getParent());
			Files.write(file, lines, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new CouplingInputException("Could not write " + file + ": " + e.getMessage());
		}
	}

	private static String csv(String text) {
		return text.contains(",") || text.contains("\"") ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
	}

	private static String number(double value) {
		return Double.isNaN(value) ? "" : Double.toString(value);
	}

	ServiceCommodityMap services() {
		return services;
	}

	RegionCountryKey key() {
		return key;
	}
}
