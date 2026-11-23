package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.core.cli.Config;
import de.cesr.crafty.core.crafty.Aft;
import de.cesr.crafty.core.crafty.ManagerTypes;

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
	void unusedConsumptionIsWarnedAboutButTheTradeCapitalRuleNoLonger() {
		Config c = coupledScenario();
		c.dynamic_trade_capitals = true;
		c.use_transport_costs = false;

		CouplingStartupCheck.Report r = CouplingStartupCheck.configReport(c);

		assertEquals(List.of(), r.problems());
		assertEquals(1, r.warnings().size(), String.join("\n", r.warnings()));
		assertTrue(r.warnings().get(0).startsWith("plum_consumption"));
	}

	@Test
	void aRegionalStartThatChangesNothingIsWarnedAbout() {
		Config exporter = coupledScenario();
		exporter.initialise_regional_price_weights = true;
		exporter.plum_price_basis = "exporter";
		List<String> warnings = CouplingStartupCheck.configReport(exporter).warnings();
		assertEquals(1, warnings.size(), String.join("\n", warnings));
		assertTrue(warnings.get(0).contains("plum_price_basis is exporter"), warnings.get(0));

		Config factor = coupledScenario();
		factor.initialise_regional_price_weights = true;
		factor.plum_price_scaling = "factor";
		assertTrue(CouplingStartupCheck.configReport(factor).warnings().stream()
				.anyMatch(w -> w.contains("plum_price_scaling is factor")));

		Config domestic = coupledScenario();
		domestic.initialise_regional_price_weights = true;
		assertEquals(List.of(), CouplingStartupCheck.configReport(domestic).warnings(), "domestic and index: it acts");
	}

	@Test
	void withDynamicTradeCapitalsCapitalsCsvsPlumNamesAreRead() throws Exception {
		Config c = coupledScenario();
		c.dynamic_trade_capitals = true;
		finishedCalibration(c);

		CouplingStartupCheck.Report r = CouplingStartupCheck.dataReport(c, ServiceCommodityMapTest.globalServices(),
				TradeCapitalMapTest.capitals(), gis(), 1990, 2020);

		assertEquals(List.of(), r.problems());
		assertEquals(9, r.tradeCapitals().cropByCapital().size());

		c.dynamic_trade_capitals = false;
		assertNull(CouplingStartupCheck.dataReport(c, ServiceCommodityMapTest.globalServices(),
				TradeCapitalMapTest.capitals(), gis(), 1990, 2020).tradeCapitals(), "only read with the switch on");
	}

	@Test
	void capitalsCsvProblemsAndUntaggedTradeCapitalsAreReported() throws Exception {
		Config c = coupledScenario();
		c.dynamic_trade_capitals = true;
		finishedCalibration(c);
		Path capitals = tempDir.resolve("Capitals.csv");
		Files.writeString(capitals, Files.readString(TradeCapitalMapTest.capitals()).replace("C3 rice,rice", "C3 rice,"));

		CouplingStartupCheck.Report untagged = CouplingStartupCheck.dataReport(c,
				ServiceCommodityMapTest.globalServices(), capitals, gis(), 1990, 2020);
		assertEquals(List.of(), untagged.problems());
		assertTrue(untagged.warnings().stream().anyMatch(w -> w.contains("[rice_trade]")),
				String.join("\n", untagged.warnings()));

		Files.writeString(capitals, "Label,Name,Type,Description\nc3_cereals_trade,x,Capital,x\n");
		assertTrue(CouplingStartupCheck.dataReport(c, ServiceCommodityMapTest.globalServices(), capitals, gis(), 1990,
				2020).problems().stream().anyMatch(p -> p.contains("PLUM_name")));

		assertTrue(CouplingStartupCheck.dataReport(c, ServiceCommodityMapTest.globalServices(), null, gis(), 1990,
				2020).problems().stream().anyMatch(p -> p.contains("no Capitals.csv")));
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

		CouplingStartupCheck.Report r = CouplingStartupCheck.dataReport(c, ServiceCommodityMapTest.globalServices(), null,
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

		List<String> problems = CouplingStartupCheck.dataReport(c, ServiceCommodityMapTest.globalServices(), null, gis(),
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

		List<String> problems = CouplingStartupCheck.dataReport(c, ServiceCommodityMapTest.globalServices(), null, gis(),
				1990, 2020).problems();

		assertEquals(1, problems.size(), String.join("\n", problems));
		assertTrue(problems.get(0).contains("already exists"));
	}

	@Test
	void theYearsAreChecked() throws Exception {
		Config c = coupledScenario();
		finishedCalibration(c);
		c.coupled_end_year = 2021;

		List<String> late = CouplingStartupCheck.dataReport(c, ServiceCommodityMapTest.globalServices(), null, gis(), 1990,
				2020).problems();
		assertEquals(1, late.size(), String.join("\n", late));
		assertTrue(late.get(0).startsWith("coupled_end_year (2021)"));

		c.coupled_end_year = 2000;
		assertEquals(List.of(), CouplingStartupCheck.dataReport(c, ServiceCommodityMapTest.globalServices(), null, gis(),
				1990, 2020).problems());

		c.plum_calibration_final_year = 1990;
		List<String> early = CouplingStartupCheck.dataReport(c, ServiceCommodityMapTest.globalServices(), null, gis(), 1990,
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

		CouplingStartupCheck.Report r = CouplingStartupCheck.dataReport(c, services, null, null, 1990, 2020);

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

		assertEquals(List.of(),
				CouplingStartupCheck.afterLoadProblems(key, gis(), Set.of("Tanzania", "Italy_and_Malta"), true));

		List<String> problems = CouplingStartupCheck.afterLoadProblems(key, tempDir.resolve("other_Regions.csv"),
				Set.of("Tanzania", "Italy_and_Malta", "Atlantis"), true);
		assertEquals(2, problems.size(), String.join("\n", problems));
		assertTrue(problems.get(0).contains("other_Regions.csv"));
		assertTrue(problems.get(1).contains("[Atlantis]"));

		List<String> single = CouplingStartupCheck.afterLoadProblems(key, gis(), Set.of("World"), false);
		assertEquals(1, single.size(), String.join("\n", single));
		assertTrue(single.get(0).startsWith("CRAFTY fell back to a single region"));
	}

	@Test
	void aRegionInTheKeyWithNoCellsStops() throws Exception {
		RegionCountryKey key = RegionCountryKey.read(gis());

		List<String> problems = CouplingStartupCheck.afterLoadProblems(key, gis(), Set.of("Tanzania"), true);

		assertEquals(1, problems.size(), String.join("\n", problems));
		assertTrue(problems.get(0).contains("no cells in CRAFTY: [Italy_and_Malta]"), problems.get(0));
	}

	@Test
	void theGisFileIsGisPathWhenThatIsAFile() throws Exception {
		Config c = new Config();
		c.gis_path = gis().toString();

		assertEquals(gis(), CouplingStartupCheck.locateGisFile(c));
	}

	// ---- Non-economic service prices (50a) ----

	private static Aft aft(String label, ManagerTypes type, double biodiversityLevel) {
		Aft a = new Aft(label);
		a.setType(type);
		if (biodiversityLevel > 0) {
			a.setSensByService(Map.of("Biodiversity", Map.of("biodiversity_suit", 1.0)));
			a.getProductivityLevel().put("Biodiversity", biodiversityLevel);
		}
		return a;
	}

	private static List<Aft> globalAfts() {
		return List.of(aft("Conservation_Nonex", ManagerTypes.AFT, 1), aft("AgriEcoC3C", ManagerTypes.AFT, 0.5),
				aft("IntAgriC3C", ManagerTypes.AFT, 0), aft("UL", ManagerTypes.MASK, 0), new Aft("Abandoned"));
	}

	private Path capitals(String... labels) throws IOException {
		StringBuilder text = new StringBuilder("Label,Name,Type,PLUM_name,Description\n");
		for (String label : labels) {
			text.append(label).append(",x,Suitability,,x\n");
		}
		return Files.writeString(tempDir.resolve("Capitals.csv"), text);
	}

	private Path aftsMetaData(String column) throws IOException {
		return Files.writeString(tempDir.resolve("AFTsMetaData.csv"), "Label,Type," + column + "\n"
				+ "Conservation_Nonex,AFT,1\nAgriEcoC3C,AFT,\nIntAgriC3C,AFT,\nUL,Mask,1\n");
	}

	@Test
	void aGoodNesSetUpWarnsOfProducersThatDontCountAndNotesTheCountedLand() throws Exception {
		CouplingStartupCheck.NesReport r = CouplingStartupCheck.nesReport("Biodiversity", "biodiversity_suit",
				List.of("C1", "Biodiversity"), Map.of("C1", true, "Biodiversity", false),
				capitals("Education", "biodiversity_suit"), aftsMetaData("biodiversity_counted_land"), tempDir.toString(),
				Set.of(), globalAfts());

		assertEquals(List.of(), r.problems());
		assertEquals(1, r.warnings().size(), r.warnings().toString());
		assertTrue(r.warnings().get(0).contains("[AgriEcoC3C] produce Biodiversity but count 0"), r.warnings().get(0));
		assertEquals(1, r.notes().size());
		assertTrue(r.notes().get(0).contains("Conservation_Nonex 1.0, UL 1.0; [UL] count but can't be paid"),
				r.notes().get(0));
		assertEquals(1.0, r.counted().fraction(new Aft("UL")), 0.0);
	}

	@Test
	void everyNesProblemIsListedTogether() throws Exception {
		Path oneFile = Files.writeString(tempDir.resolve("weights_global.csv"), "Biodiversity\n1\n");

		CouplingStartupCheck.NesReport r = CouplingStartupCheck.nesReport("Biodiversity", "bii",
				List.of("C1", "Biodiversity"), Map.of("C1", true, "Biodiversity", true), capitals("biodiversity_suit"),
				aftsMetaData("Pays_stock_cost"), oneFile.toString(), Set.of("Tanzania", "Argentina"), globalAfts());

		assertEquals(5, r.problems().size(), String.join("\n", r.problems()));
		assertTrue(r.problems().get(0).contains("Biodiversity is a market good"), r.problems().get(0));
		assertTrue(r.problems().get(1).contains("Capitals.csv has no capital bii"), r.problems().get(1));
		assertTrue(r.problems().get(2).contains("service_utility_weights_path is one file"), r.problems().get(2));
		assertTrue(r.problems().get(3).startsWith("2 region(s) have no weight file of their own")
				&& r.problems().get(3).contains("[Argentina, Tanzania]"), r.problems().get(3));
		assertTrue(r.problems().get(4).contains("has no biodiversity_counted_land column"), r.problems().get(4));
		assertNull(r.counted());
		assertEquals(List.of(), r.notes());
	}

	@Test
	void aServiceCraftyDoesntHaveStops() throws Exception {
		CouplingStartupCheck.NesReport r = CouplingStartupCheck.nesReport("Biodiversity", "biodiversity_suit",
				List.of("C1"), Map.of("C1", true), capitals("biodiversity_suit"), aftsMetaData("biodiversity_counted_land"),
				"", Set.of(), globalAfts());

		assertEquals(1, r.problems().size(), String.join("\n", r.problems()));
		assertTrue(r.problems().get(0).contains("dynamic_biodiversity_price is on, but CRAFTY has no Biodiversity"),
				r.problems().get(0));
	}
}
