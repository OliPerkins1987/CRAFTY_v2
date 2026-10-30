package de.cesr.crafty.core.crafty;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import de.cesr.crafty.core.cli.Config;
import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.dataLoader.serivces.ServiceSet;
import de.cesr.crafty.core.output.Listener;
import de.cesr.crafty.core.output.Tracker;
import de.cesr.crafty.core.updaters.CapitalUpdater;
import de.cesr.crafty.core.updaters.ProductionCostUpdater;
import de.cesr.crafty.core.updaters.Timestep;

class CellTest {

	@BeforeEach
	void resetCounters() {
		// make sure we start clean for giveUp() tests
		if (Listener.landUseChangeCounter != null) {
			Listener.landUseChangeCounter.set(0);
		}
	}

	// -----------------------------
	// productivity(...)
	// -----------------------------

	@Test
	void productivity_returnsZero_whenAftNull() {
		Cell c = new Cell(0, 0);
		assertEquals(0.0, c.productivity(null, "S1"), 1e-12);
	}

	@Test
	void productivity_returnsZero_whenAftNotInteract() {
		Cell c = new Cell(0, 0);

		Aft a = mock(Aft.class);
		when(a.isInteract()).thenReturn(false);

		assertEquals(0.0, c.productivity(a, "S1"), 1e-12);
	}

	@Test
	void productivity_returnsZero_whenSensitivitiesMissingOrEmpty() throws Throwable {
		withServices(List.of("S1"), () -> {
			Cell c = new Cell(0, 0);

			Aft a = mock(Aft.class);
			when(a.isInteract()).thenReturn(true);
			when(a.getSensByService()).thenReturn(new ConcurrentHashMap<>());

			assertEquals(0.0, c.productivity(a, "S1"), 1e-12);

			Map<String, Map<String, Double>> sens = new ConcurrentHashMap<>();
			sens.put("S1", new HashMap<>()); // empty
			when(a.getSensByService()).thenReturn(sens);

			assertEquals(0.0, c.productivity(a, "S1"), 1e-12);
		});
	}

	@Test
	void productivity_computesExpectedProduct() throws Throwable {
		withServices(List.of("S1"), () -> {
			Cell c = new Cell(1, 2);

			// capitals: capA=2, capB=9, capC=100
			Map<String, Double> caps = new ConcurrentHashMap<>();
			caps.put("capA", 2.0);
			caps.put("capB", 9.0);
			caps.put("capC", 100.0);
			c.getCapitals().putAll(caps);

			// sensitivities for S1: capA^1 * capB^0.5 * capC^0 (ignored)
			Map<String, Double> exps = new LinkedHashMap<>();
			exps.put("capA", 1.0);
			exps.put("capB", 0.5);
			exps.put("capC", 0.0);

			Map<String, Map<String, Double>> sensByService = new ConcurrentHashMap<>();
			sensByService.put("S1", exps);

			ConcurrentHashMap<String, Double> levels = new ConcurrentHashMap<>();
			levels.put("S1", 10.0);

			Aft a = mock(Aft.class);
			when(a.isInteract()).thenReturn(true);
			when(a.getSensByService()).thenReturn(sensByService);
			when(a.getProductivityLevel()).thenReturn(levels);

			// expected: (2 * sqrt(9)) * 10 = (2 * 3) * 10 = 60
			assertEquals(60.0, c.productivity(a, "S1"), 1e-12);
		});
	}

