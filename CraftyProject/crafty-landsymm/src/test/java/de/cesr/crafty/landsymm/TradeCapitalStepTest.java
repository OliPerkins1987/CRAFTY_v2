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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.core.cli.Config;
import de.cesr.crafty.core.crafty.Cell;

/**
 * The trade-capital step on real cells for the PLUM extract's three countries, with one trade capital,
 * c3_cereals_trade (wheat). Each region has four cells at its country's value and one edge cell at a neighbour's
 * value, the downscaling error.
 */
class TradeCapitalStepTest {

	private static final String CAPITAL = "c3_cereals_trade";

	@TempDir
	Path tempDir;

	private CoreStateForTests core;
	private RegionCountryKey key;
	private TradeCapitalMap capitals;
	private PlumYearOutputs y2029;
	private Map<String, Collection<Cell>> cells;

	@BeforeEach
	void setUp() throws Exception {
		core = new CoreStateForTests(new Config(), List.of("S1"), Set.of("S1"));
		key = ProductionFileTest.threeCountryKey(tempDir);
		Path capitalsCsv = tempDir.resolve("Capitals.csv");
		Files.writeString(capitalsCsv, "Label,Name,Type,Description,PLUM_name\nMA,x,Capital,x,\n" + CAPITAL
				+ ",x,Capital,x,wheat\n");
		capitals = TradeCapitalMap.read(capitalsCsv);
		y2029 = PlumYearOutputs.read(PlumYearOutputsTest.all1990Extract(), 2029);
		cells = new TreeMap<>();
		int x = 0;
		for (String region : key.regions()) {
			List<Cell> regionCells = new ArrayList<>();
			for (int i = 0; i < 5; i++) {
				Cell c = new Cell(x++, 0);
				c.setArea(100);
				c.getCapitals().put(CAPITAL, i < 4 ? 0.0954 : 0.9);
				c.getCapitals().put("MA", 0.5);
				regionCells.add(c);
			}
			cells.put(region, regionCells);
		}
	}

