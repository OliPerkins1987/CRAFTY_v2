package de.cesr.crafty.core.crafty;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import de.cesr.crafty.core.ToyData;
import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.dataLoader.afts.AFTsLoader;
import de.cesr.crafty.core.dataLoader.land.CellsLoader;
import de.cesr.crafty.core.dataLoader.serivces.ServiceSet;
import de.cesr.crafty.core.output.ListenerByRegion;
import de.cesr.crafty.core.updaters.LandMaskUpdater;
import de.cesr.crafty.core.updaters.SeedUpdater;
import de.cesr.crafty.core.updaters.ServicesUpdater;
import de.cesr.crafty.core.updaters.Timestep;

class RegionalModelRunnerTest {

	private Object configBackup;
	private Object cellsLoaderRegionsBackup;
	private boolean cellsLoaderRegionalizationBackup;
	private Object hashAgentNbrRegionsBackup;
	@TempDir
	private Path tempDir;
	@BeforeEach
	void backupStatics() throws Exception {
		
		new ToyData().resetStaticState(tempDir);
		// ConfigLoader.config backup
		Field cfgField = findField(ConfigLoader.class, "config");
		cfgField.setAccessible(true);
		configBackup = cfgField.get(null);

		// CellsLoader.regions backup
		Field regionsField = findField(CellsLoader.class, "regions");
		regionsField.setAccessible(true);
		cellsLoaderRegionsBackup = regionsField.get(null);

		// CellsLoader.regionalisation backup
		Field regField = findField(CellsLoader.class, "regionalisation");
		regField.setAccessible(true);
		cellsLoaderRegionalizationBackup = (boolean) regField.get(null);

		// AFTsLoader.hashAgentNbrRegions backup (used by calculeDistributionMean)
		Field hField = findField(AFTsLoader.class, "hashAgentNbrRegions");
		hField.setAccessible(true);
		hashAgentNbrRegionsBackup = hField.get(null);
	}

	@AfterEach
	void restoreStatics() throws Exception {
		// reset price utility flags before restoring config reference
		if (ConfigLoader.config != null) {
			ConfigLoader.config.use_explicit_price_utility = false;
			ConfigLoader.config.use_price_only_utility = false;
			ConfigLoader.config.use_cell_area = false;
			ConfigLoader.config.use_price_feedback = false;
			ConfigLoader.config.price_feedback_strength = 0.03;
			ConfigLoader.config.use_production_costs = false;
		}

		// restore ConfigLoader.config
		Field cfgField = findField(ConfigLoader.class, "config");
		cfgField.setAccessible(true);
		cfgField.set(null, configBackup);

		// restore CellsLoader.regions
		Field regionsField = findField(CellsLoader.class, "regions");
		regionsField.setAccessible(true);
		regionsField.set(null, cellsLoaderRegionsBackup);

		// restore CellsLoader.regionalisation
		Field regField = findField(CellsLoader.class, "regionalisation");
		regField.setAccessible(true);
		regField.set(null, cellsLoaderRegionalizationBackup);

		// restore AFTsLoader.hashAgentNbrRegions
		Field hField = findField(AFTsLoader.class, "hashAgentNbrRegions");
		hField.setAccessible(true);
		hField.set(null, hashAgentNbrRegionsBackup);
	}
	
    @AfterEach
    void tearDown() {
    	CustomLogger.shutdownRunFileLoggers();
    }

