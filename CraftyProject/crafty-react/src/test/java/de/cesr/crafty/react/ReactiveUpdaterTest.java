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
import java.util.stream.Stream;

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
import de.cesr.crafty.core.modelRunner.ModelRunner;
import de.cesr.crafty.core.modelRunner.ModelState;
import de.cesr.crafty.core.updaters.CapitalUpdater;
import de.cesr.crafty.core.updaters.ProductionCostUpdater;
import de.cesr.crafty.core.updaters.Timestep;
import de.cesr.crafty.react.data.CoreFacts;
import de.cesr.crafty.react.data.ReactConfig;
import de.cesr.crafty.react.data.ReactConfigLoader;
import de.cesr.crafty.react.data.ReactElement;
import de.cesr.crafty.react.data.ReactInputException;
import de.cesr.crafty.react.data.ReactInputs;
import de.cesr.crafty.react.data.ReactToyData;
import de.cesr.crafty.react.data.ReactYearData;
import de.cesr.crafty.react.decisions.CropDecisions;
import de.cesr.crafty.react.decisions.DecisionUnits;
import de.cesr.crafty.react.decisions.PastureDecisions;
import de.cesr.crafty.react.decisions.YearPrices;
import de.cesr.crafty.react.output.ReactOutputs;
import de.cesr.crafty.react.science.CropSurfaces;

class ReactiveUpdaterTest {

	@TempDir
	Path tempDir;

	private Config originalConfig;
	private ProductionCostUpdater originalCostUpdater;
	private List<String> originalCapitals;

	@BeforeEach
	void setUp() {
		originalConfig = ConfigLoader.config;
		originalCostUpdater = ModelRunner.productionCostUpdater;
		originalCapitals = CapitalUpdater.getCapitalsList();
		Timestep.setCurrentYear(2020);
	}

	@AfterEach
	void restoreStatics() {
		ConfigLoader.config = originalConfig;
		ModelRunner.productionCostUpdater = originalCostUpdater;
		CapitalUpdater.setCapitalsList(originalCapitals);
		AFTsLoader.getAftHash().clear();
		CellsLoader.hashCell.clear();
		CellsLoader.regions.clear();
	}

