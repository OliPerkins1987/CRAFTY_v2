package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.cesr.crafty.core.cli.Config;
import de.cesr.crafty.core.crafty.Aft;
import de.cesr.crafty.core.crafty.Cell;
import de.cesr.crafty.core.crafty.ManagerTypes;

/** The supply pass on real core cells: S1 and S2 are market goods, N1 isn't. */
class SupplyPassTest {

	private static final List<String> MARKET = List.of("S1", "S2");

	private Config config;
	private CoreStateForTests core;

	@BeforeEach
	void setUp() {
		config = new Config();
		config.use_cell_area = true;
		config.use_self_consumption = true;
		core = new CoreStateForTests(config, List.of("S1", "N1", "S2"), Set.of("S1", "S2"));
	}

	@AfterEach
	void tearDown() {
		core.close();
	}

	/** A cell with stored production (per unit area) for S1, N1 and S2, and an owner's self-consumption share. */
	private static Cell cell(int x, int y, double area, double s1, double n1, double s2, double selfConsumption) {
		Cell c = new Cell(x, y);
		c.setArea(area);
		c.getCurrentProd()[0] = s1;
		c.getCurrentProd()[1] = n1;
		c.getCurrentProd()[2] = s2;
		c.setCurrentSelfConsumption(selfConsumption);
		return c;
	}

	@Test
	void supplyIsAreaTimesProductionLessSelfConsumptionAndGrossKeepsIt() {
		List<Cell> cells = List.of(cell(0, 0, 100, 2.0, 9.0, 0.5, 0.0), cell(1, 0, 50, 4.0, 9.0, 1.0, 0.25));

		SupplyPass.RegionSupply r = SupplyPass.sum(Map.of("A", cells), MARKET, false, true).get("A");

		assertEquals(MARKET, new ArrayList<>(r.supply().keySet()), "market services only, in the order given");
		assertEquals(100 * 2.0 + 50 * 4.0 * 0.75, r.supply().get("S1"), 1e-9);
		assertEquals(100 * 0.5 + 50 * 1.0 * 0.75, r.supply().get("S2"), 1e-9);
		assertEquals(100 * 2.0 + 50 * 4.0, r.gross().get("S1"), 1e-9);
		assertEquals(100 * 0.5 + 50 * 1.0, r.gross().get("S2"), 1e-9);
	}

	@Test
	void withoutCellAreaSupplyAndGrossArePerCell() {
		config.use_cell_area = false;
		List<Cell> cells = List.of(cell(0, 0, 100, 2.0, 0, 0, 0.5));

		SupplyPass.RegionSupply r = SupplyPass.sum(Map.of("A", cells), MARKET, false, false).get("A");

		assertEquals(1.0, r.supply().get("S1"), 1e-12);
		assertEquals(2.0, r.gross().get("S1"), 1e-12);
	}

	@Test
	void theSumIsCoresOwnToTheLastBitAndPassesTheDriftCheck() {
		List<Cell> cells = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			cells.add(cell(i % 17, i / 17, 1 + Math.sqrt(i), Math.PI * i / 7, i, Math.E / (i + 1), (i % 3) / 4.0));
		}
		Map<String, Double> coreSupply = CoreStateForTests.coreSum(cells);

		Map<String, SupplyPass.RegionSupply> pass = SupplyPass.sum(Map.of("A", cells), MARKET, false, true);

		for (String s : MARKET) {
			assertEquals(coreSupply.get(s), pass.get("A").supply().get(s), 0.0, s);
		}
		assertEquals(List.of(), SupplyPass.driftProblems(pass, Map.of("A", coreSupply)));
	}

	@Test
	void recalculationGivesTheCurrentOwnersProductionAndDoesntAgeTheOwner() {
		Aft farmer = new Aft("Farmer");
		farmer.setType(ManagerTypes.AFT);
		farmer.setSensByService(Map.of("S1", Map.of("capA", 1.0), "S2", Map.of("capA", 2.0)));
		farmer.getProductivityLevel().put("S1", 3.0);
		farmer.getProductivityLevel().put("S2", 5.0);
		farmer.setSelfConsumption(0.25);
		// The production stored from an earlier owner, before this year's competition gave the cell to the farmer
		Cell c = cell(0, 0, 10, 100, 0, 100, 0);
		c.getCapitals().put("capA", 2.0);
		c.setOwner(farmer);
		c.setOwnerLifeCounter(4);

		SupplyPass.RegionSupply stale = SupplyPass.sum(Map.of("A", List.of(c)), MARKET, false, true).get("A");
		SupplyPass.RegionSupply fresh = SupplyPass.sum(Map.of("A", List.of(c)), MARKET, true, true).get("A");

		assertEquals(1000, stale.supply().get("S1"), 1e-9);
		assertEquals(2.0 * 3 * 10 * 0.75, fresh.supply().get("S1"), 1e-9, "capA x level x area, less 25%");
		assertEquals(4.0 * 5 * 10 * 0.75, fresh.supply().get("S2"), 1e-9, "capA^2 x level x area, less 25%");
		assertEquals(2.0 * 3 * 10, fresh.gross().get("S1"), 1e-9);
		assertEquals(4, c.getOwnerLifeCounter(), "core's regionalSupply() would have aged the owner a year");
	}

	@Test
	void theDriftCheckReadsCoresSupplyAndStopsOnAChange() {
		List<Cell> cells = List.of(cell(0, 0, 100, 2.0, 9.0, 0.5, 0.0), cell(1, 0, 50, 4.0, 9.0, 1.0, 0.25));
		Map<String, SupplyPass.RegionSupply> pass = SupplyPass.sum(Map.of("A", cells), MARKET, false, true);
		Map<String, Double> coreSupply = CoreStateForTests.coreSum(cells);
		Map<String, Double> before = new HashMap<>(coreSupply);

		assertEquals(List.of(), SupplyPass.driftProblems(pass, Map.of("A", coreSupply)));
		assertEquals(before, coreSupply, "read, not changed");

		Map<String, Double> changed = new HashMap<>(coreSupply);
		changed.put("S2", changed.get("S2") * (1 + 1e-6));
		List<String> problems = SupplyPass.driftProblems(pass, Map.of("A", changed));
		assertEquals(2, problems.size(), String.join("\n", problems));
		assertTrue(problems.get(1).startsWith("A, S2"), problems.get(1));

		assertEquals(3, SupplyPass.driftProblems(pass, Map.of()).size(), "a region core has no supply for");
	}

	@Test
	void aMarketServiceCraftyDoesntHaveStops() {
		CouplingInputException e = assertThrows(CouplingInputException.class,
				() -> SupplyPass.sum(Map.of("A", List.of(cell(0, 0, 1, 1, 1, 1, 0))), List.of("S1", "S9"), false, true));

		assertTrue(e.getMessage().contains("[S9]"), e.getMessage());
	}
}
