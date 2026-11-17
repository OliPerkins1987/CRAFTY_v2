package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunRecordTest {

	@TempDir
	Path tempDir;

	@Test
	void whatIsWrittenIsReadBack() {
		RunRecord r = new RunRecord().put("run_name", "calib1990_v1").put("status", "running");
		r.section("crafty").put("start_year", 1990);
		r.section("crafty").put("jvm_args", List.of("-Xlog:gc"));
		r.write(tempDir);

		RunRecord read = RunRecord.read(tempDir);

		assertEquals("calib1990_v1", read.text("run_name"));
		assertEquals(1990, read.number("crafty", "start_year"));
		assertEquals(List.of("-Xlog:gc"), read.get("crafty", "jvm_args"));
		assertNull(read.get("crafty", "missing"));
		assertNull(read.get("run_name", "not a block"));
	}

	@Test
	void itIsReplacedThroughATemporaryFileWithAHeader() throws Exception {
		new RunRecord().put("status", "running").write(tempDir);
		new RunRecord().put("status", "complete").write(tempDir);

		assertEquals("complete", RunRecord.read(tempDir).text("status"));
		assertFalse(Files.exists(tempDir.resolve("run.yaml.tmp")));
		assertTrue(Files.readString(tempDir.resolve("run.yaml")).startsWith("# Written by LandSymmLauncher"));
	}

	@Test
	void pathsInsideTheRunOrItsJarsFolderAreRelative() {
		Path run = tempDir.resolve("runs/calib");

		assertEquals("plum/output", RunRecord.pathText(run, run.resolve("plum").resolve("output")));
		assertEquals("../jars/crafty-landsymm-abc.jar",
				RunRecord.pathText(run, tempDir.resolve("runs/jars/crafty-landsymm-abc.jar")));
		assertEquals(LauncherFixture.slashes(tempDir.resolve("elsewhere/config.yaml")),
				RunRecord.pathText(run, tempDir.resolve("elsewhere/config.yaml")));
		assertEquals(run.resolve("plum/output").toAbsolutePath().normalize(), RunRecord.resolve(run, "plum/output"));
	}

	@Test
	void aMissingOrBrokenRecordStops() throws Exception {
		assertThrows(CouplingInputException.class, () -> RunRecord.read(tempDir));
		Files.writeString(tempDir.resolve("run.yaml"), "just text");
		assertThrows(CouplingInputException.class, () -> RunRecord.read(tempDir));
	}
}
