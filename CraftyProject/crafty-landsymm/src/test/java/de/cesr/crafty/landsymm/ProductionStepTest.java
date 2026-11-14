package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.core.cli.Config;
import de.cesr.crafty.core.crafty.Cell;

/**
 * The production step on real core cells, for the PLUM extract's three countries with the global Services.csv. Each
 * region has two cells of 500,000 ha producing 1 t/ha of every service, so 1 Mt of each. In Tanzania one cell's
 * owner keeps half its market goods, which leaves 0.75 Mt.
 */
class ProductionStepTest {

	@TempDir
	Path tempDir;

	private ServiceCommodityMap services;
	private RegionCountryKey key;
	private CoreStateForTests core;
	private Map<String, List<Cell>> cells;

	@BeforeEach
	void setUp() throws Exception {
		services = ServiceCommodityMap.read(ServiceCommodityMapTest.globalServices());
		key = ProductionFileTest.threeCountryKey(tempDir);
		CsvReader.Table table = CsvReader.readAll(ServiceCommodityMapTest.globalServices());
		List<String> craftyServices = new ArrayList<>();
		for (int i = 0; i < table.size(); i++) {
			craftyServices.add(table.get(i, "Name"));
		}
		Config coreConfig = new Config();
		coreConfig.use_cell_area = true;
		coreConfig.use_self_consumption = true;
		core = new CoreStateForTests(coreConfig, craftyServices,
				services.links().stream().map(ServiceCommodityMap.Link::service).collect(Collectors.toSet()));

		cells = new TreeMap<>();
		int x = 0;
		for (String region : key.regions()) {
			List<Cell> regionCells = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				Cell c = new Cell(x++, 0);
				c.setArea(5e5);
				java.util.Arrays.fill(c.getCurrentProd(), 1.0);
				regionCells.add(c);
			}
			cells.put(region, regionCells);
		}
		cells.get("Tanzania").get(0).setCurrentSelfConsumption(0.5);
	}

	@AfterEach
	void tearDown() {
		core.close();
	}

	private Config config(boolean calibrationRun, String mode, String run) {
		Config c = new Config();
		c.plum_calibration_run = calibrationRun;
		c.plum_coupling_mode = mode;
		c.use_cell_area = true;
		c.crafty_production_path = tempDir.resolve(run).resolve("production").toString();
		c.calibration_production_path = tempDir.resolve("calib").resolve("production").toString();
		return c;
	}

	/** Core's own supply for every region, as SupplyUpdater leaves it. */
	private Map<String, Map<String, Double>> coreSupply() {
		Map<String, Map<String, Double>> supply = new TreeMap<>();
		cells.forEach((region, c) -> supply.put(region, CoreStateForTests.coreSum(c)));
		return supply;
	}

	private ProductionStep.Regions regions(Map<String, Map<String, Double>> coreSupply) {
		return new ProductionStep.Regions() {
			@Override
			public Map<String, Collection<Cell>> cells() {
				return new TreeMap<>(cells);
			}

			@Override
			public Map<String, Map<String, Double>> coreSupply() {
				return coreSupply;
			}
		};
	}

	private ProductionStep step(Config c, String run, Map<String, Map<String, Double>> coreSupply) {
		return new ProductionStep(c, services, key, new CouplingOutputs(tempDir.resolve(run).resolve("plum_coupling")),
				regions(coreSupply));
	}

	private Map<String, Map<String, Double>> written(String run, int year) {
		return ProductionFile.read(tempDir.resolve(run).resolve("production").resolve(String.valueOf(year))
				.resolve(ProductionFile.FILE));
	}

	@Test
	void productionIsRecalculatedInSequentialScenarioRunsOnly() {
		assertTrue(ProductionStep.recalculates(true, false), "after giving up and competition, every year");
		assertFalse(ProductionStep.recalculates(false, false), "overlapping: right after SupplyUpdater");
		assertFalse(ProductionStep.recalculates(true, true), "a calibration run: after initialzeRun");
		assertFalse(ProductionStep.recalculates(false, true));
	}

	@Test
	void aCalibrationRunWritesTheStartYearAndRecordsWhatWasSent() throws Exception {
		step(config(true, "sequential", "calib"), "calib", coreSupply()).yearStep(1990);

		Map<String, Map<String, Double>> p = written("calib", 1990);
		assertEquals(1.0, p.get("Argentina").get("wheat"), 1e-12);
		assertEquals(2.0, p.get("Argentina").get("sugar"), 1e-12, "cane + beet");
		assertEquals(2.0, p.get("Argentina").get("wood"), 1e-12, "timber + fuelwood");
		assertEquals(0.75, p.get("Tanzania").get("wheat"), 1e-12, "less what the owner keeps");
		assertEquals(0.0, p.get("Tanzania").get("energycrops"), 0.0);
		assertTrue(Files.exists(tempDir.resolve("calib").resolve("production").resolve("1990").resolve("done")));

		List<String> sent = Files.readAllLines(tempDir.resolve("calib").resolve("plum_coupling")
				.resolve(ProductionStep.PRODUCTION_SENT));
		assertEquals(String.join(",", ProductionStep.PRODUCTION_SENT_HEADER), sent.get(0));
		assertEquals(1 + 3 * 13, sent.size(), "3 regions x 13 market services");
		String[] tanzaniaCereals = sent.stream().filter(l -> l.startsWith("1990,Tanzania,Tanzania,C3cereals,"))
				.findFirst().orElseThrow().split(",");
		assertEquals("wheat", tanzaniaCereals[4]);
		assertEquals(750000.0, Double.parseDouble(tanzaniaCereals[5]), 1e-6, "supply sent");
		assertEquals(250000.0, Double.parseDouble(tanzaniaCereals[6]), 1e-6, "self-consumed");
		assertEquals(0.75, Double.parseDouble(tanzaniaCereals[8]), 1e-12, "in Mt");
		assertFalse(Files.exists(tempDir.resolve("calib").resolve("plum_coupling").resolve(ProductionStep.BASELINE_CHECK)),
				"a calibration run has nothing to compare with");
	}

	@Test
	void aDriftFromCoresSupplyStopsTheStepBeforeAnythingIsWritten() {
		Map<String, Map<String, Double>> drifted = coreSupply();
		Map<String, Double> tanzania = new HashMap<>(drifted.get("Tanzania"));
		tanzania.put("Pasture", tanzania.get("Pasture") + 1);
		drifted.put("Tanzania", tanzania);
		ProductionStep step = step(config(true, "sequential", "calib"), "calib", drifted);

		CouplingInputException e = assertThrows(CouplingInputException.class, () -> step.yearStep(1990));

		assertTrue(e.getMessage().contains("Tanzania, Pasture"), e.getMessage());
		assertFalse(Files.exists(tempDir.resolve("calib").resolve("production").resolve("1990")));
	}

	@Test
	void aSequentialScenarioRecalculatesOnTheNewLandUseFromItsStartYear() {
		// The cells have no owner, so recalculated production is 0. Core's supply is from before, but isn't checked.
		step(config(false, "sequential", "run"), "run", coreSupply()).yearStep(1990);

		Map<String, Map<String, Double>> p = written("run", 1990);
		p.values().forEach(byCommodity -> byCommodity.values().forEach(v -> assertEquals(0.0, v, 0.0)));
		assertEquals(0.0, cells.get("Argentina").get(0).getCurrentProd()[0], 0.0);
		assertFalse(Files.exists(tempDir.resolve("run").resolve("plum_coupling").resolve(ProductionStep.BASELINE_CHECK)),
				"the 1990 check runs before the first year, not here");
	}

	@Test
	void anOverlappingYearUsesTheSupplyCraftyComputedAndChecksIt() {
		step(config(false, "overlapping", "run"), "run", coreSupply()).yearStep(1991);
		assertEquals(1.0, written("run", 1991).get("Argentina").get("wheat"), 1e-12);

		Map<String, Map<String, Double>> drifted = coreSupply();
		drifted.remove("Argentina");
		ProductionStep step = step(config(false, "overlapping", "run2"), "run2", drifted);
		assertThrows(CouplingInputException.class, () -> step.yearStep(1992));
	}

	@Test
	void theStartingMapIsComparedWithTheCalibrationRunsWithoutRecalculating() throws Exception {
		step(config(true, "sequential", "calib"), "calib", coreSupply()).yearStep(1990);

		// Sequential, so its years recalculate; the check uses the starting state as CRAFTY computed it
		step(config(false, "sequential", "same"), "same", coreSupply()).checkBaseline(1990);
		List<String> same = Files.readAllLines(tempDir.resolve("same").resolve("plum_coupling")
				.resolve(ProductionStep.BASELINE_CHECK));
		assertEquals(1 + 36, same.size());
		assertTrue(same.stream().skip(1).allMatch(l -> l.endsWith(",false")), String.join("\n", same));
		assertFalse(Files.exists(tempDir.resolve("same").resolve("production").resolve("1990")),
				"the check writes no production");

		// A different baseline map in Tanzania: a warning, and no stop
		cells.get("Tanzania").forEach(c -> java.util.Arrays.fill(c.getCurrentProd(), 2.0));
		step(config(false, "overlapping", "other"), "other", coreSupply()).checkBaseline(1990);
		List<String> other = Files.readAllLines(tempDir.resolve("other").resolve("plum_coupling")
				.resolve(ProductionStep.BASELINE_CHECK));
		assertEquals(11, other.stream().filter(l -> l.endsWith(",true")).count(), "Tanzania's rows but energy crops");
		assertTrue(other.stream().filter(l -> l.endsWith(",true")).allMatch(l -> l.startsWith("Tanzania,")));
	}

	@Test
	void theCheckStopsOnADriftFromCoresSupplyOrAnUnreadableCalibrationFile() throws Exception {
		Map<String, Map<String, Double>> drifted = coreSupply();
		drifted.remove("Tanzania");
		ProductionStep drifting = step(config(false, "sequential", "run"), "run", drifted);
		assertTrue(assertThrows(CouplingInputException.class, () -> drifting.checkBaseline(1990)).getMessage()
				.contains("drifted"));

		Path calibration = tempDir.resolve("calib").resolve("production").resolve("1990");
		Files.createDirectories(calibration);
		Files.writeString(calibration.resolve(ProductionFile.FILE), "Country,Commodity\nTanzania,wheat\n");
		ProductionStep step = step(config(false, "sequential", "run2"), "run2", coreSupply());
		assertTrue(assertThrows(CouplingInputException.class, () -> step.checkBaseline(1990)).getMessage()
				.contains("Production"));

		ProductionStep calibrationStep = step(config(true, "sequential", "calib2"), "calib2", coreSupply());
		assertThrows(IllegalStateException.class, () -> calibrationStep.checkBaseline(1990));
	}

	@Test
	void aYearIsNeverWrittenTwice() {
		ProductionStep step = step(config(true, "sequential", "calib"), "calib", coreSupply());
		step.yearStep(1990);

		CouplingInputException e = assertThrows(CouplingInputException.class, () -> step.yearStep(1990));

		assertTrue(e.getMessage().contains("already exists"), e.getMessage());
	}

	@Test
	void eachRunStartsItsProductionSentAfresh() throws Exception {
		step(config(true, "sequential", "calib"), "calib", coreSupply()).yearStep(1990);
		Path sent = tempDir.resolve("calib").resolve("plum_coupling").resolve(ProductionStep.PRODUCTION_SENT);
		assertEquals(40, Files.readAllLines(sent).size());

		step(config(true, "sequential", "calib2"), "calib", coreSupply());

		assertEquals(List.of(String.join(",", ProductionStep.PRODUCTION_SENT_HEADER)), Files.readAllLines(sent));
	}
}
