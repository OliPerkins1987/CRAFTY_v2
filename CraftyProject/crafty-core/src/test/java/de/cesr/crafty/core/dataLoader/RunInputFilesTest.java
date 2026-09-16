package de.cesr.crafty.core.dataLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunInputFilesTest {

	@TempDir
	Path tempDir;

	@AfterEach
	void clearRunFolder() {
		// The run folder is static, shared by every test in this JVM.
		RunInputFiles.clearRunFolder();
	}

	@Test
	void withoutARunFolder_theOriginalIsRead() {
		Path original = tempDir.resolve("worlds").resolve("capitals").resolve("capitals_2020.csv");

		assertFalse(RunInputFiles.isUsingRunFolder());
		assertSame(original, RunInputFiles.resolve(original));
		assertNull(RunInputFiles.resolve(null));
	}

	@Test
	void aFileInsideTheProjectKeepsItsRelativePath() {
		Path project = tempDir.resolve("project");
		Path run = tempDir.resolve("output").resolve(RunInputFiles.RUN_FOLDER_NAME);
		Path relative = Path.of("worlds", "capitals", "ssp126", "EU_capitals_ssp126_2026.csv");

		Path swapped = RunInputFiles.runFolderLocation(project.resolve(relative), project, run);

		assertEquals(run.resolve(relative), swapped);
	}

	@Test
	void aFileOutsideTheProjectKeepsItsFullPathUnderExternal() {
		Path project = tempDir.resolve("project");
		Path run = tempDir.resolve("run");
		Path original = tempDir.resolve("elsewhere").resolve("costs").resolve("Nfert_costs_2026.csv");

		Path swapped = RunInputFiles.runFolderLocation(original, project, run);

		assertTrue(swapped.startsWith(run.resolve(RunInputFiles.EXTERNAL_FOLDER_NAME)), "Got: " + swapped);
		assertTrue(swapped.endsWith(Path.of("elsewhere", "costs", "Nfert_costs_2026.csv")), "Got: " + swapped);
	}

	@Test
	void withNoProjectFolderEveryFileCountsAsExternal() {
		Path run = tempDir.resolve("run");
		Path original = tempDir.resolve("capitals_2026.csv");

		Path swapped = RunInputFiles.runFolderLocation(original, null, run);

		assertTrue(swapped.startsWith(run.resolve(RunInputFiles.EXTERNAL_FOLDER_NAME)), "Got: " + swapped);
		assertTrue(swapped.endsWith("capitals_2026.csv"), "Got: " + swapped);
	}

	@Test
	void aDateStampedOutputFolderDoesNotMixUpYears() {
		// Every path under this output folder contains "_2026", which is why the run
		// folder is never searched by name. The path swap maps each year's file to its own.
		Path project = tempDir.resolve("project");
		Path run = tempDir.resolve("Default_Run_Output_2026_09_15_12_34").resolve(RunInputFiles.RUN_FOLDER_NAME);
		Path capitals = project.resolve("worlds").resolve("capitals");

		Path year2020 = RunInputFiles.runFolderLocation(capitals.resolve("capitals_2020.csv"), project, run);
		Path year2026 = RunInputFiles.runFolderLocation(capitals.resolve("capitals_2026.csv"), project, run);

		assertEquals("capitals_2020.csv", year2020.getFileName().toString());
		assertEquals("capitals_2026.csv", year2026.getFileName().toString());
		assertNotEquals(year2020, year2026);
	}

	@Test
	void resolveUsesTheRunFolderOnceSetAndStopsWhenCleared() {
		Path original = tempDir.resolve("in").resolve("capitals_2020.csv");

		RunInputFiles.useRunFolder(tempDir.resolve("run"));
		Path swapped = RunInputFiles.resolve(original);

		assertTrue(RunInputFiles.isUsingRunFolder());
		assertTrue(swapped.startsWith(RunInputFiles.getRunFolder()), "Got: " + swapped);

		RunInputFiles.clearRunFolder();
		assertSame(original, RunInputFiles.resolve(original));
	}

	@Test
	void resolveForReadingReturnsReactsVersionOfAFileReactWrites() throws IOException {
		// The missing-file case stops the run with LOGGER.fatal, which exits the JVM,
		// so only the file-present case can be tested here.
		Path original = tempDir.resolve("in").resolve("capitals_2020.csv");
		RunInputFiles.useRunFolder(tempDir.resolve("run"));
		Path runVersion = RunInputFiles.resolve(original);
		Files.createDirectories(runVersion.getParent());
		Files.writeString(runVersion, "X,Y\n");

		assertEquals(runVersion, RunInputFiles.resolveForReading(original, true));
	}

	@Test
	void resolveForReadingReturnsTheOriginalOfAFileReactDoesNotWrite() {
		// For example the Nfert costs when reactive_fertilizer is off: there is no
		// version in the run folder, and none is expected.
		Path original = tempDir.resolve("in").resolve("Nfert_costs_2020.csv");
		RunInputFiles.useRunFolder(tempDir.resolve("run"));

		assertSame(original, RunInputFiles.resolveForReading(original, false));
	}
}
