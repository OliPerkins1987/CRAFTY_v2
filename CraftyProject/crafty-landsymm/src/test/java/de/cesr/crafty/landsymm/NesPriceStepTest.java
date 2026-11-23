package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.core.cli.Config;
import de.cesr.crafty.core.crafty.Aft;
import de.cesr.crafty.core.crafty.Cell;
import de.cesr.crafty.core.crafty.ManagerTypes;

/**
 * The NES step on real core cells (50a). Region A has counted land of every kind, B has none (the floor), C has no
 * spend. Biodiversity is the only non-market service; C1 is a crop.
 */
class NesPriceStepTest {

	private static final String BIO = "Biodiversity";
	private static final String SUIT = "biodiversity_suit";
	private static final double FILE_SUIT = 0.37; // what the capitals file holds, which the step writes over

	@TempDir
	Path tempDir;

	private CoreStateForTests core;
	private Aft conservation;
	private Aft organic;
	private Aft farmer;
	private Aft ul;
	private Aft abandoned;
	private Map<String, Collection<Cell>> cells;
	private Map<String, Map<String, ConcurrentHashMap<Integer, Double>>> weightMaps;
	private CountedLand counted;

	@BeforeEach
	void setUp() {
		Config config = new Config();
		config.use_cell_area = true;
		core = new CoreStateForTests(config, List.of("C1", BIO), Set.of("C1"));

		conservation = aft("Conservation_Nonex", Map.of(BIO, Map.of(SUIT, 1.0, "Education", 1.0)), Map.of(BIO, 1.0));
		organic = aft("AgriEcoC3C", Map.of(BIO, Map.of(SUIT, 1.0), "C1", Map.of("Education", 1.0)),
				Map.of(BIO, 0.5, "C1", 2.0));
		farmer = aft("IntAgriC3C", Map.of("C1", Map.of("Education", 1.0)), Map.of("C1", 3.0));
		ul = new Aft("UL");
		ul.setType(ManagerTypes.MASK);
		abandoned = new Aft("Abandoned");
		counted = CountedLand.of(BIO, Map.of("Conservation_Nonex", 1.0, "UL", 1.0, "AgriEcoC3C", 0.2));

		cells = new TreeMap<>();
		int[] x = { 0 };
		cells.put("A", List.of(cell(x, 100, conservation), cell(x, 50, ul), cell(x, 200, organic), cell(x, 80, farmer),
				cell(x, 30, abandoned), cell(x, 40, null)));
		cells.put("B", List.of(cell(x, 60, farmer), cell(x, 90, farmer)));
		cells.put("C", List.of(cell(x, 10, conservation)));

		weightMaps = new TreeMap<>();
		weights("A", 2.0, 3.0);
		weights("B", 1.0, 1.0);
		weights("C", 0.0, 0.0);
	}

	@AfterEach
	void tearDown() {
		core.close();
	}

	private static Aft aft(String label, Map<String, Map<String, Double>> sensitivities, Map<String, Double> levels) {
		Aft a = new Aft(label);
		a.setType(ManagerTypes.AFT);
		a.setSensByService(sensitivities);
		a.getProductivityLevel().putAll(levels);
		return a;
	}

	private static Cell cell(int[] x, double area, Aft owner) {
		Cell c = new Cell(x[0]++, 0);
		c.setArea(area);
		c.getCapitals().put(SUIT, FILE_SUIT);
		c.getCapitals().put("Education", 0.5);
		c.setOwner(owner);
		return c;
	}

	/** A region's Biodiversity weights for 1990 and 1991 (its spend, million US$), and a C1 price. */
	private void weights(String region, double spend1990, double spend1991) {
		ConcurrentHashMap<Integer, Double> bio = new ConcurrentHashMap<>(Map.of(1990, spend1990, 1991, spend1991));
		ConcurrentHashMap<Integer, Double> crop = new ConcurrentHashMap<>(Map.of(1990, 300.0, 1991, 300.0));
		weightMaps.put(region, Map.of(BIO, bio, "C1", crop));
	}

	private PlumInputStep.Weights weights() {
		return (region, service) -> weightMaps.getOrDefault(region, Map.of()).get(service);
	}

	private double weight(String region, int year) {
		return weightMaps.get(region).get(BIO).get(year);
	}

	private ProductionStep.Regions regions() {
		return new ProductionStep.Regions() {
			@Override
			public Map<String, Collection<Cell>> cells() {
				return cells;
			}

			@Override
			public Map<String, Map<String, Double>> coreSupply() {
				return Map.of();
			}
		};
	}

	private NesPriceStep step(NesPriceRule rule) {
		NesBudget budget = NesBudget.fromWeights(weights(), BIO, cells.keySet(), 1990, 1991);
		NesPricing pricing = new NesPricing(BIO, SUIT, NesMetric.AREA, counted, budget, rule, weights());
		return new NesPriceStep(List.of(pricing), new CouplingOutputs(tempDir), regions());
	}

