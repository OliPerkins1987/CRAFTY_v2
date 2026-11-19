package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.core.cli.Config;

/**
 * The stock cost step on {@link StockCostsTest#oneCountry(Path)}'s rows (every stock ratio 1, wheat's 1.2), with
 * Stock_cost 50, target 0.3 and threshold 3, so a critical ratio of 0.9.
 */
class StockCostStepTest {

	@TempDir
	Path tempDir;

	private ServiceCommodityMap services;
	private List<String> craftyServices;
	private CoreStateForTests core;
	private PlumYearOutputs rows;
	private List<double[]> handedOver;

	@BeforeEach
	void setUp() throws Exception {
		services = ServiceCommodityMap.read(ServiceCommodityMapTest.globalServices());
		CsvReader.Table table = CsvReader.readAll(ServiceCommodityMapTest.globalServices());
		craftyServices = new ArrayList<>();
		for (int i = 0; i < table.size(); i++) {
			craftyServices.add(table.get(i, "Name"));
		}
		core = new CoreStateForTests(new Config(), craftyServices,
				services.links().stream().map(ServiceCommodityMap.Link::service).collect(Collectors.toSet()));
		rows = StockCostsTest.oneCountry(tempDir.resolve("plum"));
		handedOver = new ArrayList<>();
	}

	@AfterEach
	void tearDown() {
		core.close();
	}

	private Path couplingFolder() {
		return tempDir.resolve("plum_coupling");
	}

	private StockCostStep step(PlumYear plumYear) {
		return new StockCostStep(plumYear, stockCosts(), new CouplingOutputs(couplingFolder()), handedOver::add);
	}

	private StockCosts stockCosts() {
		return StockCosts.create(services, rows, 50, 0.3, 3);
	}

	@Test
	void theYearsRatesGoToCoreIndexedLikeCraftysServices() {
		PlumYear plumYear = new PlumYear();
		plumYear.set(1990, rows);

		step(plumYear).yearStep(1990);

		assertEquals(1, handedOver.size());
		double[] rates = handedOver.get(0);
		assertEquals(craftyServices.size(), rates.length);
		assertEquals(50 * 1.2, rates[craftyServices.indexOf("C3cereals")], 1e-12);
		assertEquals(50 * 1.0, rates[craftyServices.indexOf("Fuelwood")], 1e-12);
		assertEquals(50 * 1.0, rates[craftyServices.indexOf("Pasture")], 1e-12, "the ruminants rate");
		assertEquals(0.0, rates[craftyServices.indexOf("Biodiversity")], 0.0, "non-market services: 0");
	}

	@Test
	void everyTermOfEachPayingServiceIsRecordedInStockCosts() throws Exception {
		PlumYear plumYear = new PlumYear();
		plumYear.set(1991, rows);

		step(plumYear).yearStep(1991);

		List<String> lines = Files.readAllLines(couplingFolder().resolve(StockCostStep.STOCK_COSTS));
		assertEquals(String.join(",", StockCostStep.STOCK_COSTS_HEADER), lines.get(0));
		assertEquals(1 + 13, lines.size(), "every market service, Pasture included");
		assertTrue(lines.stream().anyMatch(l -> l.startsWith("1991,2000,Pasture,ruminants,")), lines.toString());
		String[] wheat = lines.stream().filter(l -> l.startsWith("1991,2000,C3cereals,wheat,")).findFirst()
				.orElseThrow().split(",");
		assertEquals(3.0, Double.parseDouble(wheat[4]), 0.0, "World_stock");
		assertEquals(1.5, Double.parseDouble(wheat[5]), 0.0, "World_production");
		assertEquals(2.0, Double.parseDouble(wheat[6]), 0.0, "World_imports");
		assertEquals(1.0, Double.parseDouble(wheat[7]), 0.0, "World_exports");
		assertEquals(2.5, Double.parseDouble(wheat[8]), 1e-12, "Consumption");
		assertEquals(1.2, Double.parseDouble(wheat[9]), 1e-12, "Stock_ratio");
		assertEquals(0.9, Double.parseDouble(wheat[10]), 1e-12, "Critical_ratio");
		assertEquals(50.0, Double.parseDouble(wheat[11]), 0.0, "Stock_cost");
		assertEquals(60.0, Double.parseDouble(wheat[12]), 1e-12, "Rate");
	}

	@Test
	void aSwitchedOffFileIsNotWritten_butTheRatesStillGoToCore() {
		PlumYear plumYear = new PlumYear();
		plumYear.set(1990, rows);
		CouplingOutputs outputs = new CouplingOutputs(couplingFolder(), List.of("prices_received"));

		new StockCostStep(plumYear, stockCosts(), outputs, handedOver::add).yearStep(1990);

		assertEquals(1, handedOver.size());
		assertFalse(Files.exists(couplingFolder().resolve(StockCostStep.STOCK_COSTS)));
	}

	@Test
	void withoutTheYearsPlumRowsTheStepStops() {
		PlumYear plumYear = new PlumYear();
		plumYear.set(1990, rows);

		assertThrows(CouplingInputException.class, () -> step(plumYear).yearStep(1991));
		assertEquals(List.of(), handedOver);
	}

	@Test
	void switchedOffItDoesNothing() {
		StockCostStep.off().yearStep(1990);

		assertEquals(List.of(), handedOver);
		assertFalse(Files.exists(couplingFolder()));
	}
}
