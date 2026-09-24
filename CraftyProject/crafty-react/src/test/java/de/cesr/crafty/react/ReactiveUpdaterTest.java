package de.cesr.crafty.react;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import de.cesr.crafty.core.crafty.Aft;
import de.cesr.crafty.core.crafty.Cell;
import de.cesr.crafty.core.crafty.Region;
import de.cesr.crafty.core.dataLoader.ProjectLoader;
import de.cesr.crafty.core.dataLoader.afts.AFTsLoader;
import de.cesr.crafty.core.dataLoader.land.CellsLoader;
import de.cesr.crafty.core.dataLoader.serivces.ServiceSet;
import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.dataLoader.RunInputFiles;
import de.cesr.crafty.core.modelRunner.ModelRunner;
import de.cesr.crafty.core.modelRunner.ModelState;
import de.cesr.crafty.core.updaters.CapitalUpdater;
import de.cesr.crafty.core.updaters.ProductionCostUpdater;
import de.cesr.crafty.core.updaters.Timestep;
import de.cesr.crafty.react.data.CoreFacts;
import de.cesr.crafty.react.data.ReactConfig;
import de.cesr.crafty.react.data.ReactElement;
import de.cesr.crafty.react.data.ReactInputException;
import de.cesr.crafty.react.data.ReactInputs;
import de.cesr.crafty.react.data.ReactToyData;
import de.cesr.crafty.react.data.ReactYearData;
import de.cesr.crafty.react.decisions.CropDecisions;
import de.cesr.crafty.react.decisions.DecisionUnits;
import de.cesr.crafty.react.decisions.PastureDecisions;
import de.cesr.crafty.react.decisions.YearPrices;
import de.cesr.crafty.react.science.CropSurfaces;

class ReactiveUpdaterTest {

	@TempDir
	Path tempDir;

	private Path capitals;
	private Path intensityCosts;
	private Config originalConfig;
	private ProductionCostUpdater originalCostUpdater;

	@BeforeEach
	void setUp() throws IOException {
		originalConfig = ConfigLoader.config;
		originalCostUpdater = ModelRunner.productionCostUpdater;
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
		ModelRunner.productionCostUpdater = originalCostUpdater;
		AFTsLoader.getAftHash().clear();
		CellsLoader.hashCell.clear();
		CellsLoader.regions.clear();
	}

	/**
	 * Sets up core's state to match the toy project, so that the constructor's startup checks pass.
	 * The constructor reads core through CoreFacts: AFT metadata, cells and their regions, services,
	 * the years and the spatial cost files.
	 */
	private void setUpCoreForToyProject() throws Exception {
		ReactToyData.project(tempDir);
		ConfigLoader.config.project_path = tempDir.toString();
		ConfigLoader.config.reactive_fertilizer = true;
		ConfigLoader.config.reactive_irrigation = true;
		ConfigLoader.config.reactive_other_intensity = true;
		ConfigLoader.config.reactive_stocking = true;
		Timestep.setStartYear(2020);
		Timestep.setEndtYear(2021);

		AFTsLoader.getAftHash().clear();
		AFTsLoader.getAftHash().put("IntC3C_irrig", aft(200, 0.75, true, 0));
		AFTsLoader.getAftHash().put("ExtC3C", aft(100, 0.4, false, 0));
		AFTsLoader.getAftHash().put("IntP", aft(0, 1.5, false, 3.5));
		AFTsLoader.getAftHash().put("IntFodder", aft(200, 0.75, false, 0));
		AFTsLoader.getAftHash().put("AF", aft(0, 1.0, false, 0));
		AFTsLoader.getAftHash().put("Urban", aft(0, 1.0, false, 0));

		CellsLoader.hashCell.clear();
		CellsLoader.hashCell.put("1,1", cell(1, 1, "North"));
		CellsLoader.hashCell.put("1,2", cell(1, 2, "South"));
		CellsLoader.hashCell.put("2,1", cell(2, 1, "North"));
		CellsLoader.regions.clear();
		CellsLoader.regions.put("North", new Region("North"));
		CellsLoader.regions.put("South", new Region("South"));

		setStaticField(ProjectLoader.class, "scenario", "ssp126");
		setStaticField(ProjectLoader.class, "serviceMetadata", tempDir.resolve("csv/Services.csv"));
		setStaticField(ServiceSet.class, "servicesList", List.of("C3cereals", "Pasture", "Hardwood", "Carbon"));

		Map<String, Path> costPaths = new LinkedHashMap<>();
		costPaths.put(ProductionCostUpdater.NFERT_COSTS, tempDir.resolve("Nfert_costs.csv"));
		costPaths.put(ProductionCostUpdater.IRRIGATION_COSTS, tempDir.resolve("Irrigation_costs.csv"));
		costPaths.put(ProductionCostUpdater.INTENSITY_COSTS, tempDir.resolve("Intensity_costs.csv"));
		costPaths.put(ProductionCostUpdater.STOCKING_COSTS, tempDir.resolve("stocking_costs.csv"));
		ProductionCostUpdater costUpdater = mock(ProductionCostUpdater.class);
		when(costUpdater.getSpatialCostPaths(2020)).thenReturn(costPaths);
		when(costUpdater.getSpatialCostPaths(2021)).thenReturn(costPaths);
		ModelRunner.productionCostUpdater = costUpdater;
	}

