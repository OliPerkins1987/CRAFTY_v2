package de.cesr.crafty.core.modelRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.core.ToyData;
import de.cesr.crafty.core.cli.Config;
import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.crafty.Region;
import de.cesr.crafty.core.crafty.Service;
import de.cesr.crafty.core.crafty.RegionalModelRunner;
import de.cesr.crafty.core.dataLoader.land.CellsLoader;
import de.cesr.crafty.core.dataLoader.serivces.ServiceSet;
import de.cesr.crafty.core.updaters.CapitalUpdater;
import de.cesr.crafty.core.updaters.RegionsModelRunnerUpdater;
import de.cesr.crafty.core.updaters.Timestep;

/**
 * Unit tests for {@link ModelRunner} demand-equilibrium logic.
 *
 * These tests avoid file IO and heavy model setup by: - Testing the "flag
 * disabled" guard in demandEquilibrium() - Calling the private static helpers
 * RegionalDemandEquilibrium_calculation() and
 * initialTotalDSEquilibriumListrner() via reflection with a minimal in-memory
 * setup for regions and services.
 */
class ModelRunnerTest {
	ToyData toy = new ToyData();

	// RegionalModelRunner subclass that does NOT recompute calibration factors,
	// but instead only marks zero-calibration services as "no initial supply".
	private static class StubRegionalModelRunner extends RegionalModelRunner {
		StubRegionalModelRunner(String regionName) {
			super(regionName);
		}

		@Override
		public void initialDSEquilibriumFactorCalculation() {
			// The static method RegionalDemandEquilibrium_calculation() sets
			// ServiceSet.NoInitialSupplyServices.put(R.getName(), new ArrayList<>());
			// before calling this method. Here we only add services with
			// calibration factor == 1.0 to that list; we don't change the factors.
			ServiceSet.getServicesList().forEach(serviceName -> {
				Service s = R.getServicesHash().get(serviceName);
				if (s.getCalibration_Factor() == 1.0) {
					ServiceSet.NoInitialSupplyServices.get(R.getName()).add(serviceName);
				}
			});
		}
		
		
	}
	private Region region1;
	private Region region2;
	private StubRegionalModelRunner runner1;
	private StubRegionalModelRunner runner2;

	@TempDir
	Path projectDir;

	@BeforeEach
	void setUp() {

		toy.resetStaticState(projectDir);
		// Minimal config object
		if (ConfigLoader.config == null) {
			ConfigLoader.config = new Config();
		}


		// Set some dummy years so Timestep is initialised (needed by
		// RegionalModelRunner)
		Timestep.setStartYear(2000);
		Timestep.setEndtYear(2010);
		Timestep.setCurrentYear(2000);

		// Ensure static maps exist
		if (RegionsModelRunnerUpdater.regionsModelRunner == null) {
			RegionsModelRunnerUpdater.regionsModelRunner = new ConcurrentHashMap<>();
		}
		RegionsModelRunnerUpdater.regionsModelRunner.clear();

		if (ServiceSet.NoInitialSupplyServices == null) {
			ServiceSet.NoInitialSupplyServices = new ConcurrentHashMap<>();
		}
		ServiceSet.NoInitialSupplyServices.clear();

		if (ServiceSet.worldService == null) {
			ServiceSet.worldService = new ConcurrentHashMap<>();
		}
		ServiceSet.worldService.clear();

		// Service list: two services
		ServiceSet.getServicesList().clear();
		ServiceSet.getServicesList().addAll(Arrays.asList("S1", "S2"));

//		// Capital updater stub for RegionalDemandEquilibrium_calculation
//		ModelRunner.capitalUpdater = new NoopCapitalUpdater();

		// CellsLoader.regions must contain our regions so that the RegionalModelRunner
		// constructor can find them.
		if (CellsLoader.regions == null) {
			CellsLoader.regions = new ConcurrentHashMap<>();
		}
		CellsLoader.regions.clear();

		// --- Create two regions with service calibration factors ---
		region1 = new Region("R1");
		region2 = new Region("R2");

		// Each Region must have a services hash
		// For R1: S1=2.0, S2=4.0
		Service s1R1 = new Service("S1");
		s1R1.setCalibration_Factor(2.0);
		Service s2R1 = new Service("S2");
		s2R1.setCalibration_Factor(4.0);

		region1.getServicesHash().put("S1", s1R1);
		region1.getServicesHash().put("S2", s2R1);

		// For R2: S1=0.0 (no supply), S2=6.0
		Service s1R2 = new Service("S1");
		s1R2.setCalibration_Factor(1.0);
		Service s2R2 = new Service("S2");
		s2R2.setCalibration_Factor(6.0);

		region2.getServicesHash().put("S1", s1R2);
		region2.getServicesHash().put("S2", s2R2);

		CellsLoader.regions.put("R1", region1);
		CellsLoader.regions.put("R2", region2);

		// World service map only uses keys in initialTotalDSEquilibriumListrner()
		ServiceSet.worldService.put("S1", new Service("S1"));
		ServiceSet.worldService.put("S2", new Service("S2"));

		// Create stub RegionalModelRunners for each region
		runner1 = new StubRegionalModelRunner("R1");
		runner2 = new StubRegionalModelRunner("R2");

		RegionsModelRunnerUpdater.regionsModelRunner.put("R1", runner1);
		RegionsModelRunnerUpdater.regionsModelRunner.put("R2", runner2);
	}
	