	@Test
	void productivity_countsACapitalTheCellNeverGotAsZero() throws Throwable {
		withServices(List.of("S1"), () -> {
			Cell c = new Cell(1, 2);
			c.getCapitals().put("capA", 2.0);

			Aft a = mock(Aft.class);
			when(a.isInteract()).thenReturn(true);
			when(a.getSensByService()).thenReturn(Map.of("S1", Map.of("capA", 1.0, "capB", 1.0)));
			when(a.getProductivityLevel()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 10.0)));

			assertEquals(0.0, c.productivity(a, "S1"), 0.0, "capB missing: 2 * 0 * 10");

			c.getCapitals().put("capB", 3.0);
			assertEquals(60.0, c.productivity(a, "S1"), 1e-12, "capB given: 2 * 3 * 10");
		});
	}

	@Test
	void productivity_isNaN_whenACapitalIsExplicitlyNaN() throws Throwable {
		withServices(List.of("S1"), () -> {
			Cell c = new Cell(1, 2);
			c.getCapitals().put("capA", Double.NaN); // as CRAFTY-react could hand over

			Aft a = mock(Aft.class);
			when(a.isInteract()).thenReturn(true);
			when(a.getSensByService()).thenReturn(Map.of("S1", Map.of("capA", 1.0)));
			when(a.getProductivityLevel()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 10.0)));

			assertTrue(Double.isNaN(c.productivity(a, "S1")), "a NaN capital is a value, not a missing one");
		});
	}

	// -----------------------------
	// competitiveness(...)
	// -----------------------------

	@Test
	void competitiveness_computesExpectedProduct() throws Throwable {
		withServices(List.of("S1"), () -> {
			Cell c = new Cell(1, 2);

			Map<String, Double> caps = new ConcurrentHashMap<>();
			caps.put("capA", 2.0);
			caps.put("capB", 9.0);
			caps.put("capC", 100.0);
			c.getCapitals().putAll(caps);

			Map<String, Double> exps = new LinkedHashMap<>();
			exps.put("capA", 1.0);
			exps.put("capB", 0.5);
			exps.put("capC", 0.0);

			Map<String, Map<String, Double>> sensByService = new ConcurrentHashMap<>();
			sensByService.put("S1", exps);

			ConcurrentHashMap<String, Double> levels = new ConcurrentHashMap<>();
			levels.put("S1", 10.0);

			Aft a = mock(Aft.class);
			when(a.isInteract()).thenReturn(true);
			when(a.getSensByService()).thenReturn(sensByService);
			when(a.getProductivityLevel()).thenReturn(levels);

			assertEquals(60.0, c.competitiveness(a, "S1"), 1e-12);
		});
	}

	@Test
	void competitiveness_isLessThanOrEqualToProductivity_whenCapitalsInUnitRange() throws Throwable {
		withServices(List.of("S1"), () -> {
			Object cfg = ensureConfigInstance();
			setField(cfg, "separate_production_competitiveness", true);

			try {
				CapitalUpdater.getCapitalTypes().clear();
				CapitalUpdater.getCapitalTypes().put("capA", "Suitability");
				CapitalUpdater.getCapitalTypes().put("capB", "Capital");

				Cell c = new Cell(3, 4);

				Map<String, Double> caps = new ConcurrentHashMap<>();
				caps.put("capA", 0.5);
				caps.put("capB", 0.8);
				c.getCapitals().putAll(caps);

				Map<String, Double> exps = new LinkedHashMap<>();
				exps.put("capA", 1.0);
				exps.put("capB", 2.0);

				Map<String, Map<String, Double>> sensByService = new ConcurrentHashMap<>();
				sensByService.put("S1", exps);

				ConcurrentHashMap<String, Double> levels = new ConcurrentHashMap<>();
				levels.put("S1", 10.0);

				Aft a = mock(Aft.class);
				when(a.isInteract()).thenReturn(true);
				when(a.getSensByService()).thenReturn(sensByService);
				when(a.getProductivityLevel()).thenReturn(levels);

				double prod = c.productivity(a, "S1");
				double comp = c.competitiveness(a, "S1");

				// prod uses only capA (Suitability): 0.5^1 * 10 = 5.0
				// comp uses both: 0.5^1 * 0.8^2 * 10 = 3.2
				assertEquals(5.0, prod, 1e-12);
				assertEquals(3.2, comp, 1e-12);
				assertTrue(comp <= prod);
			} finally {
				setField(cfg, "separate_production_competitiveness", false);
				CapitalUpdater.getCapitalTypes().clear();
			}
		});
	}

	@Test
	void competitiveness_invertedByCapitalExponent_recoversProductivity() throws Throwable {
		withServices(List.of("S1"), () -> {
			Object cfg = ensureConfigInstance();
			setField(cfg, "separate_production_competitiveness", true);

			try {
				CapitalUpdater.getCapitalTypes().clear();
				CapitalUpdater.getCapitalTypes().put("capA", "Suitability");

				Cell c = new Cell(5, 6);

				Map<String, Double> caps = new ConcurrentHashMap<>();
				caps.put("capA", 4.0);
				c.getCapitals().putAll(caps);

				Map<String, Double> exps = new LinkedHashMap<>();
				exps.put("capA", 2.0);

				Map<String, Map<String, Double>> sensByService = new ConcurrentHashMap<>();
				sensByService.put("S1", exps);

				ConcurrentHashMap<String, Double> levels = new ConcurrentHashMap<>();
				levels.put("S1", 5.0);

				Aft a = mock(Aft.class);
				when(a.isInteract()).thenReturn(true);
				when(a.getSensByService()).thenReturn(sensByService);
				when(a.getProductivityLevel()).thenReturn(levels);

				double prod = c.productivity(a, "S1");
				double comp = c.competitiveness(a, "S1");

				// capA is Suitability, so both methods include it — prod == comp
				assertEquals(prod, comp, 1e-9);
			} finally {
				setField(cfg, "separate_production_competitiveness", false);
				CapitalUpdater.getCapitalTypes().clear();
			}
		});
	}

	@Test
	void productivity_includesSuitabilities_excludesCapitals() throws Throwable {
		withServices(List.of("S1"), () -> {
			Object cfg = ensureConfigInstance();
			setField(cfg, "separate_production_competitiveness", true);

			try {
				CapitalUpdater.getCapitalTypes().clear();
				CapitalUpdater.getCapitalTypes().put("yield_potential", "Suitability");
				CapitalUpdater.getCapitalTypes().put("infrastructure", "Capital");

				Cell c = new Cell(7, 8);

				Map<String, Double> caps = new ConcurrentHashMap<>();
				caps.put("yield_potential", 0.6);
				caps.put("infrastructure", 0.9);
				c.getCapitals().putAll(caps);

				Map<String, Double> exps = new LinkedHashMap<>();
				exps.put("yield_potential", 1.0);
				exps.put("infrastructure", 0.5);

				Map<String, Map<String, Double>> sensByService = new ConcurrentHashMap<>();
				sensByService.put("S1", exps);

				ConcurrentHashMap<String, Double> levels = new ConcurrentHashMap<>();
				levels.put("S1", 10.0);

				Aft a = mock(Aft.class);
				when(a.isInteract()).thenReturn(true);
				when(a.getSensByService()).thenReturn(sensByService);
				when(a.getProductivityLevel()).thenReturn(levels);

				double prod = c.productivity(a, "S1");
				double comp = c.competitiveness(a, "S1");

				// prod: only yield_potential (Suitability): 0.6^1 * 10 = 6.0
				assertEquals(6.0, prod, 1e-12);

				// comp: both capitals: 0.6^1 * 0.9^0.5 * 10
				assertEquals(0.6 * Math.pow(0.9, 0.5) * 10.0, comp, 1e-9);

				assertTrue(comp < prod,
						"Competitiveness should be less than production when a true Capital < 1");
			} finally {
				setField(cfg, "separate_production_competitiveness", false);
				CapitalUpdater.getCapitalTypes().clear();
			}
		});
	}

	// -----------------------------
	// calculateCurrentProductivity(...)
	// -----------------------------

	@Test
	void calculateCurrentProductivity_fillsCurrentProd_usingServiceSetList() throws Throwable {
		withServices(List.of("S1", "S2"), () -> {
			Cell c = new Cell(0, 0);

			Map<String, Double> caps = new ConcurrentHashMap<>();
			caps.put("capA", 2.0);
			c.getCapitals().putAll(caps);

			// S1: capA^1 * level=1 => 2
			// S2: capA^2 * level=5 => 4*5=20
			Map<String, Map<String, Double>> sensByService = new ConcurrentHashMap<>();
			sensByService.put("S1", Map.of("capA", 1.0));
			sensByService.put("S2", Map.of("capA", 2.0));

			ConcurrentHashMap<String, Double> levels = new ConcurrentHashMap<>();
			levels.put("S1", 1.0);
			levels.put("S2", 5.0);

			Aft owner = mock(Aft.class);
			when(owner.isInteract()).thenReturn(true);
			when(owner.getSensByService()).thenReturn(sensByService);
			when(owner.getProductivityLevel()).thenReturn(levels);

			setOwner(c, owner);

			c.calculateCurrentProductivity();

			assertArrayEquals(new double[] { 2.0, 20.0 }, c.getCurrentProd(), 1e-12);
		});
	}

	@Test
	void calculateCurrentProductivity_usesProvidedServicesArray() throws Throwable {
		// ServiceSet list is only used for the loop length in this overload
		withServices(List.of("X", "Y"), () -> {
			Cell c = new Cell(0, 0);

			Map<String, Double> caps = new ConcurrentHashMap<>();
			caps.put("capA", 3.0);
			c.getCapitals().putAll(caps);

			Map<String, Map<String, Double>> sensByService = new ConcurrentHashMap<>();
			sensByService.put("S1", Map.of("capA", 1.0)); // => 3
			sensByService.put("S2", Map.of("capA", 2.0)); // => 9

			ConcurrentHashMap<String, Double> levels = new ConcurrentHashMap<>();
			levels.put("S1", 2.0); // => 6
			levels.put("S2", 10.0); // => 90

			Aft owner = mock(Aft.class);
			when(owner.isInteract()).thenReturn(true);
			when(owner.getSensByService()).thenReturn(sensByService);
			when(owner.getProductivityLevel()).thenReturn(levels);

			setOwner(c, owner);

			c.calculateCurrentProductivity(new String[] { "S1", "S2" });

			assertArrayEquals(new double[] { 6.0, 90.0 }, c.getCurrentProd(), 1e-12);
		});
	}

	@Test
	void calculateCurrentProductivity_throwsIfServicesArrayTooShort() throws Throwable {
		withServices(List.of("A", "B"), () -> {
			Cell c = new Cell(0, 0);
			assertThrows(ArrayIndexOutOfBoundsException.class,
					() -> c.calculateCurrentProductivity(new String[] { "onlyOne" }));
		});
	}

	// -----------------------------
	// area and currentSupply(...)
	// -----------------------------

	@Test
	void area_defaultsToOne() {
		assertEquals(1.0, new Cell(0, 0).getArea(), 0.0);
	}

	@Test
	void currentSupply_isProductionTimesArea_onlyWhenCellAreaIsOn() throws Throwable {
		withServices(List.of("S1", "S2"), () -> {
			Config cfg = (Config) ensureConfigInstance();
			boolean before = cfg.use_cell_area;
			try {
				Cell c = new Cell(0, 0);
				c.getCurrentProd()[0] = 2.0; // t/ha
				c.getCurrentProd()[1] = 0.5;
				c.setArea(2500.0); // ha

				cfg.use_cell_area = true;
				assertEquals(5000.0, c.currentSupply(0), 1e-9);
				assertEquals(1250.0, c.currentSupply(1), 1e-9);

				// Off: the area is ignored and supply is production, exactly as before cell areas existed.
				cfg.use_cell_area = false;
				assertEquals(2.0, c.currentSupply(0), 0.0);
				assertEquals(0.5, c.currentSupply(1), 0.0);
			} finally {
				cfg.use_cell_area = before;
			}
		});
	}

	// -----------------------------
	// self-consumption (use_self_consumption)
	// -----------------------------

	@Test
	void currentSupply_takesTheOwnersShareOffMarketGoodsOnly() throws Throwable {
		withMarketGoods(true, () -> {
			Cell c = producingCell(producer(0.5));
			assertArrayEquals(new double[] { 2.0, 2.0 }, c.getCurrentProd(), 1e-12, "production counts all of it");
			assertEquals(1.0, c.currentSupply(0), 1e-12, "S1 is a market good: half of it reaches supply");
			assertEquals(2.0, c.currentSupply(1), 1e-12, "S2 is not: all of it does");

			ConfigLoader.config.use_cell_area = true;
			c.setArea(2500.0);
			assertEquals(2500.0, c.currentSupply(0), 1e-9);
			assertEquals(5000.0, c.currentSupply(1), 1e-9);
		});
	}

	@Test
	void currentSupply_isProductionExactly_whenSelfConsumptionIsOff() throws Throwable {
		withMarketGoods(false, () -> {
			Cell c = producingCell(producer(0.5));
			assertEquals(0.0, c.getCurrentSelfConsumption(), 0.0);
			assertEquals(2.0, c.currentSupply(0), 0.0);
			assertEquals(2.0, c.currentSupply(1), 0.0);
		});
	}

	@Test
	void currentSupply_isZeroForAMarketGood_whenTheOwnerUsesAllOfIt() throws Throwable {
		withMarketGoods(true, () -> {
			Cell c = producingCell(producer(1.0));
			assertEquals(0.0, c.currentSupply(0), 0.0);
			assertEquals(2.0, c.currentSupply(1), 0.0);
			assertArrayEquals(new double[] { 2.0, 2.0 }, c.getCurrentProd(), 1e-12, "production is unchanged");
		});
	}

	@Test
	void currentSupply_suppliesAServiceWithNoMarketGoodFlag_inFull() throws Throwable {
		withMarketGoods(true, () -> {
			ServiceSet.getMarketGood().remove("S1");
			Cell c = producingCell(producer(0.5));
			assertEquals(2.0, c.currentSupply(0), 0.0);
		});
	}

	@Test
	void currentSupply_usesTheShareRecordedWithTheProduction_notTheCurrentOwners() throws Throwable {
		withMarketGoods(true, () -> {
			Aft selfConsumer = producer(0.5);
			Aft seller = producer(0.0);
			Cell c = producingCell(selfConsumer);

			// Competition sets the new owner before production is recalculated.
			setOwner(c, seller);
			assertEquals(1.0, c.currentSupply(0), 1e-12, "still the self-consumer's production, so its share");

			c.calculateCurrentProductivity();
			assertEquals(2.0, c.currentSupply(0), 1e-12, "now the seller's");
		});
	}

	@Test
	void selfConsumption_isZero_forNoOwnerOrAManagerThatDoesNotProduce() throws Throwable {
		withMarketGoods(true, () -> {
			assertEquals(0.0, Cell.selfConsumption(null), 0.0);
			Aft mask = producer(0.5);
			when(mask.isInteract()).thenReturn(false);
			assertEquals(0.0, Cell.selfConsumption(mask), 0.0);
			assertEquals(0.5, Cell.selfConsumption(producer(0.5)), 0.0);

			Cell c = producingCell(producer(0.5));
			setOwner(c, null);
			c.calculateCurrentProductivity();
			assertEquals(0.0, c.getCurrentSelfConsumption(), 0.0);
		});
	}

	@Test
	void calculateCurrentProductivity_withAServicesArray_recordsTheShareToo() throws Throwable {
		withMarketGoods(true, () -> {
			Cell c = new Cell(0, 0);
			c.getCapitals().put("capA", 2.0);
			setOwner(c, producer(0.5));
			c.calculateCurrentProductivity(new String[] { "S1", "S2" });
			assertEquals(0.5, c.getCurrentSelfConsumption(), 0.0);
			assertEquals(1.0, c.currentSupply(0), 1e-12);
		});
	}

	// -----------------------------
	// transport costs (use_production_costs)
	// -----------------------------

	@Test
	void transportCost_isProductionTimesTradeTimesUnitCost_onMarketGoodsOnly() throws Throwable {
		withTransport(false, () -> {
			Cell c = producingCell(trader(0.5, 0.0));
			// S1: production 2 x trade 0.5 x 3 per unit; S2 is not a market good, so it has no unit cost.
			assertEquals(3.0, c.transportCost(c.getOwner(), null), 1e-12);
			assertEquals(3.0, c.productionCost(c.getOwner(), null), 1e-12, "global mode, no other costs");
		});
	}

	@Test
	void transportCost_isZero_forAnAftThatDoesNotTrade() throws Throwable {
		withTransport(false, () -> {
			Cell c = producingCell(trader(0.0, 0.0));
			assertEquals(0.0, c.transportCost(c.getOwner(), null), 0.0);
		});
	}

	@Test
	void transportCost_skipsTheSelfConsumedShare() throws Throwable {
		withTransport(true, () -> {
			Cell c = producingCell(trader(0.5, 0.5));
			assertEquals(1.5, c.transportCost(c.getOwner(), null), 1e-12);
		});
	}

	@Test
	void transportCost_isChargedOnProduction_notCompetitiveness() throws Throwable {
		withTransport(false, () -> withTradeCapital(() -> {
			Cell c = producingCell(tradeCapitalProducer());
			c.getCapitals().put("s1_trade", 0.5);
			// Production counts capA only (2); competitiveness also has s1_trade (2 x 0.5 = 1).
			assertEquals(2.0, c.productivity(c.getOwner(), "S1"), 1e-12);
			assertEquals(1.0, c.competitiveness(c.getOwner(), "S1"), 1e-12);
			// 2 x trade 0.5 x 3, not 1 x 0.5 x 3.
			assertEquals(3.0, c.transportCost(c.getOwner(), null), 1e-12);
		}));
	}

	@Test
	void transportCost_isNotCharged_whenProductionCostsAreOff() throws Throwable {
		withTransport(false, () -> {
			Cell c = producingCell(trader(0.5, 0.0));
			ConfigLoader.config.use_production_costs = false;
			assertEquals(0.0, c.productionCost(c.getOwner(), null), 0.0);
		});
	}

	// transport with spatial production costs: charged on the region's export share

	@Test
	void transportCost_bySurplus_isProductionTimesExportShareTimesUnitCost() throws Throwable {
		withSpatialTransport(false, () -> {
			// S2 has an export share but no unit cost, since it is not a market good.
			RegionalModelRunner r = regionWithExportShares(0.25, 0.9);
			Cell c = producingCell(trader(0.0, 0.0));
			// S1: production 2 x share 0.25 x 3 per unit, though the AFT has no trade factor.
			assertEquals(1.5, c.transportCost(c.getOwner(), r), 1e-12);
			assertEquals(1.5, c.productionCost(c.getOwner(), r), 1e-12, "spatial mode, no other costs");
		});
	}

	@Test
	void transportCost_bySurplus_ignoresTheTradeFactor() throws Throwable {
		withSpatialTransport(false, () -> {
			RegionalModelRunner r = regionWithExportShares(0.25, 0.0);
			for (double trade : new double[] { 0.0, 0.5, 2.0 }) {
				Cell c = producingCell(trader(trade, 0.0));
				assertEquals(1.5, c.transportCost(c.getOwner(), r), 1e-12, "trade factor " + trade);
			}
		});
	}

	@Test
	void transportCost_bySurplus_isZero_withoutASurplus() throws Throwable {
		withSpatialTransport(false, () -> {
			Cell c = producingCell(trader(0.5, 0.0));
			assertEquals(0.0, c.transportCost(c.getOwner(), regionWithExportShares(0.0, 0.0)), 0.0);
			assertEquals(0.0, c.transportCost(c.getOwner(), regionWithExportShares()), 0.0, "no shares yet");
			assertEquals(0.0, c.transportCost(c.getOwner(), null), 0.0, "no region");
		});
	}

	@Test
	void transportCost_bySurplus_skipsTheSelfConsumedShare() throws Throwable {
		withSpatialTransport(true, () -> {
			Cell c = producingCell(trader(0.0, 0.5));
			assertEquals(0.75, c.transportCost(c.getOwner(), regionWithExportShares(0.25, 0.0)), 1e-12);
		});
	}

	@Test
	void transportCost_bySurplus_chargesAChallengerOnItsOwnProduction() throws Throwable {
		withSpatialTransport(false, () -> {
			Cell c = producingCell(trader(0.0, 0.0));
			Aft challenger = trader(0.0, 0.0);
			when(challenger.getProductivityLevel()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 3.0, "S2", 1.0)));
			// The challenger would produce 2 x 3 = 6 of S1 here; the owner's stored production is 2.
			assertEquals(4.5, c.transportCost(challenger, regionWithExportShares(0.25, 0.0)), 1e-12);
			assertEquals(2.0, c.getCurrentProd()[0], 1e-12);
		});
	}

	@Test
	void transportCost_bySurplus_isChargedOnProduction_notCompetitiveness() throws Throwable {
		withSpatialTransport(false, () -> withTradeCapital(() -> {
			Cell c = producingCell(tradeCapitalProducer());
			c.getCapitals().put("s1_trade", 0.5);
			// Production 2 x share 0.5 x 3, not competitiveness 1 x 0.5 x 3.
			assertEquals(3.0, c.transportCost(c.getOwner(), regionWithExportShares(0.5, 0.0)), 1e-12);
		}));
	}

	@Test
	void transportCost_leavesProductionAndSupplyAlone() throws Throwable {
		withTransport(false, () -> {
			Cell c = producingCell(trader(0.5, 0.0));
			assertArrayEquals(new double[] { 2.0, 2.0 }, c.getCurrentProd(), 1e-12);
			assertEquals(2.0, c.currentSupply(0), 1e-12);
		});
	}

	@Test
	void transportCost_isZero_whenNoTransportCostsAreLoaded() throws Throwable {
		withServices(List.of("S1", "S2"), () -> {
			ProductionCostUpdater.setTransportCosts(new double[0]);
			Cell c = producingCell(trader(0.5, 0.0));
			assertEquals(0.0, c.transportCost(c.getOwner(), regionWithExportShares(0.25, 0.0)), 0.0);
		});
	}

	// -----------------------------
	// giveUp(...)
	// -----------------------------

	@Test
	void giveUp_doesNothing_whenOwnerNull() throws Throwable {
		withServices(List.of("S1"), () -> {
			Cell c = new Cell(0, 0);
			setOwner(c, null);

			Region region = mock(Region.class);
			Set<Cell> unmanaged = ConcurrentHashMap.newKeySet();
			when(region.getUnmanageCellsR()).thenReturn(unmanaged);

			RegionalModelRunner runner = mock(RegionalModelRunner.class);
			setField(runner, "R", region);

			c.giveUp(runner, new ConcurrentHashMap<>());

			assertTrue(unmanaged.isEmpty());
			assertEquals(0, Listener.landUseChangeCounter.get());
		});
	}

