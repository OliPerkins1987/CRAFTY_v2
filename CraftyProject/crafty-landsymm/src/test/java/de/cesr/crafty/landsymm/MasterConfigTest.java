package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MasterConfigTest {

	@TempDir
	Path tempDir;

	private LauncherFixture f;

	@BeforeEach
	void setUp() throws Exception {
		f = new LauncherFixture(tempDir);
	}

	private MasterConfig read(String yaml) throws Exception {
		return MasterConfig.read(f.master("m.yaml", yaml));
	}

	private String problems(String yaml) throws Exception {
		CouplingInputException e = assertThrows(CouplingInputException.class, () -> read(yaml));
		return e.getMessage();
	}

	@Test
	void aCalibrationRunReadsWithItsDefaults() throws Exception {
		MasterConfig m = read(f.calibrationMaster("calib1990_v1", ""));

		assertTrue(m.calibrationRun());
		assertEquals("calib1990_v1", m.runName());
		assertEquals(f.outputRoot.resolve("calib1990_v1"), m.runFolder());
		assertEquals(LauncherFixture.CALIBRATION_STEPS, m.calibrationSteps());
		assertEquals(120, m.waitTimeoutMinutes());
		assertEquals(List.of("prices_received", "production_sent", "export_basis", "trade_capitals",
				"ungrazed_grass", "stock_costs", "nes_prices"), m.outputs(), "every file by default");
		assertTrue(m.coupling().isEmpty());
		assertEquals(f.jar, m.craftyJar());
		assertEquals(f.gamsDir.resolve("apifiles/Java/api/GAMSJavaAPI.jar"), m.gamsJar());
		assertEquals(List.of(), m.craftyJvmArgs());
	}

	@Test
	void aScenarioRunReadsItsCouplingAndOutputs() throws Exception {
		MasterConfig m = read(f.scenarioMaster("hist_seq", tempDir.resolve("calib"), 1993,
				"wait_timeout_minutes: 30\n"
						+ "coupling:\n  plum_coupling_mode: overlapping\n  dynamic_trade_capitals: true\n"
						+ "  trade_capital_slope: 6\n  trade_capital_averaging_years: 3\n"
						+ "outputs:\n  export_basis: false\n  prices_received: true\n"));

		assertFalse(m.calibrationRun());
		assertEquals(tempDir.resolve("calib"), m.calibrationRunFolder());
		assertEquals(1993, m.endYear());
		assertEquals(30, m.waitTimeoutMinutes());
		assertEquals("overlapping", m.coupling().get("plum_coupling_mode"));
		assertEquals(true, m.coupling().get("dynamic_trade_capitals"));
		assertEquals(6, m.coupling().get("trade_capital_slope"), "a whole number is still a number");
		assertEquals(List.of("prices_received", "production_sent", "trade_capitals", "ungrazed_grass", "stock_costs",
				"nes_prices"), m.outputs());
		assertEquals(List.of("export_basis"), m.outputsOff());
		assertEquals(0, m.calibrationSteps());
	}

	@Test
	void relativePathsAreRelativeToTheMasterYaml() throws Exception {
		MasterConfig m = read(f.calibrationMaster("c", "").replace(LauncherFixture.q(f.outputRoot), "runs")
				.replace(LauncherFixture.q(f.craftyBase), "crafty-config.yaml"));

		assertEquals(tempDir.resolve("runs").toAbsolutePath().normalize(), m.outputRoot());
		assertEquals(f.craftyBase.toAbsolutePath().normalize(), m.craftyConfig());
	}

	@Test
	void missingAndUnknownKeysAreAllListed() throws Exception {
		String message = problems("run_name: x\nis_calibration_run: true\nrun_nmae: y\ncrafty:\n  heap: 1g\n  jarr: z\n"
				+ "plum:\n  config: p\n  classes: c\n  gams_dir: g\n  heap: 1g\n");

		assertTrue(message.contains("output_root is missing"), message);
		assertTrue(message.contains("[run_nmae]"), message);
		assertTrue(message.contains("[crafty.jarr]"), message);
		assertTrue(message.contains("crafty.config is missing"), message);
		assertTrue(message.contains("plum.calibration_steps is missing"), message);
	}

	@Test
	void theRunNameMustBeAPlainFolderName() throws Exception {
		for (String name : List.of("\"a/b\"", "\"..\"", "\"two words\"")) {
			assertTrue(problems(f.calibrationMaster(name, "")).contains("run_name must be a folder name"), name);
		}
	}

	@Test
	void aCalibrationRunTakesNoScenarioKeys() throws Exception {
		String message = problems(f.calibrationMaster("c", "end_year: 2000\ncoupling:\n  plum_consumption: false\n"));

		assertTrue(message.contains("end_year is only for a scenario run"), message);
		assertTrue(message.contains("coupling is only for a scenario run"), message);
	}

	@Test
	void aScenarioRunNeedsItsCalibrationAndEndYearAndNoCalibrationSteps() throws Exception {
		String yaml = f.scenarioMaster("s", tempDir, 2000, "").replaceAll("(?m)^calibration_run: .*\n", "")
				.replace("end_year: 2000\n", "").replace("\nplum:\n", "\nplum:\n  calibration_steps: 5\n");

		String message = problems(yaml);

		assertTrue(message.contains("calibration_run is missing"), message);
		assertTrue(message.contains("end_year is missing"), message);
		assertTrue(message.contains("plum.calibration_steps is only for a calibration run"), message);
	}

	@Test
	void couplingSettingsAreCheckedAsCoreChecksThem() throws Exception {
		String message = problems(f.scenarioMaster("s", tempDir, 2000, "coupling:\n"
				+ "  plum_coupling_mode: sequental\n"
				+ "  plum_consumption: yes please\n"
				+ "  trade_capital_slope: 0\n"
				+ "  trade_capital_averaging_years: 2.5\n"
				+ "  crafty_production_path: x\n"
				+ "  plum_coupling_outputs: [prices_received]\n"));

		assertTrue(message.contains("coupling.plum_coupling_mode must be one of [sequential, overlapping]"), message);
		assertTrue(message.contains("coupling.plum_consumption must be true or false"), message);
		assertTrue(message.contains("coupling.trade_capital_slope must be a number more than 0"), message);
		assertTrue(message.contains("coupling.trade_capital_averaging_years must be a whole number"), message);
		assertTrue(message.contains("coupling.crafty_production_path isn't one of the coupler's settings"), message);
		assertTrue(message.contains("The files to write go in the outputs block"), message);
	}

	@Test
	void theNesSettingsAreCouplingSettings() throws Exception {
		MasterConfig m = read(f.scenarioMaster("nes", tempDir.resolve("calib"), 1993, "coupling:\n"
				+ "  dynamic_nes_prices: true\n  dynamic_biodiversity_price: true\n"
				+ "  biodiversity_metric: area\n  biodiversity_allocation_capital: bii\n"));

		assertEquals(true, m.coupling().get("dynamic_nes_prices"));
		assertEquals(true, m.coupling().get("dynamic_biodiversity_price"));
		assertEquals("area", m.coupling().get("biodiversity_metric"));
		assertEquals("bii", m.coupling().get("biodiversity_allocation_capital"));

		String message = problems(f.scenarioMaster("s", tempDir, 2000, "coupling:\n"
				+ "  dynamic_nes_prices: yes please\n"
				+ "  biodiversity_metric: bii\n"
				+ "  biodiversity_allocation_capital: \" \"\n"));
		assertTrue(message.contains("coupling.dynamic_nes_prices must be true or false"), message);
		assertTrue(message.contains("coupling.biodiversity_metric must be one of [area]"), message);
		assertTrue(message.contains("coupling.biodiversity_allocation_capital must name the capital"), message);
	}

	@Test
	void outputsNameOnlyTheCouplersYearlyFiles() throws Exception {
		String message = problems(f.calibrationMaster("c", "outputs:\n  price_factors: false\n  export_basis: no\n"
				+ "  ungrazed_grass: 0\n"));

		assertTrue(message.contains("outputs.price_factors isn't one of the coupler's yearly files"), message);
		assertTrue(message.contains("always written"), message);
		assertTrue(message.contains("outputs.ungrazed_grass must be true or false"), message);
		assertFalse(message.contains("outputs.export_basis"), "YAML reads no as false");
	}

	@Test
	void theLauncherOwnsTheHeapAndClassPath() throws Exception {
		String yaml = f.calibrationMaster("c", "").replace("  heap: 1g\n  jar:",
				"  heap: 16 GB\n  jvm_args: [\"-Xmx8g\", \"-cp\", \"-Xlog:gc:file=gc.log\"]\n  jar:");

		String message = problems(yaml);

		assertTrue(message.contains("crafty.heap must be a size as for -Xmx"), message);
		assertTrue(message.contains("crafty.jvm_args can't hold -Xmx8g"), message);
		assertTrue(message.contains("crafty.jvm_args can't hold -cp"), message);
		assertFalse(message.contains("-Xlog"), "other JVM arguments are fine");
	}

	@Test
	void notYamlOrNotAMappingStops() throws Exception {
		assertTrue(problems("run_name: [unclosed\n").contains("isn't valid YAML"));
		assertTrue(problems("- a list\n").contains("must be a YAML mapping"));
		CouplingInputException e = assertThrows(CouplingInputException.class,
				() -> MasterConfig.read(tempDir.resolve("missing.yaml")));
		assertTrue(e.getMessage().contains("Can't read the master YAML"));
		assertFalse(Files.exists(tempDir.resolve("missing.yaml")));
	}
}
