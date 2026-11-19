package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stock costs on the global Services.csv, from one-country folders where every value is set by hand. The PLUM extract
 * holds three countries' production against world trade and stocks, so its world consumption isn't realistic; it is
 * used only for the paying services and for a consumption below 0.
 */
class StockCostsTest {

	private static final double TARGET = 0.3;
	private static final double THRESHOLD = 3;

	@TempDir
	Path tempDir;

	private ServiceCommodityMap services;
	private PlumYearOutputs y2029;

	@BeforeEach
	void setUp() throws Exception {
		services = ServiceCommodityMap.read(ServiceCommodityMapTest.globalServices());
		y2029 = PlumYearOutputs.read(PlumYearOutputsTest.all1990Extract(), 2029);
	}

	/**
	 * A one-country PLUM folder for 2000. Every commodity has a world stock of 1, world imports and exports of 1, and a
	 * production of 1, so a stock ratio of 1. The exception is wheat: production 1.5, stock 3, imports 2 and exports
	 * 1, so a consumption of 2.5 and a stock ratio of 1.2.
	 */
	static PlumYearOutputs oneCountry(Path dir) throws Exception {
		Files.createDirectories(dir);
		PlumConsumptionTest.oneCountry(dir, "Argentina", 1.5, 0.0);
		return PlumYearOutputs.read(withWheatPrices(dir, "2000,wheat,2,1,0.1,3"), 2000);
	}

	private StockCosts costs(PlumYearOutputs rows, double stockCost, double threshold) {
		return StockCosts.create(services, rows, stockCost, TARGET, threshold);
	}

	@Test
	void theRateIsTheStockCostTimesTheStockRatio_onlyAboveTheCriticalRatio() {
		assertEquals(50.0 * 1.2, StockCosts.rate(1.2, 0.9, 50), 1e-12);
		assertEquals(0.0, StockCosts.rate(0.9, 0.9, 50), 0.0, "at the critical ratio: not above, so no cost");
		assertEquals(0.0, StockCosts.rate(0.5, 0.9, 50), 0.0);
		assertEquals(0.0, StockCosts.rate(1.2, 0.9, 0), 0.0, "a stock cost of 0 charges nothing");
		assertEquals(50.0 * 0.1, StockCosts.rate(0.1, 0.0, 50), 1e-12, "a threshold of 0 charges any stock");
	}

	@Test
	void theCriticalRatioIsTheTargetTimesTheThreshold() {
		assertEquals(0.3 * 3, costs(y2029, 50, 3).criticalRatio(), 1e-12);
		assertEquals(0.3 * 2.5, costs(y2029, 50, 2.5).criticalRatio(), 1e-12);
	}

	@Test
	void everyMarketServicePays_onTheStocksOfItsPriceName() {
		StockCosts costs = costs(y2029, 50, THRESHOLD);

		assertEquals(List.of("C3cereals", "C4crops", "C3rice", "C3nfix_oil", "C3starchyroots", "C3pulses", "C3fruitveg",
				"C3oilcrops", "C3sugarcane", "C3sugarbeet", "Pasture", "Timber", "Fuelwood"),
				costs.payers().stream().map(ServiceCommodityMap.Link::service).toList());
		assertEquals(List.of(), costs.exempt());
		assertEquals(50.0, costs.stockCost(), 0.0);
	}

	@Test
	void aServiceWhosePriceNameHasNoRowNeverPays() throws Exception {
		oneCountry(tempDir);
		Path noRuminants = tempDir.resolve("copy").resolve("noRuminants");
		Files.createDirectories(noRuminants);
		Path from = tempDir.resolve("copy");
		Files.writeString(noRuminants.resolve(PlumYearOutputs.PRICES_FILE),
				Files.readString(from.resolve(PlumYearOutputs.PRICES_FILE)).lines()
						.filter(l -> !l.contains(",ruminants,")).collect(Collectors.joining("\n", "", "\n")));
		Files.copy(from.resolve(PlumYearOutputs.DOMESTIC_FILE), noRuminants.resolve(PlumYearOutputs.DOMESTIC_FILE));
		Files.copy(from.resolve(PlumYearOutputs.WOOD_FILE), noRuminants.resolve(PlumYearOutputs.WOOD_FILE));
		PlumYearOutputs rows = PlumYearOutputs.read(noRuminants, 2000);

		StockCosts costs = costs(rows, 50, THRESHOLD);

		assertEquals(List.of("Pasture"), costs.exempt());
		assertFalse(byService(costs.rates(rows)).containsKey("Pasture"));
	}