	private static Aft aft(double nfertRate, double otherIntensity, boolean irrigated, double pastureProduction) {
		Aft aft = new Aft("x");
		aft.setNfertRate(nfertRate);
		aft.setOtherIntensity(otherIntensity);
		aft.setIrrigated(irrigated);
		aft.getProductivityLevel().put("Pasture", pastureProduction);
		return aft;
	}

	private static Cell cell(int x, int y, String regionName) {
		Cell cell = new Cell(x, y);
		cell.setCurrentRegion(regionName);
		return cell;
	}

	private static void setStaticField(Class<?> type, String name, Object value) throws Exception {
		Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		field.set(null, value);
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
	void inRunFolderMode_creatingItPointsTheModelAtTheRunFolder() throws Exception {
		ConfigLoader.config = new Config();
		ConfigLoader.config.reactive_afts = true;
		ConfigLoader.config.reactive_overwrite_inputs = false;
		ConfigLoader.config.output_folder_name = tempDir.resolve("output").toString();
		setUpCoreForToyProject();

		ReactiveUpdater updater = new ReactiveUpdater();

		assertEquals(tempDir.resolve("output").resolve(RunInputFiles.RUN_FOLDER_NAME).toAbsolutePath().normalize(),
				RunInputFiles.getRunFolder());
		assertNotNull(updater.getInputs(), "Creating it also checks the project and keeps what was loaded");
		assertEquals(3, updater.getInputs().checked().parameters().reactive().size());
	}

	@Test
	void inOverwriteMode_creatingItLeavesTheModelReadingTheOriginals() throws Exception {
		ConfigLoader.config = new Config();
		ConfigLoader.config.reactive_afts = true;
		ConfigLoader.config.reactive_overwrite_inputs = true;
		ConfigLoader.config.output_folder_name = tempDir.resolve("output").toString();
		setUpCoreForToyProject();

		new ReactiveUpdater();

		assertFalse(RunInputFiles.isUsingRunFolder());
	}

	@Test
	void aProjectReactCannotUseIsReportedWhenTheStepIsCreated() throws Exception {
		ConfigLoader.config = new Config();
		ConfigLoader.config.reactive_afts = true;
		setUpCoreForToyProject();
		// A cell the key does not list: the startup checks stop the run.
		CellsLoader.hashCell.put("9,9", cell(9, 9, "North"));

		ReactInputException e = assertThrows(ReactInputException.class,
				() -> ReactInputs.create(ReactConfig.defaults(), CoreFacts.fromCore()));

		assertTrue(e.getMessage().contains("9,9"), e.getMessage());
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

	// ---- loading each year (27c) ----

	@Test
	void eachStepLoadsItsYearOnce() {
		// The toy project is small, so this exercises the real loader rather than a stand-in.
		ReactToyData.project(tempDir);
		ReactInputs inputs = ReactInputs.create(ReactConfig.defaults(), ReactToyData.context(tempDir).build());
		ReactiveUpdater updater = new ReactiveUpdater(year -> List.of(), inputs);

		Timestep.setCurrentYear(2020);
		updater.step();
		assertEquals(2020, inputs.currentYear().year());
		assertEquals(1.82f, inputs.currentYear().crop("CerealsC3", "0")[0], 1e-5f);

		Timestep.setCurrentYear(2021);
		updater.step();
		assertEquals(2021, inputs.currentYear().year(), "The next year replaces the one before");
		assertSame(inputs, updater.getInputs());
	}

	@Test
	void withoutInputsTheStepStillPassesFilesThrough() {
		// Phase 0 behaviour: the pass-through does not depend on the loaded data.
		RunInputFiles.useRunFolder(tempDir.resolve("run"));

		new ReactiveUpdater(year -> List.of(capitals)).step();

		assertTrue(Files.exists(RunInputFiles.resolve(capitals)));
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

	// ---- deciding each year (29c) ----

	/** Writes the toy project and checks it, as react does when a run starts. */
	private ReactInputs toyInputs(ReactToyData.Context context) {
		ReactToyData.project(tempDir);
		return ReactInputs.create(ReactConfig.defaults(), context.build());
	}

	private ReactToyData.Context toyContext() {
		return ReactToyData.context(tempDir).prices((service, region, year) -> 300);
	}

	@Test
	void eachYearIsDecidedOnceSoTheSpinUpHappensOnce() {
		ReactiveUpdater updater = new ReactiveUpdater(year -> List.of(), toyInputs(toyContext()));

		Timestep.setCurrentYear(2020);
		updater.step(); // year zero, during initialisation
		double[] yearZero = updater.getCropDecisions().managements().get("IntC3C_irrig").nitrogen().clone();
		updater.step(); // the first scheduled step, for the same year
		assertEquals(2020, updater.getCropDecisions().lastYear());
		assertArrayEquals(yearZero, updater.getCropDecisions().managements().get("IntC3C_irrig").nitrogen(), 0,
				"the same year is not decided again");

		// The same as deciding 2020 once, on its own.
		ReactInputs fresh = ReactInputs.create(ReactConfig.defaults(), toyContext().build());
		DecisionUnits units = DecisionUnits.build(fresh.checked().cellKey(), fresh.context().regions());
		CropDecisions once = CropDecisions.create(fresh, units);
		ReactYearData data = fresh.forYear(2020);
		once.decide(data, CropSurfaces.fit(data),
				YearPrices.forYear(2020, once.servicesNeedingPrices(), units, fresh.context().prices()));
		assertArrayEquals(once.managements().get("IntC3C_irrig").nitrogen(), yearZero, 0);

		Timestep.setCurrentYear(2021);
		updater.step();
		assertEquals(2021, updater.getCropDecisions().lastYear());
		assertEquals(2021, updater.getInputs().currentYear().year());
	}

	@Test
	void thePastureStageIsDecidedOnceAYearSoItsSpinUpHappensOnce() {
		ReactiveUpdater updater = new ReactiveUpdater(year -> List.of(), toyInputs(toyContext()));

		Timestep.setCurrentYear(2020);
		updater.step(); // year zero, during initialisation
		double[] yearZero = updater.getPastureDecisions().managements().get("IntP").stocking().clone();
		updater.step(); // the first scheduled step, for the same year
		assertEquals(2020, updater.getPastureDecisions().lastYear());
		assertArrayEquals(yearZero, updater.getPastureDecisions().managements().get("IntP").stocking(), 0,
				"the same year is not decided again");

		// The same as deciding 2020 once, on its own.
		ReactInputs fresh = ReactInputs.create(ReactConfig.defaults(), toyContext().build());
		DecisionUnits units = DecisionUnits.build(fresh.checked().cellKey(), fresh.context().regions());
		PastureDecisions once = PastureDecisions.create(fresh, units);
		once.decide(fresh.forYear(2020),
				YearPrices.forYear(2020, once.servicesNeedingPrices(), units, fresh.context().prices()));
		assertArrayEquals(once.managements().get("IntP").stocking(), yearZero, 0);

		Timestep.setCurrentYear(2021);
		updater.step();
		assertEquals(2021, updater.getPastureDecisions().lastYear());
		assertEquals(2021, updater.getCropDecisions().lastYear(), "both stages decide each year");
	}

	@Test
	void stagesCanBePassedIn() {
		ReactInputs inputs = toyInputs(toyContext());
		DecisionUnits units = DecisionUnits.build(inputs.checked().cellKey(), inputs.context().regions());
		CropDecisions crops = CropDecisions.create(inputs, units);
		PastureDecisions pasture = PastureDecisions.create(inputs, units);
		ReactiveUpdater updater = new ReactiveUpdater(year -> List.of(), inputs, units, crops, pasture);

		updater.step();

		assertSame(crops, updater.getCropDecisions());
		assertSame(pasture, updater.getPastureDecisions());
		assertEquals(2020, crops.lastYear());
		assertEquals(2020, pasture.lastYear());
	}

	@Test
	void filesStillPassThroughUnchangedWhileTheStagesDecide() throws IOException {
		RunInputFiles.useRunFolder(tempDir.resolve("run"));
		ReactiveUpdater updater = new ReactiveUpdater(year -> List.of(capitals, intensityCosts), toyInputs(toyContext()));

		updater.step();

		assertEquals(2020, updater.getCropDecisions().lastYear());
		assertEquals(2020, updater.getPastureDecisions().lastYear());
		for (Path original : List.of(capitals, intensityCosts)) {
			assertArrayEquals(Files.readAllBytes(original), Files.readAllBytes(RunInputFiles.resolve(original)),
					"Nothing is written until phase 5");
		}
	}

	@Test
	void aYearThatCannotBeDecidedNamesTheProblem() {
		// step() hands this to LOGGER.fatal, which stops the run; decideYear is where it is raised.
		ReactiveUpdater updater = new ReactiveUpdater(year -> List.of(),
				toyInputs(ReactToyData.context(tempDir).prices((service, region, year) -> {
					throw new ReactInputException("no weight");
				})));

		ReactInputException e = assertThrows(ReactInputException.class, () -> updater.decideYear(2020));

		assertTrue(e.getMessage().contains("C3cereals") && e.getMessage().contains("2020")
				&& e.getMessage().contains("no weight"), e.getMessage());
	}

	@Test
	void aMissingPasturePriceNamesTheProblem() {
		ReactiveUpdater updater = new ReactiveUpdater(year -> List.of(),
				toyInputs(ReactToyData.context(tempDir).prices((service, region, year) -> {
					if (service.equals("Pasture")) {
						throw new ReactInputException("no weight");
					}
					return 300;
				})));

		ReactInputException e = assertThrows(ReactInputException.class, () -> updater.decideYear(2020));

		assertTrue(e.getMessage().contains("Pasture") && e.getMessage().contains("2020")
				&& e.getMessage().contains("no weight"), e.getMessage());
	}

	@Test
	void withOnlyStockingOnPastureIsDecidedAndCropsAreNot() {
		ReactiveUpdater updater = new ReactiveUpdater(year -> List.of(), toyInputs(ReactToyData.context(tempDir)
				.off(ReactElement.FERTILISER, ReactElement.IRRIGATION, ReactElement.OTHER_INTENSITY)));

		updater.step();

		assertEquals(2020, updater.getInputs().currentYear().year());
		assertTrue(updater.getCropDecisions().managements().isEmpty(), "phase 3 plan, Q6");
		assertEquals(null, updater.getCropDecisions().lastYear());
		assertEquals(2020, updater.getPastureDecisions().lastYear());
		assertEquals(List.of("IntP"), List.copyOf(updater.getPastureDecisions().managements().keySet()));
	}

	@Test
	void withOnlyFertiliserAndIrrigationOnCropsAreDecidedAndPastureIsNot() {
		ReactiveUpdater updater = new ReactiveUpdater(year -> List.of(), toyInputs(ReactToyData.context(tempDir)
				.off(ReactElement.OTHER_INTENSITY, ReactElement.STOCKING)));

		updater.step();

		assertEquals(2020, updater.getCropDecisions().lastYear());
		assertTrue(updater.getPastureDecisions().managements().isEmpty(), "phase 3 plan, Q6");
		assertEquals(null, updater.getPastureDecisions().lastYear());
	}

	@Test
	void withNothingToDecideOnlyTheDataIsLoaded() {
		ReactiveUpdater updater = new ReactiveUpdater(year -> List.of(),
				toyInputs(ReactToyData.context(tempDir).off(ReactElement.values())));

		updater.step();

		assertEquals(2020, updater.getInputs().currentYear().year());
		assertEquals(null, updater.getCropDecisions().lastYear());
		assertEquals(null, updater.getPastureDecisions().lastYear());
	}
}
