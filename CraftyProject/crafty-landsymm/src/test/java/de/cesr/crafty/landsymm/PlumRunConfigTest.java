package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlumRunConfigTest {

	@TempDir
	Path tempDir;

	private PlumRunConfig.Base base(String text) throws Exception {
		Path file = Files.writeString(tempDir.resolve("config.properties"), text);
		List<String> problems = new ArrayList<>();
		PlumRunConfig.Base b = PlumRunConfig.readBase(file, problems);
		assertEquals(List.of(), problems);
		return b;
	}

	/** A good base: an absolute BASE_DIR on any system, CRAFTY mode, 1990. */
	private String good() {
		return "BASE_DIR=" + LauncherFixture.slashes(tempDir) + "\nALL_CRAFTY_COUNTRIES=true\nBASE_YEAR=1990\n";
	}

	@Test
	void aGoodBaseHasNoProblems() throws Exception {
		assertEquals(List.of(), PlumRunConfig.baseProblems(base(good()), true));
		assertEquals(List.of(), PlumRunConfig.baseProblems(base(good()), false));
	}

	@Test
	void craftyModeMustBeOnAsPlumReadsIt() throws Exception {
		for (String value : List.of("", "ALL_CRAFTY_COUNTRIES=false\n", "ALL_CRAFTY_COUNTRIES=true \n")) {
			List<String> problems = PlumRunConfig.baseProblems(base("BASE_YEAR=1990\n" + value), true);
			assertEquals(1, problems.size(), value);
			assertTrue(problems.get(0).contains("doesn't set ALL_CRAFTY_COUNTRIES=true"), problems.get(0));
		}
	}

	@Test
	void theBaseYearIsPlumsDefaultWhenNotSetAndMustBeAWholeNumber() throws Exception {
		List<String> problems = new ArrayList<>();
		assertEquals(2020, PlumRunConfig.baseYear(base("ALL_CRAFTY_COUNTRIES=true\n"), problems));
		assertEquals(1990, PlumRunConfig.baseYear(base(good()), problems));
		assertEquals(List.of(), problems);

		assertNull(PlumRunConfig.baseYear(base("BASE_YEAR=1990 \n"), problems), "PLUM's Integer.valueOf doesn't trim");
		assertEquals(1, problems.size());
	}

	@Test
	void oneYearAStepAndAScenarioThatMovesThroughTheYears() throws Exception {
		List<String> step = PlumRunConfig.baseProblems(base(good() + "TIMESTEP_SIZE=5\n"), true);
		assertTrue(step.get(0).contains("TIMESTEP_SIZE must be 1"), step.toString());

		List<String> scenario = PlumRunConfig.baseProblems(base(good() + "CHANGE_DEMAND_YEAR=false\n"), false);
		assertTrue(scenario.get(0).contains("CHANGE_DEMAND_YEAR"), scenario.toString());
		assertEquals(List.of(), PlumRunConfig.baseProblems(base(good() + "CHANGE_DEMAND_YEAR=false\n"), true),
				"PLUM ignores it in calibration");
	}

	@Test
	void relativePathsStopButTheLaunchersKeysDont() throws Exception {
		List<String> problems = PlumRunConfig.baseProblems(base(good() + "DATA_DIR=data1990\nOUTPUT_DIR=.\n"
				+ "WOOD_DEMAND_FILENAME=wood.csv\nCHECKPOINT_LAND_USE_FILE=lu.ser\n"
				+ "IS_1STGEN_DEMAND_FROM_FILE=true\nCLEANUP_GAMS_DIR=False\n"), true); // switches, as in A2's config

		assertEquals(2, problems.size(), problems.toString());
		assertTrue(problems.get(0).contains("CHECKPOINT_LAND_USE_FILE is a relative path"), problems.get(0));
		assertTrue(problems.get(1).contains("DATA_DIR is a relative path"), problems.get(1));
	}

	@Test
	void theLaunchersKeysForEachRunType() throws Exception {
		LauncherFixture f = new LauncherFixture(tempDir.resolve("world"));
		MasterConfig calibration = MasterConfig.read(f.master("c.yaml", f.calibrationMaster("c", "")));
		MasterConfig scenario = MasterConfig.read(f.master("s.yaml", f.scenarioMaster("s", tempDir, 1993,
				"wait_timeout_minutes: 30\n")));
		CalibrationCheck.Calibration c = new CalibrationCheck.Calibration(tempDir, 1990, 2005,
				tempDir.resolve("production"), tempDir.resolve("plum/output"), null, null);

		Map<String, String> cal = PlumRunConfig.values(calibration, new RunFolder(calibration.runFolder()), 1990, null);
		Map<String, String> sce = PlumRunConfig.values(scenario, new RunFolder(scenario.runFolder()), 1990, c);

		assertEquals(List.of("IS_CALIBRATION_RUN", "START_TIMESTEP", "END_TIMESTEP", "OUTPUT_DIR",
				"CRAFTY_PRODUCTION_DIR", "LPJG_MONITOR_TIMEOUT"), List.copyOf(cal.keySet()));
		assertEquals("40", cal.get("END_TIMESTEP"));
		assertEquals("true", cal.get("IS_CALIBRATION_RUN"));
		assertEquals("false", sce.get("IS_CALIBRATION_RUN"));
		assertEquals("0", sce.get("START_TIMESTEP"), "PLUM's scenario starts at 1990 (48d rev. 4)");
		assertEquals("3", sce.get("END_TIMESTEP"), "to CRAFTY's last year, 1993 (Q3 a)");
		assertEquals(LauncherFixture.slashes(tempDir.resolve("plum/output")), sce.get("CALIB_DIR"));
		assertEquals("1800", sce.get("LPJG_MONITOR_TIMEOUT"));
		assertTrue(PlumRunConfig.LAUNCHER_KEYS.containsAll(sce.keySet()));
	}
}