    @AfterEach
    void tearDown() {
    	CustomLogger.shutdownRunFileLoggers();
    }

	@Test
	void demandEquilibrium_doesNothingWhenFlagFalse() {
		// Prepare a single service with a demand; no regions needed because
		// the method returns immediately when the flag is false.
		ConfigLoader.config.initial_demand_supply_equilibrium = false;

		// Call the static method
		InitialDSEquilibriumManager.demandEquilibrium();
	}

    @Test
    void regionalDemandEquilibriumCalculation_setsAverageCalibrationForNoSupplyServices() throws Exception {
        // At this point from setUp():
        // R1: S1=2.0, S2=4.0
        // R2: S1=1.0, S2=6.0
        //
        // Expected behaviour:
        //  - RegionalDemandEquilibrium_calculation() calls:
        //      ServiceSet.NoInitialSupplyServices.put(Rn, new ArrayList<>());
        //      RegionalRunner.initialDSEquilibriumFactorCalculation();
        //    Our stub marks S1 as "no initial supply" only in R2
        //    (because its calibration_factor=0.0 there).
        //
        //  - It then computes average calibration factors over regions:
        //      avg(S1) = (2.0 + 1.0)/2 = 1.5
        //      avg(S2) = (4.0 + 6.0)/2 = 5.0
        //
        //  - Finally, it replaces calibration_factor for "no initial supply"
        //    services with the average:
        //      R1: unchanged (no services marked)
        //      R2: S1 set to 1.5, S2 unchanged (6.0)

		Method method = InitialDSEquilibriumManager.class
				.getDeclaredMethod("RegionalDemandEquilibrium_calculation");
		method.setAccessible(true);
		method.invoke(null);

        // Check final calibration factors
        assertEquals(2.0, region1.getServicesHash().get("S1").getCalibration_Factor(), 1e-12);
        assertEquals(4.0, region1.getServicesHash().get("S2").getCalibration_Factor(), 1e-12);

        assertEquals(1.5, region2.getServicesHash().get("S1").getCalibration_Factor(), 1e-12,
                "Zero-supply service S1 in R2 should receive average calibration factor");
        assertEquals(6.0, region2.getServicesHash().get("S2").getCalibration_Factor(), 1e-12,
                "Service S2 in R2 should remain unchanged");
    }

    @Test
    void initialTotalDSEquilibriumListener_fillsMatrixWithServiceNameAndCalibration() throws Exception {
        // Ensure calibration factors have some known values
        region1.getServicesHash().get("S1").setCalibration_Factor(2.5);
        region1.getServicesHash().get("S2").setCalibration_Factor(4.5);
        region2.getServicesHash().get("S1").setCalibration_Factor(1.0);
        region2.getServicesHash().get("S2").setCalibration_Factor(3.0);

        // ListenerByRegion should already be initialised in RegionalModelRunner's ctor.
        // For safety, make sure DSEquilibriumListener exists for each runner.
        assertNotNull(runner1.listner);
        assertNotNull(runner1.listner.DSEquilibriumListener);
        assertNotNull(runner2.listner);
        assertNotNull(runner2.listner.DSEquilibriumListener);

        // Call private static initialTotalDSEquilibriumListrner()
        Method m = InitialDSEquilibriumManager.class.getDeclaredMethod("initialTotalDSEquilibriumListrner");
        m.setAccessible(true);
        m.invoke(null);

        // ServiceSet.getServicesList() = ["S1", "S2"]
        // For each region:
        //   row index = i+1, where i is index in services list
        // So:
        //   S1 -> row 1
        //   S2 -> row 2

        int idxS1 = ServiceSet.getServicesList().indexOf("S1"); // 0
        int idxS2 = ServiceSet.getServicesList().indexOf("S2"); // 1

        // Region 1
        String[][] eq1 = runner1.listner.DSEquilibriumListener;
        assertEquals("S1", eq1[idxS1 + 1][0]);
        assertEquals(String.valueOf(region1.getServicesHash().get("S1").getCalibration_Factor()),
                eq1[idxS1 + 1][1]);
        assertEquals("S2", eq1[idxS2 + 1][0]);
        assertEquals(String.valueOf(region1.getServicesHash().get("S2").getCalibration_Factor()),
                eq1[idxS2 + 1][1]);

        // Region 2
        String[][] eq2 = runner2.listner.DSEquilibriumListener;
        assertEquals("S1", eq2[idxS1 + 1][0]);
        assertEquals(String.valueOf(region2.getServicesHash().get("S1").getCalibration_Factor()),
                eq2[idxS1 + 1][1]);
        assertEquals("S2", eq2[idxS2 + 1][0]);
        assertEquals(String.valueOf(region2.getServicesHash().get("S2").getCalibration_Factor()),
                eq2[idxS2 + 1][1]);
    }