//	@Test
//	void giveUp_givesUpDeterministically_whenBelowThresholdAndProbabilityOne() throws Throwable {
//		withServices(List.of("S1"), () -> {
//			Cell c = new Cell(0, 0);
//
//			Aft owner = mock(Aft.class);
//			when(owner.isInteract()).thenReturn(true);
//			when(owner.getGiveUpMean()).thenReturn(1.0); // threshold factor
//			when(owner.getGiveUpSD()).thenReturn(0.0); // remove gaussian randomness
//			when(owner.getGiveUpProbabilty()).thenReturn(1.0); // Math.random always < 1 => true
//
//			setOwner(c, owner);
//
//			// utility < averageUtility * 1.0 => triggers
//			setCurrentUtility(c, 5.0);
//
//			ConcurrentHashMap<Aft, Double> mean = new ConcurrentHashMap<>();
//			mean.put(owner, 10.0);
//
//			Region region = mock(Region.class);
//			Set<Cell> unmanaged = ConcurrentHashMap.newKeySet();
//			when(region.getUnmanageCellsR()).thenReturn(unmanaged);
//
//			RegionalModelRunner runner = mock(RegionalModelRunner.class);
//			setField(runner, "R", region);
//			when(owner.getLabel()).thenReturn(""); // Math.random always < 1 => true
//			Tracker.sankeydata.put("Abandoned", new ConcurrentHashMap<>() );
//			Tracker.sankeydata.get("Abandoned").put(Timestep.getCurrentYear(),new ConcurrentHashMap<>() );
//			c.giveUp(runner, mean);
//
//			assertNull(getOwner(c), "Owner should be cleared after giveUp");
//			assertTrue(unmanaged.contains(c), "Cell should be added to unmanaged set");
//			assertEquals(1, Listener.landUseChangeCounter.get(), "Counter should increment");
//		});
//	}

	// =========================================================
	// Helpers
	// =========================================================

	private Object ensureConfigInstance() {
		try {
			Field cfgField = findField(ConfigLoader.class, "config");
			cfgField.setAccessible(true);
			Object cfg = cfgField.get(null);
			if (cfg == null) {
				cfg = cfgField.getType().getDeclaredConstructor().newInstance();
				cfgField.set(null, cfg);
			}
			return cfg;
		} catch (Exception e) {
			throw new RuntimeException("Failed to ensure config instance", e);
		}
	}

	/**
	 * Runs a test body with ServiceSet.getServicesList() returning the provided
	 * list.
	 *
	 * First tries to mutate the real list (if it's mutable). If not possible, falls
	 * back to Mockito static mocking (needs mockito-inline).
	 */
	private static void withServices(List<String> services, Executable body) throws Throwable {
		List<String> provided = new ArrayList<>(services);

		try {
			List<String> real = ServiceSet.getServicesList();
			List<String> backup = new ArrayList<>(real);
			try {
				real.clear();
				real.addAll(provided);
				body.execute();
			} finally {
				real.clear();
				real.addAll(backup);
			}
		} catch (UnsupportedOperationException ex) {
			try (MockedStatic<ServiceSet> mocked = Mockito.mockStatic(ServiceSet.class)) {
				mocked.when(ServiceSet::getServicesList).thenReturn(provided);
				body.execute();
			}
		}
	}

	/**
	 * Runs a self-consumption test body on services S1 (a market good) and S2 (not one), with
	 * use_self_consumption as given. The switch, cell area and the Market_good flags are restored after.
	 */
	private void withMarketGoods(boolean selfConsumptionOn, Executable body) throws Throwable {
		withServices(List.of("S1", "S2"), () -> {
			Config cfg = (Config) ensureConfigInstance();
			boolean switchBefore = cfg.use_self_consumption;
			boolean areaBefore = cfg.use_cell_area;
			Map<String, Boolean> flagsBefore = new HashMap<>(ServiceSet.getMarketGood());
			try {
				cfg.use_self_consumption = selfConsumptionOn;
				ServiceSet.getMarketGood().clear();
				ServiceSet.getMarketGood().put("S1", true);
				ServiceSet.getMarketGood().put("S2", false);
				body.execute();
			} finally {
				cfg.use_self_consumption = switchBefore;
				cfg.use_cell_area = areaBefore;
				ServiceSet.getMarketGood().clear();
				ServiceSet.getMarketGood().putAll(flagsBefore);
			}
		});
	}

	/**
	 * Runs a transport test body on S1 (a market good, transport 3 per unit) and S2 (not one), with
	 * production costs on in global mode. Everything set here is restored after.
	 */
	private void withTransport(boolean selfConsumptionOn, Executable body) throws Throwable {
		withMarketGoods(selfConsumptionOn, () -> {
			Config cfg = (Config) ensureConfigInstance();
			boolean costsBefore = cfg.use_production_costs;
			boolean spatialBefore = cfg.spatial_production_costs;
			try {
				cfg.use_production_costs = true;
				cfg.spatial_production_costs = false;
				ProductionCostUpdater.setTransportCosts(new double[] { 3.0, 0.0 });
				body.execute();
			} finally {
				cfg.use_production_costs = costsBefore;
				cfg.spatial_production_costs = spatialBefore;
				ProductionCostUpdater.setTransportCosts(new double[0]);
			}
		});
	}

	/**
	 * {@link #withTransport(boolean, Executable)} with spatial production costs and use_transport_costs on,
	 * so transport is charged on the region's export shares. Both switches are restored after.
	 */
	private void withSpatialTransport(boolean selfConsumptionOn, Executable body) throws Throwable {
		withTransport(selfConsumptionOn, () -> {
			Config cfg = (Config) ensureConfigInstance();
			boolean transportBefore = cfg.use_transport_costs;
			try {
				cfg.use_transport_costs = true;
				// withTransport restores this one.
				cfg.spatial_production_costs = true;
				body.execute();
			} finally {
				cfg.use_transport_costs = transportBefore;
			}
		});
	}

	/** A region runner whose export shares, indexed like the services, are the given ones. */
	private static RegionalModelRunner regionWithExportShares(double... shares) {
		RegionalModelRunner r = mock(RegionalModelRunner.class);
		when(r.getExportShare()).thenReturn(shares);
		return r;
	}

	/**
	 * Runs a body with separate_production_competitiveness on, capA a suitability and s1_trade a
	 * capital, so production counts capA only and competitiveness counts both. Restored after.
	 */
	private void withTradeCapital(Executable body) throws Throwable {
		Config cfg = (Config) ensureConfigInstance();
		boolean separateBefore = cfg.separate_production_competitiveness;
		Map<String, String> typesBefore = new HashMap<>(CapitalUpdater.getCapitalTypes());
		try {
			cfg.separate_production_competitiveness = true;
			CapitalUpdater.getCapitalTypes().clear();
			CapitalUpdater.getCapitalTypes().put("capA", "Suitability");
			CapitalUpdater.getCapitalTypes().put("s1_trade", "Capital");
			body.execute();
		} finally {
			cfg.separate_production_competitiveness = separateBefore;
			CapitalUpdater.getCapitalTypes().clear();
			CapitalUpdater.getCapitalTypes().putAll(typesBefore);
		}
	}

	/** {@link #trader(double, double)} (trade 0.5) whose S1 is also sensitive to the s1_trade capital. */
	private static Aft tradeCapitalProducer() {
		Aft a = trader(0.5, 0.0);
		when(a.getSensByService()).thenReturn(
				Map.of("S1", Map.of("capA", 1.0, "s1_trade", 1.0), "S2", Map.of("capA", 1.0)));
		return a;
	}

	/** {@link #producer(double)} with the given trade factor on S1 and none on S2. */
	private static Aft trader(double tradeOnS1, double selfConsumption) {
		Aft a = producer(selfConsumption);
		when(a.getLabel()).thenReturn("Trader");
		when(a.getTradeFactor(0)).thenReturn(tradeOnS1);
		return a;
	}

	/** An AFT that grows capA x 1 of both S1 and S2, and uses the given share of its market goods itself. */
	private static Aft producer(double selfConsumption) {
		Aft a = mock(Aft.class);
		when(a.isInteract()).thenReturn(true);
		when(a.getSensByService()).thenReturn(Map.of("S1", Map.of("capA", 1.0), "S2", Map.of("capA", 1.0)));
		when(a.getProductivityLevel()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 1.0, "S2", 1.0)));
		when(a.getSelfConsumption()).thenReturn(selfConsumption);
		return a;
	}

	/** A cell with capA = 2 owned by the given AFT, its production calculated: 2 of S1 and 2 of S2. */
	private static Cell producingCell(Aft owner) {
		Cell c = new Cell(0, 0);
		c.getCapitals().put("capA", 2.0);
		setOwner(c, owner);
		c.calculateCurrentProductivity();
		return c;
	}

	private static void setOwner(Cell c, Aft owner) {
		// prefer setter if present
		try {
			Method m = findMethod(c.getClass(), "setOwner", Aft.class);
			if (m != null) {
				m.setAccessible(true);
				m.invoke(c, owner);
				return;
			}
		} catch (Exception ignored) {
		}
		setField(c, "owner", owner);
	}

	private static Aft getOwner(Cell c) {
		try {
			Method m = findMethod(c.getClass(), "getOwner");
			if (m != null) {
				m.setAccessible(true);
				return (Aft) m.invoke(c);
			}
		} catch (Exception ignored) {
		}
		return (Aft) getField(c, "owner");
	}

	private static void setCurrentUtility(Cell c, double util) {
		// prefer setter if present
		try {
			Method m = findMethod(c.getClass(), "setCurrentUtility", double.class);
			if (m != null) {
				m.setAccessible(true);
				m.invoke(c, util);
				return;
			}
		} catch (Exception ignored) {
		}

		// fallback to field if present
		try {
			setField(c, "currentUtility", util);
		} catch (Exception e) {
			// If your AbstractCell uses a different name, update here.
			throw new AssertionError("Could not set current utility. Add a setter or adjust field name.", e);
		}
	}

	private static Method findMethod(Class<?> type, String name, Class<?>... params) {
		Class<?> c = type;
		while (c != null) {
			try {
				return c.getDeclaredMethod(name, params);
			} catch (NoSuchMethodException ignored) {
				c = c.getSuperclass();
			}
		}
		return null;
	}

	private static Object getField(Object target, String fieldName) {
		try {
			Field f = findField(target.getClass(), fieldName);
			f.setAccessible(true);
			return f.get(target);
		} catch (Exception e) {
			throw new RuntimeException("Failed to read field '" + fieldName + "'", e);
		}
	}

	private static void setField(Object target, String fieldName, Object value) {
		try {
			Field f = findField(target.getClass(), fieldName);
			f.setAccessible(true);
			f.set(target, value);
		} catch (Exception e) {
			throw new RuntimeException("Failed to set field '" + fieldName + "'", e);
		}
	}

	private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
		Class<?> c = type;
		while (c != null) {
			try {
				return c.getDeclaredField(fieldName);
			} catch (NoSuchFieldException ignored) {
				c = c.getSuperclass();
			}
		}
		throw new NoSuchFieldException(fieldName);
	}
}
