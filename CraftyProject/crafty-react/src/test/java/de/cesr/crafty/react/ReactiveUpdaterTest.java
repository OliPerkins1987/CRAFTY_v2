package de.cesr.crafty.react;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.core.cli.Config;
import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.dataLoader.RunInputFiles;
import de.cesr.crafty.core.modelRunner.ModelRunner;
import de.cesr.crafty.core.modelRunner.ModelState;
import de.cesr.crafty.core.updaters.CapitalUpdater;
import de.cesr.crafty.core.updaters.ProductionCostUpdater;
import de.cesr.crafty.core.updaters.Timestep;

class ReactiveUpdaterTest {

	@TempDir
	Path tempDir;

	private Path capitals;
	private Path intensityCosts;
	private Config originalConfig;

	@BeforeEach
	void setUp() throws IOException {
		originalConfig = ConfigLoader.config;
		capitals = tempDir.resolve("in").resolve("capitals_2020.csv");
		intensityCosts = tempDir.resolve("in").resolve("Intensity_costs_2020.csv");
		Files.createDirectories(capitals.getParent());
		Files.writeString(capitals, "X,Y,capi1\n0,0,1.5\n");
		Files.writeString(intensityCosts, "X,Y,AFT1\n0,0,42.0\n");
		Timestep.setCurrentYear(2020);
	}

	@AfterEach
	void restoreStatics() {
		RunInputFiles.clearRunFolder();
		ConfigLoader.config = originalConfig;
	}

	// ---- How crafty-core finds and creates this class ----

	@Test
	void theClassNameCraftyCoreUsesIsThisClass() {
		// crafty-core names this class in a text string. A rename here would compile
		// fine and only fail when a react run starts, so the name is checked.
		assertEquals(ModelRunner.REACTIVE_UPDATER_CLASS, ReactiveUpdater.class.getName());
	}

	@Test
	void craftyCoreCanCreateIt() throws Exception {
		// crafty-core needs a model step with a public no-argument constructor.
		assertTrue(ModelState.class.isAssignableFrom(ReactiveUpdater.class));
		assertTrue(Modifier.isPublic(ReactiveUpdater.class.getDeclaredConstructor().getModifiers()));
	}

	@Test
	void inRunFolderMode_creatingItPointsTheModelAtTheRunFolder() {
		ConfigLoader.config = new Config();
		ConfigLoader.config.reactive_afts = true;
		ConfigLoader.config.reactive_overwrite_inputs = false;
		ConfigLoader.config.output_folder_name = tempDir.resolve("output").toString();

		new ReactiveUpdater();

		assertEquals(tempDir.resolve("output").resolve(RunInputFiles.RUN_FOLDER_NAME).toAbsolutePath().normalize(),
				RunInputFiles.getRunFolder());
	}

	@Test
	void inOverwriteMode_creatingItLeavesTheModelReadingTheOriginals() {
		ConfigLoader.config = new Config();
		ConfigLoader.config.reactive_afts = true;
		ConfigLoader.config.reactive_overwrite_inputs = true;
		ConfigLoader.config.output_folder_name = tempDir.resolve("output").toString();

		new ReactiveUpdater();

		assertFalse(RunInputFiles.isUsingRunFolder());
	}

	// ---- Which files react writes ----

	@SuppressWarnings("unchecked")
	private static Map<Integer, Path> capitalsFilesByYear() throws Exception {
		Field field = CapitalUpdater.class.getDeclaredField("capitals_directory");
		field.setAccessible(true);
		return (Map<Integer, Path>) field.get(null);
	}

	@Test
	void reactWritesTheCapitalsAndOnlyTheReactiveCostTypes() throws Exception {
		Path nfertCosts = tempDir.resolve("in").resolve("Nfert_costs_2020.csv");
		Path stockingCosts = tempDir.resolve("in").resolve("stocking_costs_2020.csv");
		Map<String, Path> costFiles = new LinkedHashMap<>();
		costFiles.put(ProductionCostUpdater.NFERT_COSTS, nfertCosts);
		costFiles.put(ProductionCostUpdater.INTENSITY_COSTS, intensityCosts);
		costFiles.put(ProductionCostUpdater.STOCKING_COSTS, stockingCosts);
		ProductionCostUpdater costUpdater = mock(ProductionCostUpdater.class);
		when(costUpdater.getSpatialCostPaths(2020)).thenReturn(costFiles);

		ProductionCostUpdater originalCostUpdater = ModelRunner.productionCostUpdater;
		Map<Integer, Path> capitalsByYear = capitalsFilesByYear();
		Path previousCapitals = capitalsByYear.put(2020, capitals);
		try {
			ModelRunner.productionCostUpdater = costUpdater;
			ConfigLoader.config = new Config();
			ConfigLoader.config.reactive_afts = true;

			assertEquals(List.of(), ReactiveUpdater.filesReactWrites(2020),
					"No reactive element: react writes nothing, not even capitals");

			ConfigLoader.config.reactive_other_intensity = true;
			assertEquals(List.of(capitals, intensityCosts), ReactiveUpdater.filesReactWrites(2020));

			ConfigLoader.config.reactive_fertilizer = true;
			assertEquals(List.of(capitals, nfertCosts, intensityCosts), ReactiveUpdater.filesReactWrites(2020),
					"Each cost file follows its own element switch");
		} finally {
			ModelRunner.productionCostUpdater = originalCostUpdater;
			if (previousCapitals == null) {
				capitalsByYear.remove(2020);
			} else {
				capitalsByYear.put(2020, previousCapitals);
			}
		}
	}

	// ---- The phase 0 pass-through ----

	@Test
	void runFolderMode_writesAnUnchangedCopyOfEachFileWhereTheModelWillReadIt() throws IOException {
		RunInputFiles.useRunFolder(tempDir.resolve("run"));

		new ReactiveUpdater(year -> List.of(capitals, intensityCosts)).step();

		for (Path original : List.of(capitals, intensityCosts)) {
			Path runVersion = RunInputFiles.resolve(original);
			assertTrue(Files.exists(runVersion), "Missing " + runVersion);
			assertArrayEquals(Files.readAllBytes(original), Files.readAllBytes(runVersion),
					"Phase 0 is a pass-through: the run folder's version must match the original");
		}
	}

	@Test
	void overwriteMode_leavesTheOriginalsAsTheyAre() throws IOException {
		byte[] capitalsBefore = Files.readAllBytes(capitals);
		byte[] costsBefore = Files.readAllBytes(intensityCosts);

		new ReactiveUpdater(year -> List.of(capitals, intensityCosts)).step();

		assertArrayEquals(capitalsBefore, Files.readAllBytes(capitals));
		assertArrayEquals(costsBefore, Files.readAllBytes(intensityCosts));
		assertFalse(Files.exists(tempDir.resolve("run")), "Overwrite mode must not create a run folder");
	}

	@Test
	void eachYearIsWrittenOnlyOnce() {
		// Year zero runs during initialisation, and must not be written again if stepped again.
		RunInputFiles.useRunFolder(tempDir.resolve("run"));
		AtomicInteger lookups = new AtomicInteger();
		ReactiveUpdater updater = new ReactiveUpdater(year -> {
			lookups.incrementAndGet();
			return List.of(capitals);
		});

		updater.step();
		updater.step();
		assertEquals(1, lookups.get(), "A second step in the same year must do nothing");

		Timestep.setCurrentYear(2021);
		updater.step();
		assertEquals(2, lookups.get(), "A new year must be written");
	}
}
