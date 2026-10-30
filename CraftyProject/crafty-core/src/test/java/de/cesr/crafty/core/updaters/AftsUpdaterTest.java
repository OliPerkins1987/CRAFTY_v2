package de.cesr.crafty.core.updaters;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.core.ToyData;
import de.cesr.crafty.core.cli.Config;
import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.crafty.Aft;
import de.cesr.crafty.core.dataLoader.afts.AFTsLoader;
import de.cesr.crafty.core.dataLoader.serivces.ServiceSet;

/**
 * Unit tests for {@link AftsUpdater}.
 *
 * These tests focus on: - updateAFTProduction(...) + updateSensitivty(...)
 * using a small CSV.
 */
class AftsUpdaterTest {

	@TempDir
	Path tempDir;

	@BeforeEach
	void setUp() {
		ToyData toy = new ToyData();
		toy.resetStaticState(tempDir);
		// Minimal config
		if (ConfigLoader.config == null) {
			ConfigLoader.config = new Config();
		}

		// Reset service list and capitals
		ServiceSet.getServicesList().clear();
		ServiceSet.getServicesList().add("S1");
		ServiceSet.getServicesList().add("S2");

		CapitalUpdater.getCapitalsList().clear();
		CapitalUpdater.getCapitalsList().add("Cap1");
		CapitalUpdater.getCapitalsList().add("Cap2");

		if (AFTsLoader.getActivateAFTsHash() == null) {
			AFTsLoader.getActivateAFTsHash().clear();
		}
		AFTsLoader.getActivateAFTsHash().clear();
	}
	
    @AfterEach
    void tearDown() {
    	CustomLogger.shutdownRunFileLoggers();
    }

//	@Test
//	void updateAFTProduction_updatesProductivityAndSensitivityFromCsv() throws IOException {
//		// Build a tiny CSV:
//		//
//		// Service,Cap1,Cap2,Production
//		// S1,0.5,1.0,10
//		// S2,0.2,0.3,20
//		// IGNORE,9.9,9.9,999 (service not in ServiceSet, should be ignored)
//		//
//		Path csvPath = tempDir.resolve("aft_production.csv");
//		String csvContent = String.join("\n", "Service,Cap1,Cap2,Production", "S1,0.5,1.0,10", "S2,0.2,0.3,20",
//				"IGNORE,9.9,9.9,999");
//		Files.write(csvPath, csvContent.getBytes(StandardCharsets.UTF_8));
//
//		Aft aft = new Aft("AFT_TEST");
//
//		// Call the static update method
//		AftsUpdater.updatePorB(aft, csvPath);
//
//		// --- Check productivity levels (from "Production" column) ---
//		assertEquals(10.0, aft.getProductivityLevel().get("S1"), 1e-12);
//		assertEquals(20.0, aft.getProductivityLevel().get("S2"), 1e-12);
//		// "IGNORE" should not appear because it's not in ServiceSet.getServicesList()
//		assertFalse(aft.getProductivityLevel().containsKey("IGNORE"),
//				"Services not present in ServiceSet should be ignored in productivity update");
//
//		// --- Check sensitivities (from Cap1 / Cap2 columns) ---
//	}

	// -----------------------------
	// trade factors (transport costs)
	// -----------------------------

	@Test
	void updateAFTProduction_setsTheTradeFactorFromTheTradeColumnOnEachServiceRow() throws Exception {
		CapitalUpdater.getCapitalsList().add("s1_trade");
		Path csv = tempDir.resolve("Trader.csv");
		Files.write(csv, List.of(",Production,Cap1,Cap2,s1_trade", "S1,0.9,1,0,0.5", "S2,1.0,1,0,0"));
		Aft a = new Aft("Trader");

		AftsUpdater.updateAFTProduction(a, csv);

		assertEquals(0.5, a.getTradeFactor(0), 0.0, "S1 row, s1_trade column");
		assertEquals(0.0, a.getTradeFactor(1), 0.0, "S2 has no non-zero trade column");
		assertEquals(0.0, a.getTradeFactor(5), 0.0, "an index past the services reads as 0");
	}

	@Test
	void tradeFactor_isZero_forAnAftWhoseProductionFileWasNeverRead() {
		assertEquals(0.0, new Aft("Fresh").getTradeFactor(0), 0.0);
	}

	@Test
	void nonZeroTradeCapitals_findsOnlyNonZeroTradeColumns_sorted() {
		Map<String, Double> exps = Map.of("Cap1", 1.0, "b_trade", 0.2, "a_trade", 0.3, "c_trade", 0.0);
		assertEquals(List.of("a_trade", "b_trade"), AftsUpdater.nonZeroTradeCapitals(exps));
		assertEquals(List.of(), AftsUpdater.nonZeroTradeCapitals(Map.of("Cap1", 1.0)));
		assertEquals(List.of(), AftsUpdater.nonZeroTradeCapitals(null));
	}

	@Test
	void updateTradeFactors_addsSeveralTradeColumns_whereTransportCannotApply() {
		// Transport costs are off, so the ambiguity check does not stop the run.
		boolean before = ConfigLoader.config.use_transport_costs;
		try {
			ConfigLoader.config.use_transport_costs = false;
			Aft a = new Aft("Mixed");
			a.getSensByService().put("S1", new ConcurrentHashMap<>(Map.of("a_trade", 0.25, "b_trade", 0.5)));

			AftsUpdater.updateTradeFactors(a);

			assertEquals(0.75, a.getTradeFactor(0), 0.0);
			assertEquals(0.0, a.getTradeFactor(1), 0.0, "S2 has no sensitivities");
		} finally {
			ConfigLoader.config.use_transport_costs = before;
		}
	}

	@Test
	void tradeFactorIsCharged_onlyWithTransportAndGlobalProductionCosts() {
		Config c = ConfigLoader.config;
		boolean transportBefore = c.use_transport_costs;
		boolean spatialBefore = c.spatial_production_costs;
		try {
			c.use_transport_costs = true;
			c.spatial_production_costs = false;
			assertTrue(AftsUpdater.tradeFactorIsCharged("S1"), "global production costs");
			c.spatial_production_costs = true;
			assertFalse(AftsUpdater.tradeFactorIsCharged("S1"), "spatial: charged on export shares instead");
			c.use_transport_costs = false;
			c.spatial_production_costs = false;
			assertFalse(AftsUpdater.tradeFactorIsCharged("S1"), "no transport");
		} finally {
			c.use_transport_costs = transportBefore;
			c.spatial_production_costs = spatialBefore;
		}
	}

	@Test
	void updateTradeFactors_addsSeveralTradeColumns_withSpatialProductionCosts() {
		// Transport is charged on export shares, never on the trade factor, so the ambiguity check does not
		// stop the run.
		Config c = ConfigLoader.config;
		boolean transportBefore = c.use_transport_costs;
		boolean spatialBefore = c.spatial_production_costs;
		try {
			c.use_transport_costs = true;
			c.spatial_production_costs = true;
			Aft a = new Aft("Mixed");
			a.getSensByService().put("S1", new ConcurrentHashMap<>(Map.of("a_trade", 0.25, "b_trade", 0.5)));

			AftsUpdater.updateTradeFactors(a);

			assertEquals(0.75, a.getTradeFactor(0), 0.0);
		} finally {
			c.use_transport_costs = transportBefore;
			c.spatial_production_costs = spatialBefore;
		}
	}

}
