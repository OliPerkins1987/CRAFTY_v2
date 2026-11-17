package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.core.cli.Config;
import de.cesr.crafty.core.cli.ConfigLoader;

class CraftyRunConfigTest {

	@TempDir
	Path tempDir;

	private LauncherFixture f;

	@BeforeEach
	void setUp() throws Exception {
		f = new LauncherFixture(tempDir);
	}

	private CraftyRunConfig.Base base(String extra) throws Exception {
		Files.writeString(f.craftyBase, Files.readString(f.craftyBase) + extra);
		List<String> problems = new ArrayList<>();
		CraftyRunConfig.Base b = CraftyRunConfig.readBase(f.craftyBase, problems);
		assertEquals(List.of(), problems);
		return b;
	}

	private MasterConfig calibrationMaster() throws Exception {
		return MasterConfig.read(f.master("m.yaml", f.calibrationMaster("calib", "outputs:\n  export_basis: false\n")));
	}

	@Test
	void theScenarioAndItsYearsComeFromScenariosCsv() throws Exception {
		List<String> problems = new ArrayList<>();

		CraftyRunConfig.Scenario s = CraftyRunConfig.scenario(base(""), problems);

		assertEquals(List.of(), problems);
		assertEquals(new CraftyRunConfig.Scenario("hist", 1990, 2020), s);
	}

	@Test
	void aScenarioThatIsntInTheFileStops() throws Exception {
		Files.writeString(f.craftyBase, Files.readString(f.craftyBase).replace("\"hist\"", "\"histt\""));
		List<String> problems = new ArrayList<>();

		assertNull(CraftyRunConfig.scenario(CraftyRunConfig.readBase(f.craftyBase, problems), problems));
		assertTrue(problems.get(0).contains("sets scenario histt, which isn't in"), problems.toString());
	}

	@Test
	void aCouplerSettingInTheBaseFileStops() throws Exception {
		List<String> problems = CraftyRunConfig.baseProblems(base("dynamic_trade_capitals: true\n"
				+ "plum_coupling_outputs: []\n"));

		assertEquals(2, problems.size(), problems.toString());
		assertTrue(problems.get(0).contains("sets dynamic_trade_capitals") && problems.get(0).contains("coupling block"),
				problems.get(0));
		assertTrue(problems.get(1).contains("sets plum_coupling_outputs") && problems.get(1).contains("outputs block"),
				problems.get(1));
	}

	@Test
	void aRelativePathInTheBaseFileStopsButTheLaunchersOwnKeysDont() throws Exception {
		List<String> problems = CraftyRunConfig.baseProblems(base("capitals_directory: \"worlds/capitals\"\n"
				+ "output_path: \"out\"\ncrafty_production_path: \"prod\"\n"));

		assertEquals(1, problems.size(), problems.toString());
		assertTrue(problems.get(0).contains("capitals_directory is a relative path"), problems.get(0));
	}

	@Test
	void theMarketSwitchesTheCopyTurnsOff() throws Exception {
		assertEquals(CraftyRunConfig.MARKET_SWITCHES, CraftyRunConfig.switchesTurnedOff(base("")),
				"price feedback is on in the base, and the equilibrium is on by default");
		CraftyRunConfig.Base allOff = new CraftyRunConfig.Base(f.craftyBase, "",
				Map.of("use_price_feedback", false, "initial_demand_supply_equilibrium", false));
		assertEquals(List.of(), CraftyRunConfig.switchesTurnedOff(allOff));
	}

	@Test
	void theCopyLoadsWithCoresLoaderAndPassesTheCouplersCheck() throws Exception {
		MasterConfig m = calibrationMaster();
		RunFolder run = new RunFolder(m.runFolder());
		Map<String, Object> values = CraftyRunConfig.values(m, run, null);
		RunConfigWriter.Copy copy = CraftyRunConfig.copy(base(""), values, List.of("h"));
		Path file = Files.writeString(tempDir.resolve("copy.yaml"), copy.text());
		Config before = ConfigLoader.config;
		String pathBefore = ConfigLoader.configPath;

		CraftyRunConfig.CoreCheck check = CraftyRunConfig.checkWithCore(file, values);

		assertEquals(List.of(), check.problems());
		assertSame(before, ConfigLoader.config, "core's state is put back");
		assertEquals(pathBefore, ConfigLoader.configPath);
		assertTrue(values.keySet().stream().allMatch(CraftyRunConfig.RUN_KEYS::contains), values.keySet().toString());
		assertEquals(List.of("prices_received", "production_sent", "trade_capitals", "ungrazed_grass"),
				values.get("plum_coupling_outputs"));
	}

	@Test
	void aValueCoreReadsDifferentlyIsCaught() throws Exception {
		MasterConfig m = calibrationMaster();
		Map<String, Object> values = new java.util.LinkedHashMap<>(
				CraftyRunConfig.values(m, new RunFolder(m.runFolder()), null));
		RunConfigWriter.Copy copy = CraftyRunConfig.copy(base(""), values, List.of());
		Path file = Files.writeString(tempDir.resolve("copy.yaml"), copy.text());
		values.put("output_folder_name", "something else");

		CraftyRunConfig.CoreCheck check = CraftyRunConfig.checkWithCore(file, values);

		assertEquals(1, check.problems().size(), check.problems().toString());
		assertTrue(check.problems().get(0).contains("reads output_folder_name"), check.problems().get(0));
	}

	@Test
	void theCouplersStartUpCheckRunsOnTheCopy() throws Exception {
		MasterConfig m = calibrationMaster();
		Map<String, Object> values = CraftyRunConfig.values(m, new RunFolder(m.runFolder()), null);
		Files.writeString(f.craftyBase,
				Files.readString(f.craftyBase).replace("regionalisation: true", "regionalisation: false"));
		RunConfigWriter.Copy copy = CraftyRunConfig.copy(base(""), values, List.of());
		Path file = Files.writeString(tempDir.resolve("copy.yaml"), copy.text());

		CraftyRunConfig.CoreCheck check = CraftyRunConfig.checkWithCore(file, values);

		assertEquals(1, check.problems().size(), check.problems().toString());
		assertTrue(check.problems().get(0).startsWith("regionalisation is false"), "never changed by the launcher (Q2 a)");
	}

	@Test
	void anUnknownKeyIsCaughtByCoresLoader() throws Exception {
		MasterConfig m = calibrationMaster();
		Map<String, Object> values = CraftyRunConfig.values(m, new RunFolder(m.runFolder()), null);
		RunConfigWriter.Copy copy = CraftyRunConfig.copy(base("use_price_feedbak: false\n"), values, List.of());
		Path file = Files.writeString(tempDir.resolve("copy.yaml"), copy.text());

		CraftyRunConfig.CoreCheck check = CraftyRunConfig.checkWithCore(file, values);

		assertEquals(1, check.problems().size(), check.problems().toString());
		assertTrue(check.problems().get(0).contains("unknown key 'use_price_feedbak'"), check.problems().get(0));
	}

	@Test
	void loadedValuesAreComparedByMeaning() {
		assertTrue(CraftyRunConfig.same(6, 6.0));
		assertTrue(CraftyRunConfig.same(List.of("a"), new ArrayList<>(List.of("a"))));
		assertFalse(CraftyRunConfig.same(false, true));
		assertFalse(CraftyRunConfig.same("x", "y"));
	}
}
