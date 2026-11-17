package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A calibration's files, made by hand from the coupler's test extracts: CRAFTY's 1990 production for three
 * countries, PLUM's 2029-2030 rows for the same three, with 2030 as the final year.
 */
class CalibrationCheckTest {

	@TempDir
	Path tempDir;

	private Path production;
	private Path plumOutput;

	@BeforeEach
	void setUp() throws Exception {
		production = tempDir.resolve("production");
		plumOutput = tempDir.resolve("plum/output");
		Files.createDirectories(production.resolve("1990"));
		Files.copy(LauncherFixture.resource("production_1990_extract/production.csv"),
				production.resolve("1990/production.csv"));
		Files.writeString(production.resolve("1990/done"), "");
		Files.createDirectories(plumOutput);
		try (Stream<Path> files = Files.list(LauncherFixture.resource("plum_all1990_extract"))) {
			for (Path file : files.toList()) {
				Files.copy(file, plumOutput.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		for (String name : CalibrationCheck.PLUM_STATE_FILES) {
			Files.writeString(plumOutput.resolve(name), "");
		}
	}

	@Test
	void aCompleteCalibrationHasNoProblems() {
		assertEquals(List.of(), CalibrationCheck.fileProblems(production, plumOutput, 1990, 2030));
	}

	@Test
	void eachMissingStateFileIsListed() throws Exception {
		Files.delete(plumOutput.resolve("demandManager.ser"));
		Files.delete(plumOutput.resolve("calib_config.properties"));

		List<String> problems = CalibrationCheck.fileProblems(production, plumOutput, 1990, 2030);

		assertEquals(2, problems.size(), problems.toString());
		assertTrue(problems.get(0).contains("demandManager.ser"));
		assertTrue(problems.get(1).contains("calib_config.properties"));
	}

	@Test
	void theFinalYearsRowsMustBeThere() {
		List<String> problems = CalibrationCheck.fileProblems(production, plumOutput, 1990, 2031);

		assertTrue(problems.get(0).contains("PLUM's final calibration year 2031 can't be read"), problems.toString());
	}

	@Test
	void craftysProductionMustBeCompleteWithItsDoneFile() throws Exception {
		Files.delete(production.resolve("1990/done"));

		List<String> problems = CalibrationCheck.fileProblems(production, plumOutput, 1990, 2030);

		assertEquals(1, problems.size(), problems.toString());
		assertTrue(problems.get(0).contains("production isn't complete"), problems.get(0));
	}

	@Test
	void theCountriesMustBeTheSameInBoth() throws Exception {
		Path file = production.resolve("1990/production.csv");
		Files.writeString(file, Files.readString(file).replace("Tanzania", "United Kingdom"));

		List<String> problems = CalibrationCheck.fileProblems(production, plumOutput, 1990, 2030);

		assertEquals(2, problems.size(), problems.toString());
		assertTrue(problems.get(0).contains("has countries CRAFTY's production doesn't: [Tanzania]"), problems.get(0));
		assertTrue(problems.get(1).contains("no rows for CRAFTY's countries [United Kingdom]"), problems.get(1));
	}

	@Test
	void aCalibrationRunIsReadFromItsRunYamlWithRelativePaths() throws Exception {
		Files.createDirectories(tempDir.resolve("crafty"));
		Files.writeString(tempDir.resolve("crafty/config.yaml"), "");
		Files.writeString(tempDir.resolve("plum/config.properties"), "");
		RunRecord r = new RunRecord().put("run_type", "calibration").put("status", "complete");
		r.section("crafty").put("start_year", 1990);
		r.section("crafty").put("config", "crafty/config.yaml");
		r.section("plum").put("config", "plum/config.properties");
		r.section("calibration").put("production", "production");
		r.section("calibration").put("plum_output", "plum/output");
		r.section("calibration").put("final_year", 2030);
		r.write(tempDir);

		CalibrationCheck.Calibration c = CalibrationCheck.ofRun(tempDir);

		assertEquals(1990, c.startYear());
		assertEquals(2030, c.finalYear());
		assertEquals(production.toAbsolutePath().normalize(), c.production());
		assertEquals(plumOutput.toAbsolutePath().normalize(), c.plumOutput());

		r.put("status", "failed").put("reason", "PLUM stopped with exit code 1").write(tempDir);
		CouplingInputException e = assertThrows(CouplingInputException.class, () -> CalibrationCheck.ofRun(tempDir));
		assertTrue(e.getMessage().contains("has status failed, not complete (PLUM stopped with exit code 1)"),
				e.getMessage());

		r.put("status", "complete").put("run_type", "scenario").write(tempDir);
		e = assertThrows(CouplingInputException.class, () -> CalibrationCheck.ofRun(tempDir));
		assertTrue(e.getMessage().contains("is a scenario run, not a calibration run"), e.getMessage());
	}
}
