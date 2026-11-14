package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.landsymm.PriceScaling.Basis;
import de.cesr.crafty.landsymm.PriceScaling.Scaling;

/**
 * The prices step on the PLUM extract's three countries: the calibration's final year is all1990's 2030, and
 * "PLUM's 1990" (the first scenario year) is played by its 2029 rows. Every region's file weight is 300 in 1990
 * and 999 in 1991 for every market service, and 42 for the non-market ones.
 */
class PlumInputStepTest {

	@TempDir
	Path tempDir;

	private PlumYearOutputs y2029;
	private PlumYearOutputs y2030;
	private ServiceCommodityMap services;
	private RegionCountryKey key;
	private Map<String, Map<String, Map<Integer, Double>>> store;
	private PlumInputStep.Weights weights;
	private List<Integer> requested;

	@BeforeEach
	void setUp() throws Exception {
		y2029 = PlumYearOutputs.read(PlumYearOutputsTest.all1990Extract(), 2029);
		y2030 = PlumYearOutputs.read(PlumYearOutputsTest.all1990Extract(), 2030);
		services = ServiceCommodityMap.read(ServiceCommodityMapTest.globalServices());
		key = ProductionFileTest.threeCountryKey(tempDir);
		store = new TreeMap<>();
		for (String region : key.regions()) {
			Map<String, Map<Integer, Double>> byService = new HashMap<>();
			services.links().forEach(l -> byService.put(l.service(), new HashMap<>(Map.of(1990, 300.0, 1991, 999.0))));
			byService.put("Biodiversity", new HashMap<>(Map.of(1990, 42.0, 1991, 42.0)));
			store.put(region, byService);
		}
		weights = (region, service) -> store.containsKey(region) ? store.get(region).get(service) : null;
		requested = new ArrayList<>();
	}

	private PlumInputStep step(Scaling scaling, Basis basis, PlumYear plumYear) {
		PriceScaling ps = PriceScaling.create(scaling, basis, services, key,
				PlumInputStep.startWeights(weights, services, key, 1990), y2030);
		return new PlumInputStep(plumYear, ps, key, new CouplingOutputs(tempDir.resolve("plum_coupling")), weights,
				year -> {
					requested.add(year);
					return y2029;
				}, 1990, y2030);
	}

	private double weight(String region, String service, int year) {
		return store.get(region).get(service).get(year);
	}

	@Test
	void withARegionalStartEachRegionStartsAtItsCountrysMarkUpAndFollowsItsCountry() {
		PriceScaling ps = PriceScaling.create(Scaling.INDEX, Basis.DOMESTIC, services, key,
				PlumInputStep.startWeights(weights, services, key, 1990), y2030, true);
		PlumInputStep step = new PlumInputStep(new PlumYear(), ps, key,
				new CouplingOutputs(tempDir.resolve("plum_coupling")), weights, year -> y2029, 1990, y2030);

		step.yearStep(1990);
		step.yearStep(1991);

		double worldStart = y2030.price("wheat");
		double tanzaniaStart = PriceScaling.plumPrice(Basis.DOMESTIC, y2030, "Tanzania", "wheat");
		assertEquals(300 * tanzaniaStart / worldStart, weight("Tanzania", "C3cereals", 1990), 1e-9);
		assertTrue(weight("Tanzania", "C3cereals", 1990) > 300, "Tanzania imports wheat: above the file price");
		assertEquals(300.0, weight("Argentina", "C3cereals", 1990), 1e-9, "Argentina exports it: the world price");
		assertEquals(300 / worldStart * PriceScaling.plumPrice(Basis.DOMESTIC, y2029, "Tanzania", "wheat"),
				weight("Tanzania", "C3cereals", 1991), 1e-9, "then its country price x the world factor");
	}

	@Test
	void theStartYearUsesTheCalibrationsFinalRowsSoIndexGivesTheFileWeights() {
		for (Basis basis : Basis.values()) {
			setUpQuietly();
			PlumYear plumYear = new PlumYear();

			step(Scaling.INDEX, basis, plumYear).yearStep(1990);

			assertEquals(List.of(), requested, "no wait in the start year");
			for (String region : key.regions()) {
				for (ServiceCommodityMap.Link l : services.links()) {
					assertEquals(300.0, weight(region, l.service(), 1990), 1e-9, basis + ", " + region + ", " + l.service());
				}
				assertEquals(42.0, weight(region, "Biodiversity", 1990), 0.0, "non-market services keep their weight");
			}
			assertSame(y2030, plumYear.rows(1990));
		}
	}