	@AfterEach
	void tearDown() {
		core.close();
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

	/** The step with no averaging (k = 1), so each year's value is the rule's. */
	private TradeCapitalStep step(PlumYear plumYear, TradeCapitalRule rule) {
		return step(plumYear, rule, 1);
	}

	private TradeCapitalStep step(PlumYear plumYear, TradeCapitalRule rule, int averagingYears) {
		return new TradeCapitalStep(plumYear, capitals, rule, 6, 1, averagingYears, key,
				new CouplingOutputs(tempDir.resolve("plum_coupling")), regions());
	}

	@Test
	void averagedOverKYearsEachValueMovesAFifthOfTheWayFromLastYears() {
		SelfSufficiencyLogisticRule rule = new SelfSufficiencyLogisticRule(6, 1);
		double tanzaniaRule = rule.value(0.1113 / (0.1113 + 0.0612));
		PlumYear plumYear = new PlumYear();
		TradeCapitalStep step = step(plumYear, rule, 5);

		plumYear.set(1990, y2029);
		step.yearStep(1990);
		double first = 0.8 * 0.0954 + 0.2 * tanzaniaRule;
		cells.get("Tanzania").forEach(c -> assertEquals(first, value(c), 1e-12, "from the file value"));

		reloadFileValues();
		plumYear.set(1991, y2029);
		step.yearStep(1991);
		double second = 0.8 * first + 0.2 * tanzaniaRule;
		cells.get("Tanzania").forEach(c -> assertEquals(second, value(c), 1e-12, "from 1990's value"));
	}

	@Test
	void theAverageIsLastTimesOneLessOneOverKPlusRuleOverK() {
		assertEquals(0.9, TradeCapitalStep.averaged(0.3, 0.9, 1), 1e-12, "k = 1: the rule's value");
		assertEquals(0.8 * 0.2 + 0.2 * 0.7, TradeCapitalStep.averaged(0.2, 0.7, 5), 1e-12);
		assertEquals(0.9 * 0.2 + 0.1 * 0.7, TradeCapitalStep.averaged(0.2, 0.7, 10), 1e-12);
	}

	private static double value(Cell c) {
		return c.getCapitals().getOrDefault(CAPITAL, Double.NaN);
	}

	@Test
	void eachRegionGetsOneValueOverEveryCellEdgeCellsIncluded() {
		PlumYear plumYear = new PlumYear();
		plumYear.set(1990, y2029);
		SelfSufficiencyLogisticRule rule = new SelfSufficiencyLogisticRule(6, 1);

		step(plumYear, rule).yearStep(1990);

		double tanzania = rule.value(0.1113 / (0.1113 + 0.0612));
		double argentina = rule.value(11.1993 / (11.1993 - 1.6884));
		cells.get("Tanzania").forEach(c -> assertEquals(tanzania, value(c), 1e-12));
		cells.get("Argentina").forEach(c -> assertEquals(argentina, value(c), 1e-12));
		assertEquals(0.5, cells.get("Tanzania").iterator().next().getCapitals().getOrDefault("MA", Double.NaN), 0.0,
				"other capitals untouched");
	}

	@Test
	void theFileValueIsTheAreaWeightedMedian() {
		assertEquals(0.0954, TradeCapitalStep.weightedMedian(cells.get("Tanzania"), CAPITAL), 0.0,
				"the edge cell doesn't move it");

		List<Cell> skewed = new ArrayList<>();
		double[][] valueArea = { { 0.1, 1 }, { 0.2, 1 }, { 0.9, 5 } };
		for (int i = 0; i < valueArea.length; i++) {
			Cell c = new Cell(100 + i, 0);
			c.setArea(valueArea[i][1]);
			c.getCapitals().put(CAPITAL, valueArea[i][0]);
			skewed.add(c);
		}
		assertEquals(0.9, TradeCapitalStep.weightedMedian(skewed, CAPITAL), 0.0, "5 of the 7 area units");
		assertTrue(Double.isNaN(TradeCapitalStep.weightedMedian(skewed, "no_such_capital")));
	}

	@Test
	void eachRegionsValueCarriesOverToTheNextYearThroughAnyRule() {
		// A rule that only adds 0.1 to last year's value
		TradeCapitalRule plusOne = in -> Math.min(1, in.lastValue() + 0.1);
		PlumYear plumYear = new PlumYear();
		TradeCapitalStep step = step(plumYear, plusOne);

		plumYear.set(1990, y2029);
		step.yearStep(1990);
		cells.get("Senegal_and_co").forEach(c -> assertEquals(0.0954 + 0.1, value(c), 1e-12));

		// CapitalUpdater reloads the file's values at the start of each year
		reloadFileValues();
		plumYear.set(1991, y2029);
		step.yearStep(1991);

		cells.get("Senegal_and_co").forEach(c -> assertEquals(0.0954 + 0.2, value(c), 1e-12,
				"built on 1990's value, not the reloaded file's"));
	}

	/** What CapitalUpdater does each year: every cell back to its file value. */
	private void reloadFileValues() {
		cells.values().forEach(regionCells -> {
			int i = 0;
			for (Cell c : regionCells) {
				c.getCapitals().put(CAPITAL, i++ < 4 ? 0.0954 : 0.9);
			}
		});
	}

	@Test
	void everyValueIsRecorded() throws Exception {
		PlumYear plumYear = new PlumYear();
		plumYear.set(1990, y2029);

		step(plumYear, new SelfSufficiencyLogisticRule(6, 1)).yearStep(1990);

		List<String> lines = Files.readAllLines(tempDir.resolve("plum_coupling").resolve(TradeCapitalStep.TRADE_CAPITALS));
		assertEquals(String.join(",", TradeCapitalStep.TRADE_CAPITALS_HEADER), lines.get(0));
		assertEquals(1 + 3, lines.size(), "3 regions x 1 trade capital");
		String[] tanzania = lines.stream().filter(l -> l.startsWith("1990,Tanzania,Tanzania," + CAPITAL + ",wheat,"))
				.findFirst().orElseThrow().split(",");
		assertEquals(0.1113, Double.parseDouble(tanzania[5]), 1e-12);
		assertEquals(0.1113 + 0.0612, Double.parseDouble(tanzania[6]), 1e-12);
		assertEquals(0.1113 / (0.1113 + 0.0612), Double.parseDouble(tanzania[7]), 1e-12);
		assertEquals("6.0", tanzania[8]);
		assertEquals("1.0", tanzania[9]);
		assertEquals("1", tanzania[10], "averaging years");
		assertEquals("0.0954", tanzania[11], "last year's value is the file value in the start year");
		assertEquals("0.0954", tanzania[12]);
		double ruleValue = new SelfSufficiencyLogisticRule(6, 1).value(0.1113 / (0.1113 + 0.0612));
		assertEquals(ruleValue, Double.parseDouble(tanzania[13]), 1e-12);
		assertEquals(ruleValue, Double.parseDouble(tanzania[14]), 1e-12, "k = 1: the new value is the rule's");
	}

	@Test
	void aValueOutsideZeroToOneStopsTheYearWithNoCellChanged() {
		PlumYear plumYear = new PlumYear();
		plumYear.set(1990, y2029);

		CouplingInputException e = assertThrows(CouplingInputException.class,
				() -> step(plumYear, in -> 1.5).yearStep(1990));

		assertTrue(e.getMessage().contains("from 0 to 1"), e.getMessage());
		assertEquals(0.0954, value(cells.get("Argentina").iterator().next()), 0.0);
	}

	@Test
	void aRegionWithNoCellsStops() {
		cells.remove("Argentina");
		PlumYear plumYear = new PlumYear();
		plumYear.set(1990, y2029);

		assertTrue(assertThrows(CouplingInputException.class,
				() -> step(plumYear, new SelfSufficiencyLogisticRule(6, 1)).yearStep(1990)).getMessage()
				.contains("Region Argentina has no cells"));
	}

	@Test
	void switchedOffItDoesNothing() {
		TradeCapitalStep.off().yearStep(1990);

		cells.values().forEach(cs -> cs.forEach(c -> assertTrue(value(c) == 0.0954 || value(c) == 0.9)));
	}
}