	@Test
	void thePriceIsTheSpendOverTheCountedLand() {
		step(NesPriceRule.BUDGET_PER_AREA).yearStep(1990);

		// A: Conservation 100 x 1 + UL 50 x 1 + AgriEco 200 x 0.2; the farmer, Abandoned and no owner count 0
		assertEquals(2e6 / 190, weight("A", 1990), 1e-9);
		assertEquals(3.0, weight("A", 1991), 0.0, "next year's spend is untouched");
		assertEquals(300.0, weightMaps.get("A").get("C1").get(1990), 0.0, "a market price is untouched");
	}

	@Test
	void aRegionWithNoCountedLandIsPricedOverOneAveragePixel() {
		step(NesPriceRule.BUDGET_PER_AREA).yearStep(1990);

		assertEquals(1e6 / 75, weight("B", 1990), 1e-9, "B's mean pixel is (60 + 90) / 2 ha");
		assertEquals(0.0, weight("C", 1990), 0.0, "no spend, no price");
	}

	@Test
	void theRuleFloorsTheCountedLandAtTheMeanPixel() {
		NesPriceRule r = NesPriceRule.BUDGET_PER_AREA;
		assertEquals(10.0, r.price(inputs(1000, 100, 50)), 1e-12, "D above M: B / D");
		assertEquals(20.0, r.price(inputs(1000, 50, 50)), 1e-12, "D = M");
		assertEquals(20.0, r.price(inputs(1000, 5, 50)), 1e-12, "a sliver: B / M, not B / D");
		assertEquals(20.0, r.price(inputs(1000, 0, 50)), 1e-12, "none: B / M");
		assertEquals(0.0, r.price(inputs(0, 0, 50)), 0.0, "no spend");
		assertTrue(inputs(1000, 5, 50).belowFloor());
		assertFalse(inputs(1000, 50, 50).belowFloor());
	}

	private static NesPriceRule.Inputs inputs(double budget, double metricArea, double meanPixel) {
		return new NesPriceRule.Inputs(BIO, "A", 1990, budget, metricArea, metricArea, meanPixel, Double.NaN);
	}

	@Test
	void theMetricIsWrittenOverTheFileValueOnEveryCellAndPaysProduction() throws Exception {
		step(NesPriceRule.BUDGET_PER_AREA).yearStep(1990);

		cells.values().forEach(regionCells -> regionCells
				.forEach(c -> assertEquals(1.0, c.getCapitals().getOrDefault(SUIT, Double.NaN), 0.0, "cell " + c.getX())));

		List<String> lines = Files.readAllLines(tempDir.resolve(NesPriceStep.NES_PRICES));
		assertEquals(String.join(",", NesPriceStep.NES_PRICES_HEADER), lines.get(0));
		assertEquals(4, lines.size(), "one row per region");
		// A's production: Conservation 1 x 0.5 x 100 ha + AgriEco 1 x 0.5 x 200 ha = 150; UL is a mask
		double price = 2e6 / 190;
		assertEquals(List.of("1990", BIO, "A", "2.0", "2000000.0", "190.0", "190.0", ProductionFile.format(500.0 / 6),
				"false", ProductionFile.format(price), "150.0", ProductionFile.format(price * 150),
				ProductionFile.format(price * 150 / 2e6)), List.of(lines.get(1).split(",", -1)));
		assertTrue(lines.get(2).startsWith("1990,Biodiversity,B,1.0,1000000.0,0.0,0.0,75.0,true,"), lines.get(2));
		assertTrue(lines.get(3).startsWith("1990,Biodiversity,C,0.0,0.0,10.0,10.0,10.0,false,0.0,"), lines.get(3));
		assertTrue(lines.get(3).endsWith(","), "no paid share without spend: " + lines.get(3));
	}

	@Test
	void eachYearFollowsTheLandUseAndTheRuleSeesLastYearsPrice() {
		List<NesPriceRule.Inputs> seen = new ArrayList<>();
		NesPriceRule recording = in -> {
			synchronized (seen) {
				seen.add(in);
			}
			return NesPriceRule.BUDGET_PER_AREA.price(in);
		};
		NesPriceStep step = step(recording);
		step.yearStep(1990);
		double price1990 = weight("A", 1990);

		// CapitalUpdater reloads the file's values; competition gave the farmer's cell to Conservation
		cells.values().forEach(regionCells -> regionCells.forEach(c -> c.getCapitals().put(SUIT, FILE_SUIT)));
		Cell farmed = cells.get("A").stream().filter(c -> c.getOwner() == farmer).findFirst().orElseThrow();
		farmed.setOwner(conservation);
		step.yearStep(1991);

		assertEquals(3e6 / 270, weight("A", 1991), 1e-9, "the spend is copied, so 1990's price didn't replace it");
		assertEquals(price1990, weight("A", 1990), 0.0);
		NesPriceRule.Inputs a1990 = seen.stream().filter(in -> in.region().equals("A") && in.year() == 1990).findFirst()
				.orElseThrow();
		NesPriceRule.Inputs a1991 = seen.stream().filter(in -> in.region().equals("A") && in.year() == 1991).findFirst()
				.orElseThrow();
		assertTrue(Double.isNaN(a1990.lastPrice()));
		assertEquals(price1990, a1991.lastPrice(), 0.0);
		cells.get("A").forEach(c -> assertEquals(1.0, c.getCapitals().getOrDefault(SUIT, Double.NaN), 0.0));
	}