	@Test
	void validateInitialEquilibrium_acceptsDemandCalculatedFromFinalInitialSupply() {
		ServiceSet.getServicesList().clear();
		ServiceSet.getServicesList().add("S1");
		CellsLoader.regions.clear();
		CellsLoader.regions.put("R1", region1);
		RegionsModelRunnerUpdater.regionsModelRunner.clear();
		RegionsModelRunnerUpdater.regionsModelRunner.put("R1", runner1);

		region1.getServicesHash().get("S1").getDemands().put(Timestep.getStartYear(), 50.0);
		runner1.setRegionalSupply(new ConcurrentHashMap<>(Map.of("S1", 50.0)));

		assertDoesNotThrow(InitialDSEquilibriumManager::validateInitialEquilibrium);
	}

	@Test
	void validateInitialEquilibrium_rejectsSupplyChangedAfterCalibration() {
		ServiceSet.getServicesList().clear();
		ServiceSet.getServicesList().add("S1");
		CellsLoader.regions.clear();
		CellsLoader.regions.put("R1", region1);
		RegionsModelRunnerUpdater.regionsModelRunner.clear();
		RegionsModelRunnerUpdater.regionsModelRunner.put("R1", runner1);

		region1.getServicesHash().get("S1").getDemands().put(Timestep.getStartYear(), 50.0);
		runner1.setRegionalSupply(new ConcurrentHashMap<>(Map.of("S1", 40.0)));

		assertThrows(IllegalStateException.class, InitialDSEquilibriumManager::validateInitialEquilibrium);
	}

	@SuppressWarnings("unchecked")
	@Test
	void firstStep_skipsInitialInputsThatWereAppliedBeforeCalibration() throws Exception {
		ModelRunner modelRunner = new ModelRunner();
		AtomicInteger inputSteps = new AtomicInteger();
		AtomicInteger regularSteps = new AtomicInteger();
		ModelState initialInput = countingState(inputSteps);
		ModelState regularState = countingState(regularSteps);

		modelRunner.getScheduled().add(initialInput);
		modelRunner.getScheduled().add(regularState);

		Field initialUpdatersField = ModelRunner.class.getDeclaredField("initialStateUpdaters");
		initialUpdatersField.setAccessible(true);
		((List<ModelState>) initialUpdatersField.get(modelRunner)).add(initialInput);

		Field preparedField = ModelRunner.class.getDeclaredField("initialStatePrepared");
		preparedField.setAccessible(true);
		preparedField.setBoolean(modelRunner, true);

		modelRunner.step();
		assertEquals(0, inputSteps.get());
		assertEquals(1, regularSteps.get());

		modelRunner.step();
		assertEquals(1, inputSteps.get());
		assertEquals(2, regularSteps.get());
	}

	// =========================================================
	// Year-zero order and CRAFTY-react's step
	// =========================================================

	/** A step that records its name in a shared log each time it runs. */
	private static class RecordingState implements ModelState {
		private final String name;
		private final List<String> log;

		RecordingState(String name, List<String> log) {
			this.name = name;
			this.log = log;
		}

		@Override
		public void setup(AbstractModelRunner modelRunner) {
		}

		@Override
		public void toSchedule() {
		}

		@Override
		public void step() {
			log.add(name);
		}
	}

	/** A step with a no-argument constructor, to create by class name. */
	static class NoArgumentStep implements ModelState {
		@Override
		public void setup(AbstractModelRunner modelRunner) {
		}

		@Override
		public void toSchedule() {
		}

		@Override
		public void step() {
		}
	}

	@SuppressWarnings("unchecked")
	private static List<ModelState> initialStateUpdaters(ModelRunner runner) throws Exception {
		Field field = ModelRunner.class.getDeclaredField("initialStateUpdaters");
		field.setAccessible(true);
		return (List<ModelState>) field.get(runner);
	}

