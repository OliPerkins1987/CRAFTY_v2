package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** PLUM's domestic use and its split between services, on all1990's 2029 rows. */
class PlumConsumptionTest {

	private PlumYearOutputs y2029;
	private ServiceCommodityMap services;

	@BeforeEach
	void setUp() throws Exception {
		y2029 = PlumYearOutputs.read(PlumYearOutputsTest.all1990Extract(), 2029);
		services = ServiceCommodityMap.read(ServiceCommodityMapTest.globalServices());
	}

	private Map<String, Double> supply(double tonnes) {
		Map<String, Double> supply = new HashMap<>();
		services.links().forEach(l -> supply.put(l.service(), tonnes));
		return supply;
	}

	@Test
	void cropsWoodAndPastureUse() {
		assertEquals(0.1113 + 0.0612, PlumConsumption.domesticUse(y2029, "Tanzania", "wheat"), 1e-12);
		assertEquals(11.1993 - 1.6884, PlumConsumption.domesticUse(y2029, "Argentina", "wheat"), 1e-12);
		assertEquals(10.4678 - 0.55, PlumConsumption.domesticUse(y2029, "Argentina", "wood"), 1e-12);
		// Tanzania exports no ruminants, so all its grass is used at home
		assertEquals(5.8947, PlumConsumption.domesticUse(y2029, "Tanzania", "pasture"), 1e-12);
		// Argentina exports 27.4 of its 87.4 Mt of ruminants, and the grass in them
		assertEquals(81.2402 * (1 - 27.3611 / 87.3872), PlumConsumption.domesticUse(y2029, "Argentina", "pasture"),
				1e-9);
	}

	@Test
	void theRuminantExportShareIsAtMostOne() {
		assertEquals(0.0, PlumConsumption.ruminantExportShare(new PlumYearOutputs.Trade(10, 5, 1, 1)), 0.0);
		assertEquals(0.25, PlumConsumption.ruminantExportShare(new PlumYearOutputs.Trade(10, -2.5, 1, 1)), 1e-12);
		assertEquals(1.0, PlumConsumption.ruminantExportShare(new PlumYearOutputs.Trade(10, -15, 1, 1)), 0.0);
		assertEquals(0.0, PlumConsumption.ruminantExportShare(new PlumYearOutputs.Trade(0, -1, 1, 1)), 0.0);
	}

	@Test
	void eachServiceGetsItsCommoditysUseInCraftyUnitsSplitBySupply() {
		Map<String, Double> supply = supply(1e6);
		supply.put("C3sugarcane", 3e6);
		supply.put("C3sugarbeet", 1e6);

		List<PlumConsumption.ServiceConsumption> c = PlumConsumption.consumption(y2029, "Argentina", services, supply);

		assertEquals(13, c.size());
		PlumConsumption.ServiceConsumption wheat = c.stream().filter(x -> x.service().equals("C3cereals")).findFirst()
				.orElseThrow();
		assertEquals("wheat", wheat.commodity());
		assertEquals((11.1993 - 1.6884) / 1e-6, wheat.consumption(), 1e-3, "Mt to t");
		double sugar = (15.7 - 0.0262) / 1e-6;
		assertEquals(sugar * 0.75, consumption(c, "C3sugarcane"), 1e-3, "cane supplies 3 of the 4 Mt");
		assertEquals(sugar * 0.25, consumption(c, "C3sugarbeet"), 1e-3);
		assertEquals(consumption(c, "Timber"), consumption(c, "Fuelwood"), 1e-6, "equal supply, equal shares");
	}

	@Test
	void withNoSupplyTheUseIsSplitEqually() {
		Map<String, Double> supply = supply(1e6);
		supply.put("C3sugarcane", 0.0);
		supply.put("C3sugarbeet", 0.0);

		List<PlumConsumption.ServiceConsumption> c = PlumConsumption.consumption(y2029, "Tanzania", services, supply);

		assertEquals(consumption(c, "C3sugarcane"), consumption(c, "C3sugarbeet"), 1e-9);
	}