	@Test
	void aBadPriceStopsTheYearWithNothingWritten() {
		NesPriceRule badForB = in -> in.region().equals("B") ? Double.NaN : NesPriceRule.BUDGET_PER_AREA.price(in);

		CouplingInputException e = assertThrows(CouplingInputException.class, () -> step(badForB).yearStep(1990));

		assertTrue(e.getMessage().contains("region B, Biodiversity in 1990: the price is NaN"), e.getMessage());
		assertEquals(2.0, weight("A", 1990), 0.0, "A's weight is still its spend");
		cells.get("A").forEach(c -> assertEquals(FILE_SUIT, c.getCapitals().getOrDefault(SUIT, Double.NaN), 0.0));
	}

	@Test
	void theStepOffDoesNothing() {
		NesPriceStep off = NesPriceStep.off();

		off.yearStep(1990);

		assertEquals(List.of(), off.services());
		assertEquals(2.0, weight("A", 1990), 0.0);
		cells.get("A").forEach(c -> assertEquals(FILE_SUIT, c.getCapitals().getOrDefault(SUIT, Double.NaN), 0.0));
		assertFalse(Files.exists(tempDir.resolve(NesPriceStep.NES_PRICES)));
	}

	@Test
	void theSwitchedOffFileIsNotWrittenButThePricesAreSet() {
		NesBudget budget = NesBudget.fromWeights(weights(), BIO, cells.keySet(), 1990, 1991);
		NesPricing pricing = new NesPricing(BIO, SUIT, NesMetric.AREA, counted, budget, NesPriceRule.BUDGET_PER_AREA,
				weights());
		NesPriceStep step = new NesPriceStep(List.of(pricing), new CouplingOutputs(tempDir, List.of()), regions());

		step.yearStep(1990);

		assertEquals(List.of(BIO), step.services());
		assertEquals(2e6 / 190, weight("A", 1990), 1e-9);
		assertFalse(Files.exists(tempDir.resolve(NesPriceStep.NES_PRICES)));
	}

	// ---- The spend ----

	@Test
	void theSpendIsCopiedAndCheckedForEveryRunYear() {
		NesBudget budget = NesBudget.fromWeights(weights(), BIO, cells.keySet(), 1990, 1991);
		weightMaps.get("A").get(BIO).put(1990, 999.0);
		assertEquals(2.0, budget.spendMusd("A", 1990), 0.0, "a copy, not a view");
		assertThrows(CouplingInputException.class, () -> budget.spendMusd("A", 1992));

		weightMaps.get("B").get(BIO).put(1991, -1.0);
		weightMaps.get("C").get(BIO).remove(1991);
		CouplingInputException e = assertThrows(CouplingInputException.class,
				() -> NesBudget.fromWeights(weights(), BIO, List.of("A", "B", "C", "D"), 1990, 1991));
		assertEquals(3, e.getProblems().size(), e.getMessage());
		assertTrue(e.getProblems().get(0).contains("region B, 1991") && e.getProblems().get(0).contains("-1.0"),
				e.getMessage());
		assertTrue(e.getProblems().get(1).contains("region C has no Biodiversity weight (its spend) for 1 of the run's "
				+ "years, from 1991"), e.getMessage());
		assertTrue(e.getProblems().get(2).contains("region D has no Biodiversity service"), e.getMessage());
	}

	@Test
	void manySpendProblemsAreListedUpTo20() {
		List<String> regions = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			regions.add("R" + (100 + i));
		}
		CouplingInputException e = assertThrows(CouplingInputException.class,
				() -> NesBudget.fromWeights(weights(), BIO, regions, 1990, 1991));
		assertEquals(21, e.getProblems().size());
		assertEquals("and 5 more", e.getProblems().get(20));
	}

	@Test
	void onlyTheAreaMetricExistsSoFar() {
		assertSame(NesMetric.AREA, NesMetric.of("area"));
		assertEquals(1.0, NesMetric.AREA.on(new Cell(0, 0)), 0.0);
		CouplingInputException e = assertThrows(CouplingInputException.class, () -> NesMetric.of("bii"));
		assertTrue(e.getMessage().contains("[area]"), e.getMessage());
	}
}
