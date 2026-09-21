package de.cesr.crafty.react.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.core.cli.Config;
import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.crafty.Aft;
import de.cesr.crafty.core.crafty.Cell;
import de.cesr.crafty.core.crafty.Region;
import de.cesr.crafty.core.crafty.Service;
import de.cesr.crafty.core.dataLoader.afts.AFTsLoader;
import de.cesr.crafty.core.dataLoader.land.CellsLoader;
import de.cesr.crafty.core.modelRunner.ModelRunner;
import de.cesr.crafty.core.updaters.ProductionCostUpdater;
import de.cesr.crafty.core.updaters.Timestep;

/**
 * Checks that react reads core's state correctly. Core's state is static, so the test sets it up by
 * hand and puts it back afterwards, as ReactiveUpdaterTest already does.
 */
class CoreFactsTest {

	@TempDir
	Path dir;

	private Config originalConfig;
	private ProductionCostUpdater originalCostUpdater;
	private int originalStartYear;
	private int originalEndYear;

	@BeforeEach
	void setUp() {
		originalConfig = ConfigLoader.config;
		originalCostUpdater = ModelRunner.productionCostUpdater;
		originalStartYear = Timestep.getStartYear();
		originalEndYear = Timestep.getEndtYear();

		ConfigLoader.config = new Config();
		ConfigLoader.config.project_path = dir.toString();
		ConfigLoader.config.reactive_afts = true;
		ConfigLoader.config.reactive_fertilizer = true;
		ConfigLoader.config.reactive_stocking = true;
		Timestep.setStartYear(2020);
		Timestep.setEndtYear(2021);

		AFTsLoader.getAftHash().clear();
		AFTsLoader.getAftHash().put("IntC3C_irrig", aft(200, 0.75, true, 0));
		AFTsLoader.getAftHash().put("IntP", aft(0, 1.5, false, 3.5));

		CellsLoader.hashCell.clear();
		CellsLoader.hashCell.put("1,1", cell(1, 1, "North"));
		CellsLoader.hashCell.put("1,2", cell(1, 2, "South"));
		CellsLoader.regions.clear();
		CellsLoader.regions.put("North", region("North", 2020, 4.5));
		CellsLoader.regions.put("South", region("South", 2020, 9.0));

		Map<String, Path> costPaths = new LinkedHashMap<>();
		costPaths.put(ProductionCostUpdater.NFERT_COSTS, dir.resolve("Nfert_costs_2020.csv"));
		costPaths.put(ProductionCostUpdater.STOCKING_COSTS, dir.resolve("stocking_costs_2020.csv"));
		ProductionCostUpdater costUpdater = mock(ProductionCostUpdater.class);
		when(costUpdater.getSpatialCostPaths(2020)).thenReturn(costPaths);
		when(costUpdater.getSpatialCostPaths(2021)).thenReturn(Map.of());
		ModelRunner.productionCostUpdater = costUpdater;
	}

	@AfterEach
	void restore() {
		ConfigLoader.config = originalConfig;
		ModelRunner.productionCostUpdater = originalCostUpdater;
		Timestep.setStartYear(originalStartYear);
		Timestep.setEndtYear(originalEndYear);
		AFTsLoader.getAftHash().clear();
		CellsLoader.hashCell.clear();
		CellsLoader.regions.clear();
	}

	private static Aft aft(double nfertRate, double otherIntensity, boolean irrigated, double pastureProduction) {
		Aft aft = new Aft("x");
		aft.setNfertRate(nfertRate);
		aft.setOtherIntensity(otherIntensity);
		aft.setIrrigated(irrigated);
		aft.getProductivityLevel().put("Pasture", pastureProduction);
		return aft;
	}

	private static Cell cell(int x, int y, String regionName) {
		Cell cell = new Cell(x, y);
		cell.setCurrentRegion(regionName);
		return cell;
	}

	private static Region region(String name, int year, double weight) {
		Region region = new Region(name);
		Service service = new Service("C3cereals");
		service.getWeights().put(year, weight);
		region.getServicesHash().put("C3cereals", service);
		return region;
	}

	@Test
	void theBaselinesComeFromCoresAftMetadata() {
		ReactRunContext context = CoreFacts.fromCore();

		assertEquals(Set.of("IntC3C_irrig", "IntP"), context.afts().keySet());
		ReactRunContext.AftBaseline intensive = context.afts().get("IntC3C_irrig");
		assertEquals(200, intensive.nfertRate());
		assertEquals(0.75, intensive.otherIntensity());
		assertTrue(intensive.irrigated());
		assertFalse(intensive.producesPasture(), "It produces no Pasture, so core would charge it no stocking cost");
		assertTrue(context.afts().get("IntP").producesPasture(), "Taken from core's production level, not from react");
	}

	@Test
	void theCellsCarryTheirRegions() {
		ReactRunContext context = CoreFacts.fromCore();

		assertEquals(Set.of("1,1", "1,2"), context.cellIds());
		assertEquals("North", context.regionOfCell("1,1"));
		assertEquals("South", context.regionOfCell("1,2"));
		assertEquals(Set.of("North", "South"), context.regions());
	}

	@Test
	void theRunAndSwitchesAreRead() {
		ReactRunContext context = CoreFacts.fromCore();

		assertEquals(dir, context.projectPath());
		assertEquals(2020, context.firstYear());
		assertEquals(2021, context.lastYear());
		assertEquals(Set.of(ReactElement.FERTILISER, ReactElement.STOCKING), context.reactive());
	}

	@Test
	void theCostFilesCoreFoundAreListedPerYear() {
		ReactRunContext context = CoreFacts.fromCore();

		assertEquals(Set.of(ReactElement.FERTILISER, ReactElement.STOCKING), context.costFilesByYear().get(2020));
		assertEquals(Set.of(), context.costFilesByYear().get(2021), "A year core found no file for");
	}

	@Test
	void aServicesPriceIsItsUtilityWeightForThatRegionAndYear() {
		ReactRunContext context = CoreFacts.fromCore();

		assertEquals(4.5, context.price("C3cereals", "North", 2020));
		assertEquals(9.0, context.price("C3cereals", "South", 2020));
		assertThrows(ReactInputException.class, () -> context.price("C3cereals", "East", 2020));
		assertThrows(ReactInputException.class, () -> context.price("Pasture", "North", 2020));
		assertThrows(ReactInputException.class, () -> context.price("C3cereals", "North", 2021));
	}

	@Test
	void theServicesComeFromCoresServiceList() throws Exception {
		Field servicesList = de.cesr.crafty.core.dataLoader.serivces.ServiceSet.class.getDeclaredField("servicesList");
		servicesList.setAccessible(true);
		Object original = servicesList.get(null);
		try {
			servicesList.set(null, List.of("C3cereals", "Pasture"));

			assertEquals(Set.of("C3cereals", "Pasture"), CoreFacts.fromCore().services());
		} finally {
			servicesList.set(null, original);
		}
	}
}