	/**
	 * Sets up core's state to match the toy project, so that the constructor's startup checks pass.
	 * The constructor reads core through CoreFacts: AFT metadata, cells and their regions, services,
	 * capitals, the years and the spatial cost files.
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
		// No capital types are loaded, so core counts each of these as a suitability.
		CapitalUpdater.setCapitalsList(List.of("IntC3C_irrig_suit", "ExtC3C_suit", "IntP_suit"));

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
	void creatingItChecksTheProjectAndKeepsWhatWasLoaded() throws Exception {
		ConfigLoader.config = new Config();
		ConfigLoader.config.reactive_afts = true;
		setUpCoreForToyProject();

		ReactiveUpdater updater = new ReactiveUpdater();

		assertNotNull(updater.getInputs());
		assertEquals(3, updater.getInputs().checked().parameters().reactive().size());
		assertEquals(List.of("IntC3C_irrig", "ExtC3C"), List.copyOf(updater.getCropDecisions().managements().keySet()));
		assertEquals(List.of("IntP"), List.copyOf(updater.getPastureDecisions().managements().keySet()));
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

	// ---- loading each year (27c) ----

	@Test
	void eachStepLoadsItsYearOnce() {
		// The toy project is small, so this exercises the real loader rather than a stand-in.
		ReactToyData.project(tempDir);
		ReactInputs inputs = ReactInputs.create(ReactConfig.defaults(), ReactToyData.context(tempDir).build());
		ReactiveUpdater updater = new ReactiveUpdater(inputs);

		Timestep.setCurrentYear(2020);
		updater.step();
		assertEquals(2020, inputs.currentYear().year());
		assertEquals(1.82f, inputs.currentYear().crop("CerealsC3", "0")[0], 1e-5f);

		Timestep.setCurrentYear(2021);
		updater.step();
		assertEquals(2021, inputs.currentYear().year(), "The next year replaces the one before");
		assertSame(inputs, updater.getInputs());
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
		ReactiveUpdater updater = new ReactiveUpdater(toyInputs(toyContext()));

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
		ReactiveUpdater updater = new ReactiveUpdater(toyInputs(toyContext()));

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
		ReactiveUpdater updater = new ReactiveUpdater(inputs, units, crops, pasture, Map.of());

		updater.step();

		assertSame(crops, updater.getCropDecisions());
		assertSame(pasture, updater.getPastureDecisions());
		assertEquals(2020, crops.lastYear());
		assertEquals(2020, pasture.lastYear());
	}

	@Test
	void reactWritesNoFilesWhileTheStagesDecide() throws IOException {
		// The file handover was retired in phase 5: react hands its values to the model in memory.
		ReactiveUpdater updater = new ReactiveUpdater(toyInputs(toyContext()));
		Map<Path, String> before = filesUnder(tempDir);

		updater.step();
		Timestep.setCurrentYear(2021);
		updater.step();

		assertEquals(2021, updater.getCropDecisions().lastYear());
		assertEquals(2021, updater.getPastureDecisions().lastYear());
		assertEquals(before, filesUnder(tempDir), "no file is written, changed or removed");
	}

	@Test
	void theStepWritesTheInspectionFilesInTheYearsAsked() {
		ReactToyData.project(tempDir);
		ReactToyData.write(tempDir, ReactConfigLoader.LOCATION.toString(), "outputs:", "  crops: true");
		Path output = tempDir.resolve("run output");
		ReactiveUpdater updater = new ReactiveUpdater(ReactInputs.create(ReactConfigLoader.load(tempDir),
				toyContext().outputFolder(output).mapYears(2021).build()));

		updater.step();
		Timestep.setCurrentYear(2021);
		updater.step();

		Path react = output.resolve(ReactOutputs.FOLDER);
		assertTrue(Files.exists(react.resolve("ssp126-React-Crops-Yield-2021.csv")), "2021 is a map year");
		assertFalse(Files.exists(react.resolve("ssp126-React-Crops-Yield-2020.csv")), "2020 is not");
	}

	/** Every file under a folder, with its size and time of last change. */
	private static Map<Path, String> filesUnder(Path folder) throws IOException {
		Map<Path, String> files = new LinkedHashMap<>();
		try (Stream<Path> paths = Files.walk(folder)) {
			for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
				files.put(path, Files.size(path) + " bytes, changed " + Files.getLastModifiedTime(path));
			}
		}
		return files;
	}

	@Test
	void aYearThatCannotBeDecidedNamesTheProblem() {
		// step() hands this to LOGGER.fatal, which stops the run; decideYear is where it is raised.
		ReactiveUpdater updater = new ReactiveUpdater(
				toyInputs(ReactToyData.context(tempDir).prices((service, region, year) -> {
					throw new ReactInputException("no weight");
				})));

		ReactInputException e = assertThrows(ReactInputException.class, () -> updater.decideYear(2020));

		assertTrue(e.getMessage().contains("C3cereals") && e.getMessage().contains("2020")
				&& e.getMessage().contains("no weight"), e.getMessage());
	}

	@Test
	void aMissingPasturePriceNamesTheProblem() {
		ReactiveUpdater updater = new ReactiveUpdater(
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
		ReactiveUpdater updater = new ReactiveUpdater(toyInputs(ReactToyData.context(tempDir)
				.off(ReactElement.FERTILISER, ReactElement.IRRIGATION, ReactElement.OTHER_INTENSITY)));

		updater.step();

		assertEquals(2020, updater.getInputs().currentYear().year());
		assertTrue(updater.getInputs().currentYear().crops().isEmpty(), "32b: no crop element on, so no crop data");
		assertTrue(updater.getCropDecisions().managements().isEmpty(), "phase 3 plan, Q6");
		assertEquals(null, updater.getCropDecisions().lastYear());
		assertEquals(2020, updater.getPastureDecisions().lastYear());
		assertEquals(List.of("IntP"), List.copyOf(updater.getPastureDecisions().managements().keySet()));
	}

	@Test
	void withOnlyFertiliserAndIrrigationOnCropsAreDecidedAndPastureIsNot() {
		ReactiveUpdater updater = new ReactiveUpdater(toyInputs(ReactToyData.context(tempDir)
				.off(ReactElement.OTHER_INTENSITY, ReactElement.STOCKING)));

		updater.step();

		assertEquals(2020, updater.getCropDecisions().lastYear());
		assertTrue(updater.getPastureDecisions().managements().isEmpty(), "phase 3 plan, Q6");
		assertEquals(null, updater.getPastureDecisions().lastYear());
	}

	@Test
	void withNothingToDecideOnlyTheDataIsLoaded() {
		ReactiveUpdater updater = new ReactiveUpdater(
				toyInputs(ReactToyData.context(tempDir).off(ReactElement.values())));

		updater.step();

		assertEquals(2020, updater.getInputs().currentYear().year());
		assertEquals(null, updater.getCropDecisions().lastYear());
		assertEquals(null, updater.getPastureDecisions().lastYear());
	}

	// ---- handing over to the model (31c) ----

	/** The toy project's cells, as core holds them. */
	private static Map<String, Cell> toyCells() {
		Map<String, Cell> cells = new LinkedHashMap<>();
		cells.put("1,1", cell(1, 1, "North"));
		cells.put("1,2", cell(1, 2, "South"));
		cells.put("2,1", cell(2, 1, "North"));
		return cells;
	}

	@Test
	void eachStepHandsTheYearToTheCells() {
		ReactInputs inputs = toyInputs(toyContext());
		Map<String, Cell> cells = toyCells();
		ReactiveUpdater updater = new ReactiveUpdater(inputs, cells);
		int south = DecisionUnits.build(inputs.checked().cellKey(), inputs.context().regions()).unitOf("1,2");

		updater.step();

		assertEquals(updater.getCropDecisions().managements().get("IntC3C_irrig").yield()[south],
				cells.get("1,2").getCapitals().get("IntC3C_irrig_suit"));
		assertEquals(updater.getPastureDecisions().managements().get("IntP").stockingCost()[south],
				cells.get("1,2").getStockingCosts().get("IntP"));
	}

	@Test
	void aRepeatedYearIsHandedOverAgainButNotDecidedAgain() {
		ReactInputs inputs = toyInputs(toyContext());
		Map<String, Cell> cells = toyCells();
		ReactiveUpdater updater = new ReactiveUpdater(inputs, cells);
		int unit = DecisionUnits.build(inputs.checked().cellKey(), inputs.context().regions()).unitOf("1,1");
		Map<String, Double> capitals = cells.get("1,1").getCapitals();

		updater.step(); // year zero, during initialisation
		double yearZero = capitals.get("IntC3C_irrig_suit");
		double[] nitrogen = updater.getCropDecisions().managements().get("IntC3C_irrig").nitrogen().clone();
		capitals.put("IntC3C_irrig_suit", 0.0); // as when core loads the year's capitals
		updater.step(); // the same year again

		assertEquals(yearZero, capitals.get("IntC3C_irrig_suit"), "handed over again");
		assertArrayEquals(nitrogen, updater.getCropDecisions().managements().get("IntC3C_irrig").nitrogen(), 0,
				"not decided again");

		Timestep.setCurrentYear(2021);
		updater.step();

		assertEquals(updater.getCropDecisions().managements().get("IntC3C_irrig").yield()[unit],
				capitals.get("IntC3C_irrig_suit"), "the next year's value replaces it");
	}

	@Test
	void withNothingToDecideNothingIsHandedOver() {
		Map<String, Cell> cells = toyCells();
		ReactiveUpdater updater = new ReactiveUpdater(toyInputs(ReactToyData.context(tempDir).off(ReactElement.values())),
				cells);

		updater.step();

		for (Cell cell : cells.values()) {
			assertTrue(cell.getCapitals().isEmpty() && cell.getNfertCosts().isEmpty() && cell.getIrrigationCosts().isEmpty()
					&& cell.getIntensityCosts().isEmpty() && cell.getStockingCosts().isEmpty());
		}
	}
}