	@Test
	void prepareInitialState_runsTheInitialStateUpdatersInListOrder() throws Exception {
		// prepareInitialState() used to call eight fields by name, in an order that
		// differed from initialStateUpdaters. It now runs the list itself, so the
		// list order is the run order.
		ModelRunner runner = new ModelRunner();
		List<String> log = new ArrayList<>();
		initialStateUpdaters(runner).addAll(List.of(new RecordingState("first", log),
				new RecordingState("second", log), new RecordingState("third", log)));

		Method prepare = ModelRunner.class.getDeclaredMethod("prepareInitialState");
		prepare.setAccessible(true);
		prepare.invoke(runner);

		assertEquals(List.of("first", "second", "third"), log);
	}

	@Test
	void addBeforeCapitalUpdater_placesTheStepDirectlyBeforeCapitalUpdaterInBothLists() throws Exception {
		CapitalUpdater originalCapitalUpdater = ModelRunner.capitalUpdater;
		try {
			ModelRunner runner = new ModelRunner();
			List<String> log = new ArrayList<>();
			ModelState before = new RecordingState("before", log);
			ModelState after = new RecordingState("after", log);
			ModelState react = new RecordingState("react", log);
			ModelRunner.capitalUpdater = mock(CapitalUpdater.class);
			runner.getScheduled().addAll(List.of(before, ModelRunner.capitalUpdater, after));
			initialStateUpdaters(runner).addAll(List.of(before, ModelRunner.capitalUpdater, after));

			runner.addBeforeCapitalUpdater(react);

			assertEquals(List.of(before, react, ModelRunner.capitalUpdater, after), runner.getScheduled());
			assertEquals(List.of(before, react, ModelRunner.capitalUpdater, after), initialStateUpdaters(runner));
		} finally {
			ModelRunner.capitalUpdater = originalCapitalUpdater;
		}
	}

	@Test
	void theStepBeforeCapitalUpdater_runsForYearZeroAndIsSkippedOnceInTheFirstYear() throws Exception {
		CapitalUpdater originalCapitalUpdater = ModelRunner.capitalUpdater;
		try {
			ModelRunner runner = new ModelRunner();
			List<String> log = new ArrayList<>();
			ModelState react = new RecordingState("react", log);
			ModelState laterStep = new RecordingState("later", log);
			ModelRunner.capitalUpdater = mock(CapitalUpdater.class);
			runner.getScheduled().addAll(List.of(ModelRunner.capitalUpdater, laterStep));
			initialStateUpdaters(runner).add(ModelRunner.capitalUpdater);

			runner.addBeforeCapitalUpdater(react);

			Method prepare = ModelRunner.class.getDeclaredMethod("prepareInitialState");
			prepare.setAccessible(true);
			prepare.invoke(runner);
			assertEquals(List.of("react"), log, "It runs in the year-zero initial state");

			Field preparedField = ModelRunner.class.getDeclaredField("initialStatePrepared");
			preparedField.setAccessible(true);
			preparedField.setBoolean(runner, true);

			log.clear();
			runner.step();
			assertEquals(List.of("later"), log, "The first year skips it: it already ran for year zero");

			log.clear();
			runner.step();
			assertEquals(List.of("react", "later"), log, "Later years run it before CapitalUpdater as usual");
		} finally {
			ModelRunner.capitalUpdater = originalCapitalUpdater;
		}
	}

	@Test
	void createStepByClassName_createsAnInstanceOfTheNamedStep() throws Exception {
		ModelState step = ModelRunner.createStepByClassName(NoArgumentStep.class.getName());

		assertInstanceOf(NoArgumentStep.class, step);
	}

	@Test
	void createStepByClassName_cannotFindReactWithoutTheReactModule() {
		// crafty-core's own tests run without crafty-react, so this is the situation
		// in which the run stops with "crafty-react is not on the classpath".
		assertThrows(ClassNotFoundException.class,
				() -> ModelRunner.createStepByClassName(ModelRunner.REACTIVE_UPDATER_CLASS));
	}

	@Test
	void createStepByClassName_rejectsAClassThatIsNotAStep() {
		assertThrows(ClassCastException.class, () -> ModelRunner.createStepByClassName("java.lang.StringBuilder"));
	}

	private static ModelState countingState(AtomicInteger counter) {
		return new ModelState() {
			@Override
			public void setup(AbstractModelRunner modelRunner) {
			}

			@Override
			public void toSchedule() {
			}

			@Override
			public void step() {
				counter.incrementAndGet();
			}
		};
	}
}