	@Test
	void aUseBelowZeroCountsAsZeroButIsReportedAsWritten() throws Exception {
		// Wheat: 1 produced, 1.0001 exported, as rounding can give
		PlumYearOutputs rows = oneCountry(tempDir, "Tanzania", 1.0, -1.0001);

		List<PlumConsumption.ServiceConsumption> c = PlumConsumption.consumption(rows, "Tanzania", services,
				supply(1e6));

		PlumConsumption.ServiceConsumption wheat = c.stream().filter(x -> x.service().equals("C3cereals")).findFirst()
				.orElseThrow();
		assertEquals(-0.0001, wheat.plumUse(), 1e-12, "reported as written");
		assertEquals(0.0, wheat.consumption(), 0.0);
	}

	@TempDir
	Path tempDir;

	/**
	 * A one-year PLUM output folder (year 2000) for one country: every crop, ruminants and wood produced 1 and not
	 * traded, at price 0.1, except wheat, with the given production and net imports.
	 */
	static PlumYearOutputs oneCountry(Path dir, String country, double wheatProduction,
			double wheatNetImports) throws IOException {
		StringBuilder prices = new StringBuilder("Year,Crop,Imports (Mt),Exports (Mt),New export price, Stock Levels (Mt)\n");
		StringBuilder domestic = new StringBuilder("Year, Country, Crop, Area, Production, Production_cost, Import_price,"
				+ " Export_price, Consumer_price, Net_imports, Net_import_cost, Prod_shock, Rum_feed_amount,"
				+ " Mon_feed_amount, Fish_feed_amount\n");
		for (String crop : PlumCommodities.PRICE_NAMES) {
			prices.append("2000,").append(crop).append(",1,1,0.1,1\n");
			if (crop.equals(PlumCommodities.WOOD)) {
				continue;
			}
			boolean wheat = crop.equals("wheat");
			domestic.append("2000,").append(country).append(',').append(crop).append(",NaN,")
					.append(wheat ? wheatProduction : 1.0).append(",0,0.13,0.1,0.1,")
					.append(wheat ? wheatNetImports : 0.0).append(",0,0,0,0,0\n");
		}
		domestic.append("2000,").append(country).append(",pasture,NaN,1.0,0,0,0,0,0,0,0,0.8,0,0\n");
		String wood = "Year, Country, RoundwoodDemand, WoodfuelDemand, Production, Import_price, Export_price, Net_imports\n"
				+ "2000," + country + ",0.5,0.5,1.0,0.13,0.1,0\n";
		Files.writeString(dir.resolve(PlumYearOutputs.PRICES_FILE), prices);
		Files.writeString(dir.resolve(PlumYearOutputs.DOMESTIC_FILE), domestic);
		Files.writeString(dir.resolve(PlumYearOutputs.WOOD_FILE), wood);
		return PlumYearOutputs.read(dir, 2000);
	}

	@Test
	void aMissingRowStops() {
		CouplingInputException e = assertThrows(CouplingInputException.class,
				() -> PlumConsumption.consumption(y2029, "Atlantis", services, supply(1e6)));

		assertTrue(e.getMessage().contains("Atlantis"), e.getMessage());
	}

	@Test
	void theArrayFollowsCraftysServiceListWithZerosForTheRest() {
		List<PlumConsumption.ServiceConsumption> c = List.of(
				new PlumConsumption.ServiceConsumption("C3cereals", "wheat", 1, 2, 3),
				new PlumConsumption.ServiceConsumption("Pasture", "pasture", 1, 2, 7));

		double[] array = PlumConsumption.asArray(c, List.of("Biodiversity", "Pasture", "C3cereals"));

		assertArrayEquals(new double[] { 0, 7, 3 }, array, 0.0);
		assertThrows(CouplingInputException.class, () -> PlumConsumption.asArray(c, List.of("Pasture")));
	}

	private static double consumption(List<PlumConsumption.ServiceConsumption> c, String service) {
		return c.stream().filter(x -> x.service().equals(service)).findFirst().orElseThrow().consumption();
	}
}