	@Test
	void pastureIsChargedOnRuminantStocks() throws Exception {
		oneCountry(tempDir);
		// Ruminants: stock 2, imports 1, exports 1, production 1, so a stock ratio of 2.
		Path folder = withPricesRow(tempDir.resolve("copy"), "2000,ruminants,1,1,0.1,1", "2000,ruminants,1,1,0.1,2");
		PlumYearOutputs rows = PlumYearOutputs.read(folder, 2000);

		StockCosts.Rate pasture = byService(costs(rows, 50, THRESHOLD).rates(rows)).get("Pasture");

		assertEquals("ruminants", pasture.commodity(), "Pasture's PLUM_price_name, not its PLUM_name (pasture)");
		assertEquals(2.0, pasture.stock(), 0.0);
		assertEquals(1.0, pasture.production(), 0.0, "ruminant production in domestic.txt");
		assertEquals(2.0, pasture.stockRatio(), 1e-12);
		assertEquals(50 * 2.0, pasture.rate(), 1e-12);
	}

	@Test
	void theStockRatioIsStockOverProductionPlusImportsMinusExports() throws Exception {
		PlumYearOutputs rows = oneCountry(tempDir);

		Map<String, StockCosts.Rate> rates = byService(costs(rows, 50, THRESHOLD).rates(rows));

		StockCosts.Rate wheat = rates.get("C3cereals");
		assertEquals("wheat", wheat.commodity());
		assertEquals(3.0, wheat.stock(), 0.0, "world stock, prices.txt");
		assertEquals(1.5, wheat.production(), 0.0, "domestic.txt");
		assertEquals(2.0, wheat.imports(), 0.0, "world imports, prices.txt");
		assertEquals(1.0, wheat.exports(), 0.0, "world exports, prices.txt");
		assertEquals(1.5 + 2 - 1, wheat.consumption(), 1e-12);
		assertEquals(3 / 2.5, wheat.stockRatio(), 1e-12);
		assertEquals(50 * 3 / 2.5, wheat.rate(), 1e-12);

		StockCosts.Rate timber = rates.get("Timber");
		assertEquals(1.0, timber.production(), 0.0, "wood.txt");
		assertEquals(1.0, timber.stockRatio(), 1e-12);
		assertEquals(50.0, timber.rate(), 1e-12, "ratio 1 > critical 0.9");
		assertEquals(timber.rate(), rates.get("Fuelwood").rate(), 0.0, "Timber and Fuelwood share wood's rate");
		assertEquals(rates.get("C3sugarcane").rate(), rates.get("C3sugarbeet").rate(), 0.0, "cane and beet: sugar's");
		assertEquals(50.0, rates.get("Pasture").rate(), 1e-12, "the ruminants rate: ratio 1 > critical 0.9");
	}

	@Test
	void noCostAtOrBelowTheCriticalRatio() throws Exception {
		PlumYearOutputs rows = oneCountry(tempDir);

		List<StockCosts.Rate> rates = costs(rows, 50, 5).rates(rows); // critical ratio 1.5

		assertEquals(13, rates.size());
		assertTrue(rates.stream().allMatch(r -> r.rate() == 0.0), "the highest ratio is wheat's 1.2: " + rates);
	}

	@Test
	void importsAboveExportsRaiseConsumption_andLowerTheStockRatio() throws Exception {
		Files.createDirectories(tempDir.resolve("base"));
		PlumConsumptionTest.oneCountry(tempDir.resolve("base"), "Argentina", 1.0, 0.0);
		PlumYearOutputs rows = PlumYearOutputs.read(withWheatPrices(tempDir.resolve("base"), "2000,wheat,3,1,0.1,1"),
				2000); // imports 3, exports 1

		StockCosts.Rate wheat = byService(costs(rows, 50, THRESHOLD).rates(rows)).get("C3cereals");

		assertEquals(1.0 + 3 - 1, wheat.consumption(), 1e-12);
		assertEquals(1.0 / 3, wheat.stockRatio(), 1e-12);
		assertEquals(0.0, wheat.rate(), 0.0);
	}

	@Test
	void theRatesAreIndexedLikeCraftysServices() {
		List<StockCosts.Rate> rates = List.of(new StockCosts.Rate("C3cereals", "wheat", 3, 2, 1, 1, 75),
				new StockCosts.Rate("Timber", "wood", 1, 2, 1, 1, 0), new StockCosts.Rate("Unknown", "maize", 3, 1, 1, 1, 150));

		assertArrayEquals(new double[] { 0, 75, 0, 0 },
				StockCosts.asArray(rates, List.of("Biodiversity", "C3cereals", "Timber", "Pasture")), 0.0);
	}

