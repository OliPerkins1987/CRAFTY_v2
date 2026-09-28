package de.cesr.crafty.core.updaters;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.core.ToyData;
import de.cesr.crafty.core.cli.Config;
import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.crafty.Cell;
import de.cesr.crafty.core.dataLoader.ProjectLoader;
import de.cesr.crafty.core.dataLoader.land.CellsLoader;
import de.cesr.crafty.core.utils.file.CsvTools;

/**
 * Unit tests for {@link CapitalUpdater}.
 *
 * Focus: - capitals list initialisation from a small "capitals metadata" CSV -
 * capitals_directory map wiring when a CAPTIALS_directory folder is provided
 *
 * NOTE: You may need to adapt the way the metadata path is set on ProjectLoader
 * depending on your actual API (e.g. setCapitalsMetadata(Path) or similar).
 */
class CapitalUpdaterTest {

	@TempDir
	Path tempDir;

	@BeforeEach
	void setUp() throws IOException {

		ToyData toy = new ToyData();
		toy.resetStaticState(tempDir);
		if (ConfigLoader.config == null) {
			ConfigLoader.config = new Config();
		}
		// Small time horizon for the tests
		Timestep.setStartYear(2000);
		Timestep.setEndtYear(2010);

		Path worlds = tempDir.resolve("worlds");
		Path capitals = worlds.resolve("capitals");
		Path scenario = capitals.resolve("TestScenario");
		Files.createDirectories(scenario);
		for (int i = Timestep.getStartYear(); i < Timestep.getEndtYear(); i++) {
			// generate a capital file
			Path p = Paths.get(scenario + File.separator + "capitals_" + i + ".csv");
			CsvTools.writeCSVfile(new String[0][0], p);
		}
//		System.out.println(capitals);
//		System.out.println(scenario);
//		System.out.println("@@@    ");
//		PathTools.findAllFilePaths(scenario);
	}
	
    @AfterEach
    void tearDown() {
    	CustomLogger.shutdownRunFileLoggers();
    }

	@Test
	void constructor_readsCapitalsListFromMetadata() throws Exception {
		List<String> capitals = CapitalUpdater.getCapitalsList();

		assertEquals(3, capitals.size(), "Capitals list should contain 3 entries from metadata");
		assertTrue(capitals.contains("capi1"));
		assertTrue(capitals.contains("capi3"));
	}

	@Test
	void isSuitability_returnsTrueForSuitabilityType() {
		CapitalUpdater.getCapitalTypes().clear();
		CapitalUpdater.getCapitalTypes().put("ExtC3C_suit", "Suitability");
		CapitalUpdater.getCapitalTypes().put("soil_quality", "Capital");

		assertTrue(CapitalUpdater.isSuitability("ExtC3C_suit"));
		assertFalse(CapitalUpdater.isSuitability("soil_quality"));
	}

	@Test
	void isSuitability_returnsTrueForUnknownCapital() {
		CapitalUpdater.getCapitalTypes().clear();

		assertTrue(CapitalUpdater.isSuitability("never_seen_before"));
	}

	@Test
	void capitalTypes_storageWorksCorrectly() {
		CapitalUpdater.getCapitalTypes().clear();
		CapitalUpdater.getCapitalTypes().put("capi1", "Capital");
		CapitalUpdater.getCapitalTypes().put("capi2", "Suitability");
		CapitalUpdater.getCapitalTypes().put("capi3", "Suitability");

		assertEquals("Capital", CapitalUpdater.getCapitalTypes().get("capi1"));
		assertEquals("Suitability", CapitalUpdater.getCapitalTypes().get("capi2"));
		assertFalse(CapitalUpdater.isSuitability("capi1"));
		assertTrue(CapitalUpdater.isSuitability("capi2"));
		assertTrue(CapitalUpdater.isSuitability("capi3"));
	}

	@SuppressWarnings("unchecked")
	private static Map<Integer, Path> capitalsFilesByYear() throws Exception {
		Field field = CapitalUpdater.class.getDeclaredField("capitals_directory");
		field.setAccessible(true);
		return (Map<Integer, Path>) field.get(null);
	}

	@Test
	void step_readsTheFileFoundAtStartupWhateverTheReactSwitches() throws Exception {
		// CRAFTY-react no longer hands its values over through files: it puts them into the cells after this
		// step has loaded the year's file (phase 5). So the file read is always the one found at startup.
		int year = 2003;
		CellsLoader.hashCell.clear();
		Cell cell = new Cell(0, 0);
		CellsLoader.hashCell.put("0,0", cell);

		// An original capitals file inside the project folder, as if found at startup.
		Path original = ProjectLoader.getProjectPath().resolve("worlds").resolve("capitals")
				.resolve("TestScenario_" + year + ".csv");
		Files.createDirectories(original.getParent());
		Files.writeString(original, "X,Y,capi1,capi2,capi3\n0,0,1.0,1.0,1.0\n");
		Map<Integer, Path> filesByYear = capitalsFilesByYear();
		Path previous = filesByYear.put(year, original);

		// The config object is shared by every test in this JVM, so the switches are restored.
		boolean reactiveAfts = ConfigLoader.config.reactive_afts;
		boolean reactiveFertilizer = ConfigLoader.config.reactive_fertilizer;

		// The constructor needs full project metadata, so step() is run on an instance built without it.
		CapitalUpdater updater = mock(CapitalUpdater.class, CALLS_REAL_METHODS);
		Timestep.setCurrentYear(year);
		try {
			updater.step();
			assertEquals(1.0, cell.getCapitals().get("capi1"), "React off: the original is read");

			ConfigLoader.config.reactive_afts = true;
			ConfigLoader.config.reactive_fertilizer = true;
			cell.getCapitals().put("capi1", 0.0);
			updater.step();
			assertEquals(1.0, cell.getCapitals().get("capi1"), "React on: still the original");
		} finally {
			ConfigLoader.config.reactive_afts = reactiveAfts;
			ConfigLoader.config.reactive_fertilizer = reactiveFertilizer;
			if (previous == null) {
				filesByYear.remove(year);
			} else {
				filesByYear.put(year, previous);
			}
			CellsLoader.hashCell.clear();
		}
	}
}