	@Test
	void constructor_initialisesDistributionMeanForAllYears() throws Exception {
		try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class);
				MockedConstruction<ListenerByRegion> lb = Mockito.mockConstruction(ListenerByRegion.class)) {

			ts.when(Timestep::getStartYear).thenReturn(2000);
			ts.when(Timestep::getEndtYear).thenReturn(2002);

			Region region = mockRegion("R1", new ConcurrentHashMap<>(), 0);

			installRegion("R1", region);

			RegionalModelRunner r = new RegionalModelRunner("R1");

			assertNotNull(r.getDistributionMean().get(2000));
			assertNotNull(r.getDistributionMean().get(2001));
			assertNotNull(r.getDistributionMean().get(2002));
		}
	}

	@Test
	void regionalSupply_sumsCurrentProdAcrossCells_andCallsProductivityCalculation() throws Exception {
		try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class);
				MockedStatic<ServiceSet> ss = Mockito.mockStatic(ServiceSet.class);
				MockedConstruction<ListenerByRegion> lb = Mockito.mockConstruction(ListenerByRegion.class)) {

			ts.when(Timestep::getStartYear).thenReturn(2000);
			ts.when(Timestep::getEndtYear).thenReturn(2000);

			ss.when(ServiceSet::getServicesList).thenReturn(java.util.List.of("S1", "S2"));

			// force the executor path (doesn't matter much; both call
			// calculateCurrentProductivity)
			setStaticBoolean(CellsLoader.class, "regionalisation", false);

			// Cells
			Cell c1 = mock(Cell.class);
			double[] currentProd= {1,2};
			when(c1.getCurrentProd()).thenReturn(currentProd);
			when(c1.currentSupply(anyInt())).thenCallRealMethod();
			double[] currentProd2= {3,4};
			Cell c2 = mock(Cell.class);
			when(c2.getCurrentProd()).thenReturn(currentProd2);
			when(c2.currentSupply(anyInt())).thenCallRealMethod();

			ConcurrentHashMap<String, Cell> cells = new ConcurrentHashMap<>();
			cells.put("0,0", c1);
			cells.put("1,1", c2);

			Region region = new Region("R1");
			region.setCells(cells);
			CellsLoader.regions.put("R1",region);
			RegionalModelRunner r = new RegionalModelRunner("R1");
			r.regionalSupply();

			assertEquals(4.0, r.getRegionalSupply().get("S1"), 1e-12);
			assertEquals(6.0, r.getRegionalSupply().get("S2"), 1e-12);

			verify(c1, times(1)).calculateCurrentProductivity();
			verify(c2, times(1)).calculateCurrentProductivity();
		}
	}

	@Test
	void regionalSupply_weightsEachCellByItsArea_onlyWhenCellAreaIsOn() throws Exception {
		try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class);
				MockedStatic<ServiceSet> ss = Mockito.mockStatic(ServiceSet.class);
				MockedConstruction<ListenerByRegion> lb = Mockito.mockConstruction(ListenerByRegion.class)) {

			ts.when(Timestep::getStartYear).thenReturn(2000);
			ts.when(Timestep::getEndtYear).thenReturn(2000);
			ss.when(ServiceSet::getServicesList).thenReturn(java.util.List.of("S1", "S2"));

			Cell c1 = cellWith(new double[] { 1, 2 }, 10.0);
			Cell c2 = cellWith(new double[] { 3, 4 }, 0.5);
			installRegion("R1", regionOf(c1, c2));
			RegionalModelRunner r = new RegionalModelRunner("R1");

			// Off: areas are ignored, every cell counts as 1.
			ConfigLoader.config.use_cell_area = false;
			r.regionalSupply();
			assertEquals(4.0, r.getRegionalSupply().get("S1"), 1e-12);
			assertEquals(6.0, r.getRegionalSupply().get("S2"), 1e-12);

			// On: 1 x 10 + 3 x 0.5 and 2 x 10 + 4 x 0.5.
			ConfigLoader.config.use_cell_area = true;
			r.regionalSupply();
			assertEquals(11.5, r.getRegionalSupply().get("S1"), 1e-12);
			assertEquals(22.0, r.getRegionalSupply().get("S2"), 1e-12);
		}
	}

	@Test
	void supplyUpdatedAfterACompetitionBatch_matchesAFullRecount_withUnequalAreas() throws Exception {
		try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class);
				MockedStatic<ServiceSet> ss = Mockito.mockStatic(ServiceSet.class);
				MockedConstruction<ListenerByRegion> lb = Mockito.mockConstruction(ListenerByRegion.class)) {

			ts.when(Timestep::getStartYear).thenReturn(2000);
			ts.when(Timestep::getEndtYear).thenReturn(2000);
			ss.when(ServiceSet::getServicesList).thenReturn(java.util.List.of("S1", "S2"));
			ConfigLoader.config.use_cell_area = true;

			double[] p1 = { 1, 2 };
			Cell c1 = cellWith(p1, 10.0);
			// A new owner on c1: its production changes when it is recalculated.
			doAnswer(inv -> {
				p1[0] = 5;
				p1[1] = 0;
				return null;
			}).when(c1).calculateCurrentProductivity();
			Cell c2 = cellWith(new double[] { 3, 4 }, 0.5);
			installRegion("R1", regionOf(c1, c2));
			RegionalModelRunner r = new RegionalModelRunner("R1");

			invokePrivate(r, "computeRegionsSupply");
			assertEquals(11.5, r.getRegionalSupply().get("S1"), 1e-12);
			assertEquals(22.0, r.getRegionalSupply().get("S2"), 1e-12);

			// The batch update adds only c1's change: (5 - 1) x 10 and (0 - 2) x 10.
			Method batch = RegionalModelRunner.class.getDeclaredMethod("applyProductivityChangeToRegionalSupply",
					java.util.List.class);
			batch.setAccessible(true);
			batch.invoke(r, java.util.List.of(c1));
			double s1AfterBatch = r.getRegionalSupply().get("S1");
			double s2AfterBatch = r.getRegionalSupply().get("S2");
			assertEquals(51.5, s1AfterBatch, 1e-12);
			assertEquals(2.0, s2AfterBatch, 1e-12);

			// A full recount from the cells gives the same totals.
			invokePrivate(r, "computeRegionsSupply");
			assertEquals(r.getRegionalSupply().get("S1"), s1AfterBatch, 1e-12);
			assertEquals(r.getRegionalSupply().get("S2"), s2AfterBatch, 1e-12);
		}
	}

	@Test
	void utilitytyForAll_usesOnlyMarginalUtilityWhenCellTaxesAreDisabled() throws Exception {
		try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class);
				MockedStatic<ServiceSet> ss = Mockito.mockStatic(ServiceSet.class);
				MockedConstruction<ListenerByRegion> lb = Mockito.mockConstruction(ListenerByRegion.class)) {

			ts.when(Timestep::getStartYear).thenReturn(2000);
			ts.when(Timestep::getEndtYear).thenReturn(2000);

			ss.when(ServiceSet::getServicesList).thenReturn(java.util.List.of("S1"));

			Cell c = spy(new Cell(0, 0));
			Cell cNull = new Cell(1, 1);

			Aft owner = mock(Aft.class);
			when(owner.isInteract()).thenReturn(true);

			setField(c, "owner", owner);
			setField(cNull, "owner", null);

			// competitiveness(owner,"S1") = 10
			doReturn(10.0).when(c).competitiveness(owner, "S1");

			ConcurrentHashMap<String, Cell> cells = new ConcurrentHashMap<>();
			cells.put("0,0", c);
			cells.put("1,1", cNull);

			Region region = mockRegion("R1", cells, cells.size());
			installRegion("R1", region);

			RegionalModelRunner r = new RegionalModelRunner("R1");
			r.getMarginal().put("S1", 0.5);

			invokePrivate(r, "utilitytyForAll");

			// u = marginal * productivity = 0.5 * 10 = 5
			assertEquals(5.0, c.getCurrentUtility(), 1e-12);
			assertEquals(0.0, cNull.getCurrentUtility(), 1e-12);
		}
	}

	@Test
	void calculeMaxMinUtility_setsMinAndMax() throws Exception {
		try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class);
				MockedStatic<ServiceSet> ss = Mockito.mockStatic(ServiceSet.class);
				MockedConstruction<ListenerByRegion> lb = Mockito.mockConstruction(ListenerByRegion.class)) {

			ts.when(Timestep::getStartYear).thenReturn(2000);
			ts.when(Timestep::getEndtYear).thenReturn(2000);

			ss.when(ServiceSet::getServicesList).thenReturn(java.util.List.of("S1"));

			Cell c1 = new Cell(0, 0);
			Cell c2 = new Cell(1, 1);
			c1.setCurrentUtility(3.0);
			c2.setCurrentUtility(-1.0);

			ConcurrentHashMap<String, Cell> cells = new ConcurrentHashMap<>();
			cells.put("0,0", c1);
			cells.put("1,1", c2);

			Region region = mockRegion("R1", cells, cells.size());
			installRegion("R1", region);

			RegionalModelRunner r = new RegionalModelRunner("R1");
			invokePrivate(r, "computeMaxMinUtility");

			assertEquals(-1.0, r.getMinUtility(), 1e-12);
			assertEquals(3.0, r.getMaxUtility(), 1e-12);
		}
	}

	@Test
	void calculeMarginal_computesExpectedMarginal_withAveraging() throws Exception {
		try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class);
				MockedStatic<ServiceSet> ss = Mockito.mockStatic(ServiceSet.class);
				MockedStatic<ServicesUpdater> su = Mockito.mockStatic(ServicesUpdater.class);
				MockedConstruction<ListenerByRegion> lb = Mockito.mockConstruction(ListenerByRegion.class)) {

			ts.when(Timestep::getStartYear).thenReturn(2000);
			ts.when(Timestep::getEndtYear).thenReturn(2000);

			ss.when(ServiceSet::getServicesList).thenReturn(java.util.List.of("S1"));
			ss.when(ServiceSet::getPenalise_Oversupply).thenReturn(new ConcurrentHashMap<>(Map.of("S1", false)));

			// demand & weight by region
			Map<String, Map<String, Double>> demandByRegions = Map.of("R1", Map.of("S1", 100.0));
			Map<String, Map<String, Double>> weightByRegions = Map.of("R1", Map.of("S1", 0.5));
			su.when(ServicesUpdater::getDemandByRegions).thenReturn(demandByRegions);
			su.when(ServicesUpdater::getWeightByRegions).thenReturn(weightByRegions);

			// config flag
			Object cfg = ensureConfigInstance();
			setField(cfg, "averaged_residual_demand_per_cell", true);

			ConcurrentHashMap<String, Cell> cells = new ConcurrentHashMap<>();
			// Only need size
			Region region = mockRegion("R1", cells, 10);
			installRegion("R1", region);

			RegionalModelRunner r = new RegionalModelRunner("R1");
			r.setRegionalSupply(new ConcurrentHashMap<>(Map.of("S1", 80.0))); // supply=80

			invokePrivate(r, "computeMarginal");

			// residual = 100-80=20
			// averaged per cell (10): 2
			// * weight 0.5 => 1
			// / demand 100 => 0.01
			assertEquals(0.01, r.getMarginal().get("S1"), 1e-12);
		}
	}

	@Test
	void calculeMarginal_averagesOverRegionArea_whenCellAreaIsOn() throws Exception {
		try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class);
				MockedStatic<ServiceSet> ss = Mockito.mockStatic(ServiceSet.class);
				MockedStatic<ServicesUpdater> su = Mockito.mockStatic(ServicesUpdater.class);
				MockedConstruction<ListenerByRegion> lb = Mockito.mockConstruction(ListenerByRegion.class)) {

			ts.when(Timestep::getStartYear).thenReturn(2000);
			ts.when(Timestep::getEndtYear).thenReturn(2000);

			ss.when(ServiceSet::getServicesList).thenReturn(java.util.List.of("S1"));
			ss.when(ServiceSet::getPenalise_Oversupply).thenReturn(new ConcurrentHashMap<>(Map.of("S1", false)));

			su.when(ServicesUpdater::getDemandByRegions).thenReturn(Map.of("R1", Map.of("S1", 100.0)));
			su.when(ServicesUpdater::getWeightByRegions).thenReturn(Map.of("R1", Map.of("S1", 0.5)));

			ConfigLoader.config.averaged_residual_demand_per_cell = true;

			// Two cells, 5 units of land between them.
			Cell c1 = new Cell(0, 0);
			Cell c2 = new Cell(1, 0);
			c1.setArea(2.0);
			c2.setArea(3.0);
			installRegion("R1", regionOf(c1, c2));

			RegionalModelRunner r = new RegionalModelRunner("R1");
			r.setRegionalSupply(new ConcurrentHashMap<>(Map.of("S1", 80.0)));

			// Off: per cell. 20 / 2 cells x 0.5 / 100 = 0.05
			ConfigLoader.config.use_cell_area = false;
			invokePrivate(r, "computeMarginal");
			assertEquals(0.05, r.getMarginal().get("S1"), 1e-12);

			// On: per unit area. 20 / 5 x 0.5 / 100 = 0.02
			ConfigLoader.config.use_cell_area = true;
			invokePrivate(r, "computeMarginal");
			assertEquals(0.02, r.getMarginal().get("S1"), 1e-12);
		} finally {
			ConfigLoader.config.averaged_residual_demand_per_cell = false;
		}
	}

	@Test
	void calculeDistributionMean_sumsUtilityDividedByAgentCount_andAddsZerosForMissingAfts() throws Exception {
		try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class);
				MockedStatic<ServiceSet> ss = Mockito.mockStatic(ServiceSet.class);
				MockedStatic<AFTsLoader> al = Mockito.mockStatic(AFTsLoader.class);
				MockedConstruction<ListenerByRegion> lb = Mockito.mockConstruction(ListenerByRegion.class)) {

			ts.when(Timestep::getStartYear).thenReturn(2000);
			ts.when(Timestep::getEndtYear).thenReturn(2001);
			ts.when(Timestep::getCurrentYear).thenReturn(2001);

			ss.when(ServiceSet::getServicesList).thenReturn(java.util.List.of("S1"));

			// owner AFT
			Aft owner = mock(Aft.class);
			when(owner.getLabel()).thenReturn("A1");

			// other active AFT (to check computeIfAbsent -> 0)
			Aft other = mock(Aft.class);
			when(other.getLabel()).thenReturn("A2");

			// region cells
			Cell c1 = new Cell(0, 0);
			Cell c2 = new Cell(1, 1);
			setField(c1, "owner", owner);
			setField(c2, "owner", owner);
			c1.setCurrentUtility(10.0);
			c2.setCurrentUtility(6.0);

			ConcurrentHashMap<String, Cell> cells = new ConcurrentHashMap<>();
			cells.put("0,0", c1);
			cells.put("1,1", c2);

			Region region = mockRegion("R1", cells, cells.size());
			installRegion("R1", region);

			// hashAgentNbrRegions: for region R1, label A1 -> 2 (so each utility divided by
			// 2)
			ConcurrentHashMap<String, ConcurrentHashMap<String, Integer>> hashNbr = new ConcurrentHashMap<>();
			hashNbr.put("R1", new ConcurrentHashMap<>(Map.of("A1", 2)));
			setStaticField(AFTsLoader.class, "hashAgentNbrRegions", hashNbr);

			// active AFTs
			ConcurrentHashMap<String, Aft> active = new ConcurrentHashMap<>();
			active.put("A1", owner);
			active.put("A2", other);
			al.when(AFTsLoader::getActivateAFTsHash).thenReturn(active);

			RegionalModelRunner r = new RegionalModelRunner("R1");

			invokePrivate(r, "computeDistributionMean");

			double meanOwner = r.getDistributionMean().get(2001).get(owner.getLabel());
			double meanOther = r.getDistributionMean().get(2001).get(other.getLabel());

			// 10/2 + 6/2 = 8
			assertEquals(8.0, meanOwner, 1e-12);
			assertEquals(0.0, meanOther, 1e-12);
		}
	}

	// =========================================================
	// Price feedback
	// =========================================================

	@Test
	void priceMultiplier_followsTheWorkedExample() {
		// Plan 39a §2: k = 1, demand 100.
		assertEquals(1.1, RegionalModelRunner.priceMultiplier(100, 90, 1.0, true), 1e-12);
		assertEquals(1.0, RegionalModelRunner.priceMultiplier(100, 100, 1.0, true), 1e-12);
		assertEquals(0.8, RegionalModelRunner.priceMultiplier(100, 120, 1.0, true), 1e-12);
		assertEquals(0.0, RegionalModelRunner.priceMultiplier(100, 200, 1.0, true), 1e-12);
		assertEquals(2.0, RegionalModelRunner.priceMultiplier(100, 0, 1.0, true), 1e-12);
	}

	@Test
	void priceMultiplier_neverGoesBelowZero() {
		// 3 x demand: 1 + 1 x (-2) = -1 without the floor.
		assertEquals(0.0, RegionalModelRunner.priceMultiplier(100, 300, 1.0, true), 1e-12);
	}

	@Test
	void priceMultiplier_scalesTheGapByTheStrength() {
		// 20 % oversupply: 1 - 0.5 x 0.2 and 1 - 2 x 0.2.
		assertEquals(0.9, RegionalModelRunner.priceMultiplier(100, 120, 0.5, true), 1e-12);
		assertEquals(0.6, RegionalModelRunner.priceMultiplier(100, 120, 2.0, true), 1e-12);
	}

	@Test
	void priceMultiplier_staysBetweenZeroAndOnePlusStrength() {
		for (double strength : new double[] { 0.5, 1.0, 3.0 }) {
			for (double supply = 0; supply <= 500; supply += 5) {
				double m = RegionalModelRunner.priceMultiplier(100, supply, strength, true);
				assertTrue(m >= 0 && m <= 1 + strength, "k=" + strength + ", supply=" + supply + " gave " + m);
			}
		}
	}

	@Test
	void priceMultiplier_withoutOversupplyPenalty_neverFallsBelowTheFilePrice() {
		assertEquals(1.0, RegionalModelRunner.priceMultiplier(100, 200, 1.0, false), 1e-12);
		assertEquals(1.0, RegionalModelRunner.priceMultiplier(100, 120, 1.0, false), 1e-12);
		// A shortage is the same either way.
		assertEquals(1.1, RegionalModelRunner.priceMultiplier(100, 90, 1.0, false), 1e-12);
	}

	@Test
	void priceMultiplier_isOne_withNoDemandOrNoStrength() {
		assertEquals(1.0, RegionalModelRunner.priceMultiplier(0, 50, 1.0, true), 1e-12);
		assertEquals(1.0, RegionalModelRunner.priceMultiplier(100, 300, 0.0, true), 1e-12);
		assertEquals(1.0, RegionalModelRunner.priceMultiplier(100, 0, 0.0, true), 1e-12);
	}

	/** Runs body with one region R1: S1 demand 100 (penalised) and S2 demand 50 (not penalised). */
	private void withPriceFeedbackRegion(ThrowingConsumer<RegionalModelRunner> body) throws Exception {
		try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class);
				MockedStatic<ServiceSet> ss = Mockito.mockStatic(ServiceSet.class);
				MockedStatic<ServicesUpdater> su = Mockito.mockStatic(ServicesUpdater.class);
				MockedConstruction<ListenerByRegion> lb = Mockito.mockConstruction(ListenerByRegion.class)) {

			ts.when(Timestep::getStartYear).thenReturn(2000);
			ts.when(Timestep::getEndtYear).thenReturn(2000);
			ts.when(Timestep::getCurrentYear).thenReturn(2000);
			ss.when(ServiceSet::getServicesList).thenReturn(java.util.List.of("S1", "S2"));
			ss.when(ServiceSet::getPenalise_Oversupply)
					.thenReturn(new ConcurrentHashMap<>(Map.of("S1", true, "S2", false)));
			su.when(ServicesUpdater::getDemandByRegions).thenReturn(Map.of("R1", Map.of("S1", 100.0, "S2", 50.0)));
			su.when(ServicesUpdater::getWeightByRegions).thenReturn(Map.of("R1", Map.of("S1", 1.0, "S2", 1.0)));

			installRegion("R1", mockRegion("R1", new ConcurrentHashMap<>(), 1));
			body.accept(new RegionalModelRunner("R1"));
		}
	}

	@FunctionalInterface
	private interface ThrowingConsumer<T> {
		void accept(T t) throws Exception;
	}

	@Test
	void computeMarginal_fillsThePriceMultiplier_andFollowsSupplyEachTime() throws Exception {
		// At the default strength, 0.03.
		ConfigLoader.config.use_price_feedback = true;
		withPriceFeedbackRegion(r -> {
			r.setRegionalSupply(new ConcurrentHashMap<>(Map.of("S1", 120.0, "S2", 80.0)));
			invokePrivate(r, "computeMarginal");
			assertEquals(0.994, r.getPriceMultiplier().get("S1"), 1e-12); // 1 - 0.03 x 0.2
			assertEquals(1.0, r.getPriceMultiplier().get("S2"), 1e-12, "S2 does not penalise oversupply");

			// The next refresh (as after a competition batch) sees the new supply.
			r.setRegionalSupply(new ConcurrentHashMap<>(Map.of("S1", 90.0, "S2", 40.0)));
			invokePrivate(r, "computeMarginal");
			assertEquals(1.003, r.getPriceMultiplier().get("S1"), 1e-12); // 1 + 0.03 x 0.1
			assertEquals(1.006, r.getPriceMultiplier().get("S2"), 1e-12); // 1 + 0.03 x 0.2
		});
	}

	@Test
	void computeMarginal_usesTheConfiguredStrength() throws Exception {
		ConfigLoader.config.use_price_feedback = true;
		ConfigLoader.config.price_feedback_strength = 2.0;
		withPriceFeedbackRegion(r -> {
			r.setRegionalSupply(new ConcurrentHashMap<>(Map.of("S1", 120.0, "S2", 50.0)));
			invokePrivate(r, "computeMarginal");
			assertEquals(0.6, r.getPriceMultiplier().get("S1"), 1e-12);
		});
	}

	@Test
	void computeMarginal_leavesThePriceMultiplierEmpty_whenTheSwitchIsOff() throws Exception {
		withPriceFeedbackRegion(r -> {
			r.setRegionalSupply(new ConcurrentHashMap<>(Map.of("S1", 120.0, "S2", 80.0)));
			invokePrivate(r, "computeMarginal");
			assertTrue(r.getPriceMultiplier().isEmpty());
		});
	}

	/**
	 * Runs one step() on a region with no cells (S1: demand 100, supply 120), with use_price_feedback as
	 * given, then hands the runner and its listener to check.
	 */
	private void stepAnEmptyRegion(boolean priceFeedbackOn, ThrowingBiConsumer<RegionalModelRunner, ListenerByRegion> check)
			throws Exception {
		try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class);
				MockedStatic<ServiceSet> ss = Mockito.mockStatic(ServiceSet.class);
				MockedStatic<ServicesUpdater> su = Mockito.mockStatic(ServicesUpdater.class);
				MockedStatic<LandMaskUpdater> lm = Mockito.mockStatic(LandMaskUpdater.class);
				MockedStatic<SeedUpdater> sd = Mockito.mockStatic(SeedUpdater.class);
				MockedStatic<AFTsLoader> al = Mockito.mockStatic(AFTsLoader.class);
				MockedConstruction<ListenerByRegion> lb = Mockito.mockConstruction(ListenerByRegion.class)) {

			ts.when(Timestep::getStartYear).thenReturn(2000);
			ts.when(Timestep::getEndtYear).thenReturn(2000);
			ts.when(Timestep::getCurrentYear).thenReturn(2000);
			ss.when(ServiceSet::getServicesList).thenReturn(java.util.List.of("S1"));
			ss.when(ServiceSet::getPenalise_Oversupply).thenReturn(new ConcurrentHashMap<>(Map.of("S1", true)));
			su.when(ServicesUpdater::getDemandByRegions).thenReturn(Map.of("R1", Map.of("S1", 100.0)));
			su.when(ServicesUpdater::getWeightByRegions).thenReturn(Map.of("R1", Map.of("S1", 1.0)));
			sd.when(() -> SeedUpdater.selectSeed(any(), any(), anyDouble(), anyBoolean(), anyInt()))
					.thenReturn(new java.util.ArrayList<>());
			al.when(AFTsLoader::getActivateAFTsHash).thenReturn(new ConcurrentHashMap<>());

			ConfigLoader.config.use_explicit_price_utility = true;
			ConfigLoader.config.use_price_feedback = priceFeedbackOn;
			installRegion("R1", regionOf());
			RegionalModelRunner r = new RegionalModelRunner("R1");
			r.setRegionalSupply(new ConcurrentHashMap<>(Map.of("S1", 120.0)));

			r.step();

			check.accept(r, lb.constructed().get(0));
		}
	}

	@FunctionalInterface
	private interface ThrowingBiConsumer<T, U> {
		void accept(T t, U u) throws Exception;
	}

	@Test
	void step_writesTheYearsPrices_whenPriceFeedbackIsOn() throws Exception {
		stepAnEmptyRegion(true, (r, listener) -> {
			assertEquals(0.994, r.getPriceMultiplier().get("S1"), 1e-12); // 20 % oversupply at strength 0.03
			verify(listener).exportPrices(same(r.getPriceMultiplier()));
		});
	}

	@Test
	void step_writesNoPrices_whenPriceFeedbackIsOff() throws Exception {
		stepAnEmptyRegion(false, (r, listener) -> verify(listener, never()).exportPrices(any()));
	}

	@Test
	void priceFeedback_reachesGivingUp_throughTheCurrentUtility() throws Exception {
		// Plan 39a §2.4: an intensive AFT, 8 t/ha at 900 $/ha, at a file price of 200 $/t.
		try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class);
				MockedStatic<ServiceSet> ss = Mockito.mockStatic(ServiceSet.class);
				MockedStatic<ServicesUpdater> su = Mockito.mockStatic(ServicesUpdater.class);
				MockedConstruction<ListenerByRegion> lb = Mockito.mockConstruction(ListenerByRegion.class)) {

			ts.when(Timestep::getStartYear).thenReturn(2020);
			ts.when(Timestep::getEndtYear).thenReturn(2020);
			ts.when(Timestep::getCurrentYear).thenReturn(2020);
			ss.when(ServiceSet::getServicesList).thenReturn(java.util.List.of("S1"));
			ss.when(ServiceSet::getPenalise_Oversupply).thenReturn(new ConcurrentHashMap<>(Map.of("S1", true)));
			su.when(ServicesUpdater::getDemandByRegions).thenReturn(Map.of("R1", Map.of("S1", 100.0)));
			su.when(ServicesUpdater::getWeightByRegions).thenReturn(Map.of("R1", Map.of("S1", 200.0)));
			de.cesr.crafty.core.output.Tracker.sankeydata.computeIfAbsent("Abandoned", k -> new ConcurrentHashMap<>())
					.computeIfAbsent(2020, k -> new ConcurrentHashMap<>());

			ConfigLoader.config.use_explicit_price_utility = true;
			ConfigLoader.config.use_production_costs = true;
			// A strength far above the 0.03 default, so one step shows the whole chain.
			ConfigLoader.config.price_feedback_strength = 1.0;

			Aft intensive = mock(Aft.class);
			when(intensive.isInteract()).thenReturn(true);
			when(intensive.getLabel()).thenReturn("INT");
			when(intensive.getNfertCostPerHa()).thenReturn(900.0);
			Cell c = spy(new Cell(0, 0));
			c.setOwner(intensive);
			doReturn(8.0).when(c).competitiveness(intensive, "S1");

			Service s1 = mock(Service.class);
			when(s1.getWeights()).thenReturn(new ConcurrentHashMap<>(Map.of(2020, 200.0)));
			Region region = regionOf(c);
			region.getServicesHash().put("S1", s1);
			installRegion("R1", region);
			RegionalModelRunner r = new RegionalModelRunner("R1");
			// 50 % oversupply.
			r.setRegionalSupply(new ConcurrentHashMap<>(Map.of("S1", 150.0)));

			// Switch off: the file price holds, 200 x 8 - 900 = 700, and the owner stays.
			invokePrivate(r, "computeMarginal");
			invokePrivate(r, "utilitytyForAll");
			assertEquals(700.0, c.getCurrentUtility(), 1e-9);
			c.priceExplicitGiveUp(r);
			assertSame(intensive, c.getOwner());

			// Switch on: the price halves, 100 x 8 - 900 = -100, and the owner gives up.
			ConfigLoader.config.use_price_feedback = true;
			invokePrivate(r, "computeMarginal");
			invokePrivate(r, "utilitytyForAll");
			assertEquals(-100.0, c.getCurrentUtility(), 1e-9);
			c.priceExplicitGiveUp(r);
			assertNull(c.getOwner(), "Oversupply must make the intensive owner give up");
			assertTrue(region.getUnmanageCellsR().contains(c));
		}
	}

	// =========================================================
	// Helpers
	// =========================================================

	/** A mock cell with fixed production and area, whose supply is the real currentSupply(). */
	private static Cell cellWith(double[] production, double area) {
		Cell c = mock(Cell.class);
		when(c.getCurrentProd()).thenReturn(production);
		when(c.getArea()).thenReturn(area);
		when(c.currentSupply(anyInt())).thenCallRealMethod();
		return c;
	}

	private static Region regionOf(Cell... cells) {
		ConcurrentHashMap<String, Cell> map = new ConcurrentHashMap<>();
		for (int i = 0; i < cells.length; i++) {
			map.put(i + ",0", cells[i]);
		}
		Region region = new Region("R1");
		region.setCells(map);
		return region;
	}

	private static Region mockRegion(String name, ConcurrentHashMap<String, Cell> cells, int sizeOverride) {
		Region r = mock(Region.class);

		ConcurrentHashMap<String, Cell> cellsSpy = spy(cells);
		doReturn(sizeOverride).when(cellsSpy).size(); // <-- stub size on the map, not on Region

		when(r.getName()).thenReturn(name);
		when(r.getCells()).thenReturn(cellsSpy);

		return r;
	}

	@SuppressWarnings("unchecked")
	private static void installRegion(String regionName, Region region) throws Exception {
		Field regionsField = findField(CellsLoader.class, "regions");
		regionsField.setAccessible(true);

		Object regionsObj = regionsField.get(null);
		ConcurrentHashMap<String, Region> regions;
		if (regionsObj == null) {
			regions = new ConcurrentHashMap<>();
			regionsField.set(null, regions);
		} else {
			regions = (ConcurrentHashMap<String, Region>) regionsObj;
		}
		regions.put(regionName, region);
	}

	private static Object ensureConfigInstance() throws Exception {
		Field cfgField = findField(ConfigLoader.class, "config");
		cfgField.setAccessible(true);
		Object cfg = cfgField.get(null);
		if (cfg == null) {
			cfg = cfgField.getType().getDeclaredConstructor().newInstance();
			cfgField.set(null, cfg);
		}
		return cfg;
	}

	private static void invokePrivate(Object target, String methodName) throws Exception {
		Method m = target.getClass().getDeclaredMethod(methodName);
		m.setAccessible(true);
		m.invoke(target);
	}

	private static void setStaticBoolean(Class<?> cls, String fieldName, boolean value) throws Exception {
		Field f = findField(cls, fieldName);
		f.setAccessible(true);
		f.set(null, value);
	}

	private static void setStaticField(Class<?> cls, String fieldName, Object value) throws Exception {
		Field f = findField(cls, fieldName);
		f.setAccessible(true);
		f.set(null, value);
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
