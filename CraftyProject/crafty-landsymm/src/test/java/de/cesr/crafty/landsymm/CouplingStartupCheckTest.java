package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.core.cli.Config;

class CouplingStartupCheckTest {

	@TempDir
	Path tempDir;

	/** The global set-up's config.yaml as it is on 12 Nov 2026, as far as the coupling checks look. */
	private static Config todaysGlobalConfig() {
		Config c = new Config();
		c.regionalisation = true;
		c.use_explicit_price_utility = true;
		c.initial_demand_supply_equilibrium = false;
		c.use_price_feedback = true;
		c.global_price_feedback = true;
		c.use_production_costs = true;
		c.spatial_production_costs = true;
		c.use_transport_costs = true;
		return c;
	}

	/** A correct coupled scenario run's config, with paths under the temporary folder. */
	private Config coupledScenario() {
		Config c = todaysGlobalConfig();
		c.coupled_with_plum = true;
		c.use_price_feedback = false;
		c.global_price_feedback = false;
		c.crafty_production_path = tempDir.resolve("run").resolve("production").toString();
		c.plum_calibration_path = tempDir.resolve("calib").resolve("plum").resolve("output").toString();
		c.plum_calibration_final_year = 2030;
		c.calibration_production_path = tempDir.resolve("calib").resolve("production").toString();
		c.plum_output_path = tempDir.resolve("run").resolve("plum").resolve("output").toString();
		return c;
	}

	// ---- Config ----

	@Test
	void todaysGlobalConfigStopsWithEveryCouplingProblem() {
		CouplingStartupCheck.Report r = CouplingStartupCheck.configReport(todaysGlobalConfig());

		List<String> keys = List.of("coupled_with_plum", "use_price_feedback", "global_price_feedback",
				"crafty_production_path", "plum_calibration_path", "plum_calibration_final_year",
				"calibration_production_path", "plum_output_path");
		assertEquals(keys.size(), r.problems().size(), String.join("\n", r.problems()));
		for (int i = 0; i < keys.size(); i++) {
			assertTrue(r.problems().get(i).startsWith(keys.get(i)), r.problems().get(i));
		}
		assertEquals(List.of(), r.warnings());
	}

	@Test
	void aCorrectCoupledConfigHasNoProblems() {
		CouplingStartupCheck.Report r = CouplingStartupCheck.configReport(coupledScenario());

		assertEquals(List.of(), r.problems());
		assertEquals(List.of(), r.warnings());
	}

	@Test
	void aCalibrationRunNeedsNoScenarioPaths() {
		Config c = new Config();
		c.coupled_with_plum = true;
		c.plum_calibration_run = true;
		c.regionalisation = true;
		c.use_explicit_price_utility = true;
		c.initial_demand_supply_equilibrium = false;
		c.crafty_production_path = "C:/runs/calib/production";
		c.plum_consumption = false;

		assertEquals(List.of(), CouplingStartupCheck.configReport(c).problems());
	}

	@Test
	void eachCoreSettingACoupledRunNeedsIsChecked() {
		Config c = coupledScenario();
		c.initial_demand_supply_equilibrium = true;
		c.use_explicit_price_utility = false;
		c.regionalisation = false;

		List<String> problems = CouplingStartupCheck.configReport(c).problems();

		assertEquals(3, problems.size(), String.join("\n", problems));
		assertTrue(problems.get(0).startsWith("initial_demand_supply_equilibrium"));
		assertTrue(problems.get(1).startsWith("use_explicit_price_utility"));
		assertTrue(problems.get(2).startsWith("regionalisation"));
	}

	@Test
	void theStandInTradeCapitalRuleAndUnusedConsumptionAreWarnedAbout() {
		Config c = coupledScenario();
		c.dynamic_trade_capitals = true;
		c.use_transport_costs = false;

		CouplingStartupCheck.Report r = CouplingStartupCheck.configReport(c);

		assertEquals(List.of(), r.problems());
		assertEquals(2, r.warnings().size(), String.join("\n", r.warnings()));
		assertTrue(r.warnings().get(0).startsWith("dynamic_trade_capitals"));
		assertTrue(r.warnings().get(1).startsWith("plum_consumption"));
	}

	// ---- Data ----

	private Path gis() throws IOException {
		Path file = tempDir.resolve("global_Regions.csv");
		Files.writeString(file, "\"X\",\"Y\",\"Region_Code\",\"PLUM_country\"\n1,1,\"Tanzania\",\"Tanzania\"\n"
				+ "2,1,\"Italy_and_Malta\",\"Italy & Malta\"\n");
		return file;
	}

	/** A finished calibration run's folders for config c: the final year's done, the outputs, and production/1990. */
	private void finishedCalibration(Config c) throws IOException {
		Path plum = Path.of(c.plum_calibration_path);
		Files.createDirectories(plum.resolve("2030"));
		Files.createFile(plum.resolve("2030").resolve("done"));
		for (String f : List.of("prices.txt", "domestic.txt", "wood.txt")) {
			Files.writeString(plum.resolve(f), "Year\n");
		}
		Path production = Path.of(c.calibration_production_path).resolve("1990");
		Files.createDirectories(production);
		Files.writeString(production.resolve("production.csv"), "Country,Commodity,Production\n");
	}