	@Test
	void aMissingStockTradeOrProductionStopsListingEveryProblem() throws Exception {
		PlumYearOutputs rows = oneCountry(tempDir);
		StockCosts costs = costs(rows, 50, THRESHOLD);
		// The same folder, with wheat's imports NaN and no sugar rows in domestic.txt.
		Path broken = withWheatPrices(tempDir, "2000,wheat,NaN,1,0.1,1");
		Files.writeString(broken.resolve(PlumYearOutputs.DOMESTIC_FILE),
				Files.readString(tempDir.resolve(PlumYearOutputs.DOMESTIC_FILE)).lines()
						.filter(l -> !l.contains(",sugar,")).collect(Collectors.joining("\n", "", "\n")));

		CouplingInputException e = assertThrows(CouplingInputException.class,
				() -> costs.rates(PlumYearOutputs.read(broken, 2000)));

		assertTrue(e.getMessage().contains("no stock, imports or exports for wheat (C3cereals)"), e.getMessage());
		assertTrue(e.getMessage().contains("no world production for sugar (C3sugarcane)"), e.getMessage());
		assertTrue(e.getMessage().contains("no world production for sugar (C3sugarbeet)"), e.getMessage());
	}

	@Test
	void aStockWithAConsumptionOfZeroOrLessStops() throws Exception {
		// In the extract, rice's three-country production (1.3707) + world imports (22.0) - world exports (26.0) < 0.
		CouplingInputException e = assertThrows(CouplingInputException.class,
				() -> costs(y2029, 50, THRESHOLD).rates(y2029));
		assertTrue(e.getMessage().contains("rice (C3rice)") && e.getMessage().contains("consumption"), e.getMessage());

		Files.createDirectories(tempDir.resolve("none"));
		PlumYearOutputs none = PlumConsumptionTest.oneCountry(tempDir.resolve("none"), "Argentina", 0.0, 0.0);
		CouplingInputException zero = assertThrows(CouplingInputException.class,
				() -> costs(none, 50, THRESHOLD).rates(none));
		assertTrue(zero.getMessage().contains("wheat (C3cereals)"), zero.getMessage());
	}

	@Test
	void aNumberOutOfRangeStops() throws Exception {
		assertThrows(CouplingInputException.class, () -> StockCosts.create(services, y2029, -1, TARGET, THRESHOLD));
		assertThrows(CouplingInputException.class, () -> StockCosts.create(services, y2029, Double.NaN, TARGET, 3));
		assertThrows(CouplingInputException.class, () -> StockCosts.create(services, y2029, 50, 0, THRESHOLD));
		assertThrows(CouplingInputException.class, () -> StockCosts.create(services, y2029, 50, TARGET, -1));
		PlumYearOutputs rows = oneCountry(tempDir);
		assertTrue(costs(rows, 0, THRESHOLD).rates(rows).stream().allMatch(r -> r.rate() == 0.0), "Stock_cost 0");
	}

	private static Map<String, StockCosts.Rate> byService(List<StockCosts.Rate> rates) {
		return rates.stream().collect(Collectors.toMap(StockCosts.Rate::service, r -> r));
	}

	/** A copy of a one-country folder (in its "copy" subfolder) whose wheat row in prices.txt is replaced. */
	static Path withWheatPrices(Path from, String wheatRow) throws Exception {
		return withPricesRow(from, "2000,wheat,1,1,0.1,1", wheatRow);
	}

	/** A copy of a PLUM folder (in its "copy" subfolder) with one row of prices.txt replaced. */
	static Path withPricesRow(Path from, String row, String newRow) throws Exception {
		Path copy = from.resolve("copy");
		Files.createDirectories(copy);
		String prices = Files.readString(from.resolve(PlumYearOutputs.PRICES_FILE));
		assertTrue(prices.contains(row), "no row " + row);
		Files.writeString(copy.resolve(PlumYearOutputs.PRICES_FILE), prices.replace(row, newRow));
		Files.copy(from.resolve(PlumYearOutputs.DOMESTIC_FILE), copy.resolve(PlumYearOutputs.DOMESTIC_FILE),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		Files.copy(from.resolve(PlumYearOutputs.WOOD_FILE), copy.resolve(PlumYearOutputs.WOOD_FILE),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		return copy;
	}
}
