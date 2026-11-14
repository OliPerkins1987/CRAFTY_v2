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

/** The consumption step on the PLUM extract's three countries, each supplying 1 Mt of every market service. */
class ConsumptionStepTest {

	@TempDir
	Path tempDir;

	private ServiceCommodityMap services;
	private RegionCountryKey key;
	private List<String> craftyServices;
	private CoreStateForTests core;
	private PlumYearOutputs y2029;
	private List<Map<String, Map<Integer, double[]>>> handedOver;

	@BeforeEach
	void setUp() throws Exception {
		services = ServiceCommodityMap.read(ServiceCommodityMapTest.globalServices());
		key = ProductionFileTest.threeCountryKey(tempDir);
		CsvReader.Table table = CsvReader.readAll(ServiceCommodityMapTest.globalServices());
		craftyServices = new ArrayList<>();
		for (int i = 0; i < table.size(); i++) {
			craftyServices.add(table.get(i, "Name"));
		}
		core = new CoreStateForTests(new Config(), craftyServices,
				services.links().stream().map(ServiceCommodityMap.Link::service).collect(Collectors.toSet()));
		y2029 = PlumYearOutputs.read(PlumYearOutputsTest.all1990Extract(), 2029);
		handedOver = new ArrayList<>();
	}

	@AfterEach
	void tearDown() {
		core.close();
	}

	private ProductionStep.Regions regions() {
		return new ProductionStep.Regions() {
			@Override
			public Map<String, Collection<Cell>> cells() {
				return Map.of();
			}

			@Override
			public Map<String, Map<String, Double>> coreSupply() {
				Map<String, Map<String, Double>> supply = new TreeMap<>();
				for (String region : key.regions()) {
					Map<String, Double> s = new HashMap<>();
					craftyServices.forEach(service -> s.put(service, 1e6));
					supply.put(region, s);
				}
				return supply;
			}
		};
	}

	private ConsumptionStep step(PlumYear plumYear) {
		return new ConsumptionStep(plumYear, services, key, new CouplingOutputs(tempDir.resolve("plum_coupling")),
				regions(), handedOver::add);
	}

	@Test
	void eachRegionsConsumptionGoesToCoreForTheYearOnly() {
		PlumYear plumYear = new PlumYear();
		plumYear.set(1990, y2029);

		step(plumYear).yearStep(1990);

		assertEquals(1, handedOver.size());
		Map<String, Map<Integer, double[]>> byRegion = handedOver.get(0);
		assertEquals(key.regions(), byRegion.keySet());
		double[] tanzania = byRegion.get("Tanzania").get(1990);
		assertEquals(Map.of(1990, tanzania).keySet(), byRegion.get("Tanzania").keySet(), "this year only");
		assertEquals(craftyServices.size(), tanzania.length);
		assertEquals((0.1113 + 0.0612) / 1e-6, tanzania[craftyServices.indexOf("C3cereals")], 1e-3);
		assertEquals(0.0, tanzania[craftyServices.indexOf("Biodiversity")], 0.0, "non-market services: 0");
		assertEquals(5.8947 / 1e-6, tanzania[craftyServices.indexOf("Pasture")], 1e-3);
	}

	@Test
	void everyValueIsRecordedInExportBasis() throws Exception {
		PlumYear plumYear = new PlumYear();
		plumYear.set(1991, y2029);

		step(plumYear).yearStep(1991);

		List<String> lines = Files.readAllLines(tempDir.resolve("plum_coupling").resolve(ConsumptionStep.EXPORT_BASIS));
		assertEquals(String.join(",", ConsumptionStep.EXPORT_BASIS_HEADER), lines.get(0));
		assertEquals(1 + 3 * 13, lines.size());
		String[] argentinaWheat = lines.stream().filter(l -> l.startsWith("1991,Argentina,Argentina,C3cereals,wheat,"))
				.findFirst().orElseThrow().split(",");
		assertEquals(11.1993 - 1.6884, Double.parseDouble(argentinaWheat[5]), 1e-9);
		assertEquals(1e6, Double.parseDouble(argentinaWheat[6]), 0.0);
	}

	@Test
	void withoutTheYearsPlumRowsTheStepStops() {
		PlumYear plumYear = new PlumYear();
		plumYear.set(1990, y2029);

		assertThrows(CouplingInputException.class, () -> step(plumYear).yearStep(1991));
		assertEquals(List.of(), handedOver);
	}

	@Test
	void switchedOffItDoesNothing() {
		ConsumptionStep.off().yearStep(1990);

		assertEquals(List.of(), handedOver);
		assertFalse(Files.exists(tempDir.resolve("plum_coupling")));
	}
}