	@Test
	void aCorrectScenarioRunsInputsHaveNoProblems() throws Exception {
		Config c = coupledScenario();
		finishedCalibration(c);

		CouplingStartupCheck.Report r = CouplingStartupCheck.dataReport(c, ServiceCommodityMapTest.globalServices(),
				gis(), 1990, 2020);

		assertEquals(List.of(), r.problems());
		assertEquals(13, r.services().links().size());
		assertEquals(Set.of("Tanzania", "Italy_and_Malta"), r.key().regions());
	}

	@Test
	void anUnfinishedCalibrationStops() throws Exception {
		Config c = coupledScenario();
		finishedCalibration(c);
		Files.delete(Path.of(c.plum_calibration_path).resolve("2030").resolve("done"));
		Files.delete(Path.of(c.plum_calibration_path).resolve("wood.txt"));
		Files.delete(Path.of(c.calibration_production_path).resolve("1990").resolve("production.csv"));

		List<String> problems = CouplingStartupCheck.dataReport(c, ServiceCommodityMapTest.globalServices(), gis(),
				1990, 2020).problems();

		assertEquals(3, problems.size(), String.join("\n", problems));
		assertTrue(problems.get(0).startsWith("The calibration isn't finished"));
		assertTrue(problems.get(1).contains("wood.txt"));
		assertTrue(problems.get(2).contains("production.csv"));
	}

	@Test
	void anotherRunsProductionInTheFolderStops() throws Exception {
		Config c = coupledScenario();
		finishedCalibration(c);
		Path done = DoneFileWaiter.doneFile(Path.of(c.crafty_production_path), 1990);
		Files.createDirectories(done.getParent());
		Files.createFile(done);

		List<String> problems = CouplingStartupCheck.dataReport(c, ServiceCommodityMapTest.globalServices(), gis(),
				1990, 2020).problems();

		assertEquals(1, problems.size(), String.join("\n", problems));
		assertTrue(problems.get(0).contains("already exists"));
	}

	@Test
	void theYearsAreChecked() throws Exception {
		Config c = coupledScenario();
		finishedCalibration(c);
		c.coupled_end_year = 2021;

		List<String> late = CouplingStartupCheck.dataReport(c, ServiceCommodityMapTest.globalServices(), gis(), 1990,
				2020).problems();
		assertEquals(1, late.size(), String.join("\n", late));
		assertTrue(late.get(0).startsWith("coupled_end_year (2021)"));

		c.coupled_end_year = 2000;
		assertEquals(List.of(), CouplingStartupCheck.dataReport(c, ServiceCommodityMapTest.globalServices(), gis(),
				1990, 2020).problems());

		c.plum_calibration_final_year = 1990;
		List<String> early = CouplingStartupCheck.dataReport(c, ServiceCommodityMapTest.globalServices(), gis(), 1990,
				2020).problems();
		assertTrue(early.stream().anyMatch(p -> p.startsWith("plum_calibration_final_year (1990)")),
				String.join("\n", early));
	}

	@Test
	void badServicesAndNoGisFileAreReportedTogether() throws Exception {
		Config c = coupledScenario();
		c.plum_calibration_run = true;
		Path services = tempDir.resolve("Services.csv");
		Files.writeString(services, "Name,Market_good\nC3cereals,Yes\n");

		CouplingStartupCheck.Report r = CouplingStartupCheck.dataReport(c, services, null, 1990, 2020);

		assertEquals(2, r.problems().size(), String.join("\n", r.problems()));
		assertTrue(r.problems().get(0).contains("PLUM_name"));
		assertTrue(r.problems().get(1).startsWith("No GIS file"));
		assertNull(r.services());
		assertNull(r.key());
	}

	// ---- After loading ----

	@Test
	void afterLoadingTheGisFileRegionsAndRegionalisationAreChecked() throws Exception {
		RegionCountryKey key = RegionCountryKey.read(gis());

		assertEquals(List.of(), CouplingStartupCheck.afterLoadProblems(key, gis(), Set.of("Tanzania"), true));

		List<String> problems = CouplingStartupCheck.afterLoadProblems(key, tempDir.resolve("other_Regions.csv"),
				Set.of("Tanzania", "Atlantis"), true);
		assertEquals(2, problems.size(), String.join("\n", problems));
		assertTrue(problems.get(0).contains("other_Regions.csv"));
		assertTrue(problems.get(1).contains("[Atlantis]"));

		List<String> single = CouplingStartupCheck.afterLoadProblems(key, gis(), Set.of("World"), false);
		assertEquals(1, single.size(), String.join("\n", single));
		assertTrue(single.get(0).startsWith("CRAFTY fell back to a single region"));
	}

	@Test
	void theGisFileIsGisPathWhenThatIsAFile() throws Exception {
		Config c = new Config();
		c.gis_path = gis().toString();

		assertEquals(gis(), CouplingStartupCheck.locateGisFile(c));
	}
}