	private void setUpQuietly() {
		try {
			setUp();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void underFactorScalingTheStartYearIsPlumsPriceTimesTheFactor() {
		step(Scaling.FACTOR, Basis.DOMESTIC, new PlumYear()).yearStep(1990);

		assertEquals(1000 * PriceScaling.plumPrice(Basis.DOMESTIC, y2030, "Tanzania", "wheat"),
				weight("Tanzania", "C3cereals", 1990), 1e-9);
	}

	@Test
	void aLaterYearWaitsForPlumsYearBeforeAndFollowsIt() {
		PlumYear plumYear = new PlumYear();
		PlumInputStep step = step(Scaling.INDEX, Basis.DOMESTIC, plumYear);

		step.yearStep(1991);

		assertEquals(List.of(1990), requested, "PLUM's 1990, cleared on CRAFTY's 1990 production");
		double expected = 300 / PriceScaling.plumPrice(Basis.DOMESTIC, y2030, "Tanzania", "wheat")
				* PriceScaling.plumPrice(Basis.DOMESTIC, y2029, "Tanzania", "wheat");
		assertEquals(expected, weight("Tanzania", "C3cereals", 1991), 1e-9);
		assertNotEquals(999.0, weight("Argentina", "Pasture", 1991));
		assertEquals(300.0, weight("Tanzania", "C3cereals", 1990), 0.0, "other years untouched");
		assertSame(y2029, plumYear.rows(1991));
	}

	@Test
	void everyPriceIsRecordedWithWhatItWasMadeFrom() throws Exception {
		step(Scaling.INDEX, Basis.DOMESTIC, new PlumYear()).yearStep(1991);

		List<String> lines = Files.readAllLines(tempDir.resolve("plum_coupling").resolve(PlumInputStep.PRICES_RECEIVED));
		assertEquals(String.join(",", PlumInputStep.PRICES_RECEIVED_HEADER), lines.get(0));
		assertEquals(1 + 3 * 13, lines.size(), "3 regions x 13 market services");
		String[] tanzaniaWheat = lines.stream().filter(l -> l.startsWith("1991,Tanzania,Tanzania,C3cereals,wheat,"))
				.findFirst().orElseThrow().split(",");
		assertEquals(0.10112894, Double.parseDouble(tanzaniaWheat[5]), 1e-12, "world price, 2029's row");
		assertEquals(0.1319 / 0.1013, Double.parseDouble(tanzaniaWheat[6]), 1e-9, "import mark-up");
		assertEquals(0.0612 / (0.1113 + 0.0612), Double.parseDouble(tanzaniaWheat[7]), 1e-9, "import share");
		assertEquals("999.0", tanzaniaWheat[10], "the file weight it replaced");
		assertEquals(weight("Tanzania", "C3cereals", 1991), Double.parseDouble(tanzaniaWheat[11]), 0.0);
	}

	@Test
	void ungrazedGrassIsPastureLessRuminantFeed() throws Exception {
		step(Scaling.INDEX, Basis.DOMESTIC, new PlumYear()).yearStep(1990);

		List<String> lines = Files.readAllLines(tempDir.resolve("plum_coupling").resolve(PlumInputStep.UNGRAZED_GRASS));
		assertEquals(String.join(",", PlumInputStep.UNGRAZED_GRASS_HEADER), lines.get(0));
		assertEquals(1 + 3, lines.size());
		PlumYearOutputs.Domestic pasture = y2030.domestic("Tanzania", "pasture");
		String[] tanzania = lines.stream().filter(l -> l.startsWith("1990,Tanzania,")).findFirst().orElseThrow()
				.split(",");
		assertEquals(pasture.trade().production() - pasture.ruminantFeed(), Double.parseDouble(tanzania[5]), 1e-12);
	}

	@Test
	void aProblemStopsTheYearWithoutChangingAnyWeight() {
		PlumInputStep step = step(Scaling.INDEX, Basis.DOMESTIC, new PlumYear());
		store.get("Tanzania").remove("C3rice");

		CouplingInputException e = assertThrows(CouplingInputException.class, () -> step.yearStep(1991));

		assertTrue(e.getMessage().contains("region Tanzania has no service C3rice"), e.getMessage());
		assertEquals(999.0, weight("Argentina", "C3cereals", 1991), 0.0, "nothing set");
	}

	@Test
	void plumsCountriesMustBeCraftys() throws Exception {
		Path gis = tempDir.resolve("two_Regions.csv");
		Files.writeString(gis, "\"X\",\"Y\",\"Region_Code\",\"PLUM_country\"\n1,1,\"Tanzania\",\"Tanzania\"\n"
				+ "2,1,\"Argentina\",\"Argentina\"\n");
		RegionCountryKey twoCountries = RegionCountryKey.read(gis);
		PriceScaling scaling = PriceScaling.create(Scaling.INDEX, Basis.DOMESTIC, services, twoCountries,
				PlumInputStep.startWeights(weights, services, twoCountries, 1990), y2030);
		PlumInputStep step = new PlumInputStep(new PlumYear(), scaling, twoCountries,
				new CouplingOutputs(tempDir.resolve("other")), weights, year -> y2029, 1990, y2030);

		CouplingInputException e = assertThrows(CouplingInputException.class, () -> step.yearStep(1991));

		assertTrue(e.getMessage().contains("Senegal Gambia & Cabo Verde"), e.getMessage());
	}

	@Test
	void theSharedYearOnlyGivesTheYearItWasSetFor() {
		PlumYear plumYear = new PlumYear();
		assertThrows(CouplingInputException.class, () -> plumYear.rows(1990));

		plumYear.set(1990, y2030);

		assertSame(y2030, plumYear.rows(1990));
		assertThrows(CouplingInputException.class, () -> plumYear.rows(1991));
	}

	@Test
	void aCalibrationRunsStepNeverRuns() {
		assertThrows(IllegalStateException.class, () -> PlumInputStep.inCalibrationRun().yearStep(1990));
	}
}
