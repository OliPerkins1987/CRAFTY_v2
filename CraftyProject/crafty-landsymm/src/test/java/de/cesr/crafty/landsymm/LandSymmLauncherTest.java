package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The launcher from end to end, with {@link FakeModel}s as CRAFTY and PLUM (48e PLAN section 10).
 */
class LandSymmLauncherTest {

	@TempDir
	Path tempDir;

	private LauncherFixture f;

	@BeforeEach
	void setUp() throws Exception {
		f = new LauncherFixture(tempDir);
	}

	@Test
	void aCalibrationRunWritesItsConfigsRunsCraftyThenPlumAndIsComplete() throws Exception {
		String craftyBaseBefore = Files.readString(f.craftyBase);
		String plumBaseBefore = Files.readString(f.plumBase);

		Path run = f.calibrationRun("calib1990_v1");

		RunRecord record = RunRecord.read(run);
		assertEquals("complete", record.text("status"), f.printed());
		assertEquals("calibration", record.text("run_type"));
		assertEquals(LauncherFixture.FINAL_YEAR, record.number("calibration", "final_year"));
		assertEquals("production", record.text("calibration", "production"));
		assertEquals("plum/output", record.text("calibration", "plum_output"));
		assertEquals(0, record.number("crafty", "exit_code"));
		assertEquals(0, record.number("plum", "exit_code"));
		assertEquals(1990, record.number("crafty", "start_year"));
		assertEquals(1990, record.number("crafty", "end_year"));
		assertEquals(LauncherFixture.CALIBRATION_STEPS, record.number("plum", "end_timestep"));

		String crafty = Files.readString(run.resolve("crafty/config.yaml"));
		assertTrue(crafty.contains("\nplum_calibration_run: true\n"), crafty);
		assertTrue(crafty.contains("\nuse_price_feedback: false\n"), crafty);
		assertTrue(crafty.contains("# [launcher] use_price_feedback: true"), "the base value is kept, commented out");
		assertTrue(crafty.contains("crafty_production_path: \"" + LauncherFixture.slashes(run.resolve("production"))),
				crafty);
		assertTrue(crafty.contains("output_folder_name: \"calib1990_v1\""), crafty);
		assertFalse(crafty.contains("plum_calibration_path"), "a calibration run has no calibration to read");

		String plum = Files.readString(run.resolve("plum/config.properties"));
		assertTrue(plum.contains("\nIS_CALIBRATION_RUN=true\n"), plum);
		assertTrue(plum.contains("\nEND_TIMESTEP=" + LauncherFixture.CALIBRATION_STEPS + "\n"), plum);
		assertTrue(plum.contains("# [launcher] END_TIMESTEP=15"), plum);
		assertTrue(plum.contains("\nCRAFTY_PRODUCTION_DIR=" + LauncherFixture.slashes(run.resolve("production"))), plum);
		assertTrue(plum.contains("\nLPJG_MONITOR_TIMEOUT=7200\n"), "120 minutes in seconds: " + plum);
		assertFalse(plum.contains("CALIB_DIR="), plum);

		assertEquals(craftyBaseBefore, Files.readString(f.craftyBase), "the base files are never edited");
		assertEquals(plumBaseBefore, Files.readString(f.plumBase));
		assertTrue(Files.isRegularFile(run.resolve("master.yaml")));
		assertTrue(Files.readString(run.resolve("launcher.log")).contains("The run is complete"));
	}

	@Test
	void ifCraftyFailsPlumNeverStarts() throws Exception {
		Path plumStarted = tempDir.resolve("plum-started");
		Path master = f.master("m.yaml", f.calibrationMaster("calib_fails", ""));

		int code = f.launcher(LauncherFixture.models(p -> List.of("exit=3"), p -> List.of("write=" + plumStarted)))
				.run(new String[] { master.toString() });

		assertEquals(1, code);
		RunRecord record = RunRecord.read(f.outputRoot.resolve("calib_fails"));
		assertEquals("failed", record.text("status"));
		assertTrue(record.text("reason").contains("CRAFTY stopped with exit code 3"), record.text("reason"));
		assertEquals(3, record.number("crafty", "exit_code"));
		assertNull(record.get("plum", "exit_code"));
		assertFalse(Files.exists(plumStarted), "PLUM's calibration needs CRAFTY's production");
	}

	@Test
	void aCalibrationIsntCompleteUntilPlumHasLeftItsState() throws Exception {
		Path master = f.master("m.yaml", f.calibrationMaster("calib_no_state", ""));

		int code = f.launcher(LauncherFixture.models(LauncherFixture::craftyStartYear,
				p -> LauncherFixture.plumCalibration(p, false))).run(new String[] { master.toString() });

		assertEquals(1, code);
		RunRecord record = RunRecord.read(f.outputRoot.resolve("calib_no_state"));
		assertEquals("failed", record.text("status"));
		assertEquals(0, record.number("plum", "exit_code"));
		assertTrue(record.text("reason").contains("internationalMarket.ser"), record.text("reason"));
		assertNull(record.get("calibration"), "no calibration block until it's complete");
	}

	@Test
	void aScenarioRunTakesTurnsWithPlumToItsEndYear() throws Exception {
		Path calibration = f.calibrationRun("calib1990_v1");
		Path master = f.master("s.yaml", f.scenarioMaster("hist_seq_test1", calibration, 1992,
				"coupling:\n  plum_price_basis: exporter\n  trade_capital_slope: 4.5\noutputs:\n  export_basis: false\n"));

		int code = f.launcher(LauncherFixture.scenarioModels(1990, 1992)).run(new String[] { master.toString() });

		Path run = f.outputRoot.resolve("hist_seq_test1");
		assertEquals(0, code, f.printed());
		RunRecord record = RunRecord.read(run);
		assertEquals("complete", record.text("status"));
		assertEquals("scenario", record.text("run_type"));
		assertEquals(List.of(1990, 1991, 1992), record.get("crafty", "years_done"));
		assertEquals(List.of(1990, 1991, 1992), record.get("plum", "years_done"), "PLUM solves CRAFTY's last year (Q3 a)");
		assertEquals("exporter", record.text("coupling", "plum_price_basis"));
		assertEquals("sequential", record.text("coupling", "plum_coupling_mode"), "core's default, filled in");

		String crafty = Files.readString(run.resolve("crafty/config.yaml"));
		assertTrue(crafty.contains("\nplum_calibration_run: false\n"), crafty);
		assertTrue(crafty.contains("\nplum_calibration_final_year: " + LauncherFixture.FINAL_YEAR + "\n"), crafty);
		assertTrue(crafty.contains("plum_calibration_path: \"" + LauncherFixture.slashes(calibration.resolve("plum/output"))),
				crafty);
		assertTrue(crafty.contains("calibration_production_path: \""
				+ LauncherFixture.slashes(calibration.resolve("production"))), crafty);
		assertTrue(crafty.contains("\ncoupled_end_year: 1992\n"), crafty);
		assertTrue(crafty.contains("\nplum_price_basis: \"exporter\"\n"), crafty);
		assertTrue(crafty.contains("\ntrade_capital_slope: 4.5\n"), crafty);
		assertTrue(crafty.contains(
				"\nplum_coupling_outputs: [prices_received, production_sent, trade_capitals, ungrazed_grass, stock_costs]\n"),
				crafty);

		String plum = Files.readString(run.resolve("plum/config.properties"));
		assertTrue(plum.contains("\nIS_CALIBRATION_RUN=false\n"), plum);
		assertTrue(plum.contains("\nSTART_TIMESTEP=0\n"), plum);
		assertTrue(plum.contains("\nEND_TIMESTEP=2\n"), "1992 - BASE_YEAR 1990: " + plum);
		assertTrue(plum.contains("\nCALIB_DIR=" + LauncherFixture.slashes(calibration.resolve("plum/output")) + "\n"), plum);

		List<String> timeline = Files.readAllLines(run.resolve("timeline.csv"));
		assertEquals("Year,CRAFTY_done,PLUM_done", timeline.get(0));
		assertEquals(4, timeline.size(), timeline.toString());
		for (String row : timeline.subList(1, 4)) {
			assertEquals(3, row.split(",", -1).length);
			assertFalse(row.endsWith(","), "both times recorded: " + row);
		}
		String differences = Files.readString(run.resolve("config_vs_calibration.txt"));
		assertTrue(differences.contains("CRAFTY (crafty/config.yaml): 0 difference(s)"), differences);
		assertTrue(differences.contains("PLUM (plum/config.properties): 0 difference(s)"), differences);
	}

	@Test
	void aScenarioListsTheSettingsThatDifferFromItsCalibration() throws Exception {
		Path calibration = f.calibrationRun("calib1990_v1");
		Files.writeString(f.plumBase, Files.readString(f.plumBase) + "INITIAL_PRICE_MONOGASTRICS=0.5\n");
		Path master = f.master("s.yaml", f.scenarioMaster("hist_diff", calibration, 1991, ""));

		int code = f.launcher(LauncherFixture.scenarioModels(1990, 1991)).run(new String[] { master.toString() });

		assertEquals(0, code, f.printed());
		String differences = Files.readString(f.outputRoot.resolve("hist_diff/config_vs_calibration.txt"));
		assertTrue(differences.contains("INITIAL_PRICE_MONOGASTRICS: (not set) -> 0.5"), differences);
		assertTrue(differences.contains("PLUM (plum/config.properties): 1 difference(s)"), differences);
		assertTrue(f.printed().contains("1 setting(s) differ from it"), f.printed());
	}

	@Test
	void aModelThatFailsStopsTheOtherWithEverythingItStarted() throws Exception {
		Path calibration = f.calibrationRun("calib1990_v1");
		Path pidFile = tempDir.resolve("grandchild.pid");
		Path master = f.master("s.yaml", f.scenarioMaster("hist_crafty_fails", calibration, 1993, ""));
		LandSymmLauncher.ModelCommands models = LauncherFixture.models(p -> {
			List<String> steps = new ArrayList<>(LauncherFixture.craftyScenario(p, 1990, 1990));
			steps.add("wait=" + DoneFileWaiter.doneFile(p.run().plumOutput(), 1990));
			steps.add("exit=7");
			return steps;
		}, p -> {
			List<String> steps = new ArrayList<>(List.of("grandchild=" + pidFile));
			steps.addAll(LauncherFixture.plumScenario(p, 1990, 1993)); // waits for 1991, which never comes
			return steps;
		});

		int code = f.launcher(models).run(new String[] { master.toString() });

		assertEquals(1, code);
		RunRecord record = RunRecord.read(f.outputRoot.resolve("hist_crafty_fails"));
		assertEquals("failed", record.text("status"));
		assertTrue(record.text("reason").startsWith("CRAFTY stopped with exit code 7"), record.text("reason"));
		assertEquals(7, record.number("crafty", "exit_code"));
		assertEquals(Boolean.TRUE, record.get("plum", "stopped_by_launcher"));
		assertEquals(List.of(1990), record.get("crafty", "years_done"));
		long grandchild = Long.parseLong(Files.readString(pidFile).strip());
		assertFalse(waitUntilGone(grandchild), "PLUM's own child process (on Windows, gams.exe) is stopped too");
	}

	@Test
	void plumsErrorFileGivesItAGraceTimeThenItIsStopped() throws Exception {
		Path calibration = f.calibrationRun("calib1990_v1");
		Path master = f.master("s.yaml", f.scenarioMaster("hist_plum_error", calibration, 1992, ""));
		LandSymmLauncher.ModelCommands models = LauncherFixture.models(p -> {
			List<String> steps = new ArrayList<>(LauncherFixture.craftyScenario(p, 1990, 1990));
			steps.add("wait=" + DoneFileWaiter.doneFile(p.run().plumOutput(), 1990));
			return steps;
		}, p -> List.of("wait=" + DoneFileWaiter.doneFile(p.run().production(), 1990),
				"write=" + DoneFileWaiter.errorFile(p.run().plumOutput(), 1990), "hang"));

		long start = System.nanoTime();
		int code = f.launcher(models).run(new String[] { master.toString() });

		assertEquals(1, code);
		assertTrue(System.nanoTime() - start < Duration.ofSeconds(40).toNanos(), "not the models' own timeouts");
		RunRecord record = RunRecord.read(f.outputRoot.resolve("hist_plum_error"));
		assertEquals("failed", record.text("status"));
		assertTrue(record.text("reason").contains(DoneFileWaiter.errorFile(
				f.outputRoot.resolve("hist_plum_error/plum/output"), 1990).toString()), record.text("reason"));
		assertTrue(record.text("reason").contains("still running"), record.text("reason"));
		assertEquals(Boolean.TRUE, record.get("plum", "stopped_by_launcher"));
		assertEquals(Boolean.TRUE, record.get("crafty", "stopped_by_launcher"));
	}

	@Test
	void anExistingRunFolderIsRefusedAndNothingIsWritten() throws Exception {
		Path existing = Files.createDirectories(f.outputRoot.resolve("calib1990_v1"));
		Path master = f.master("m.yaml", f.calibrationMaster("calib1990_v1", ""));
		AtomicInteger started = new AtomicInteger();

		int code = f.launcher(LauncherFixture.models(p -> {
			started.incrementAndGet();
			return List.of();
		}, p -> List.of())).run(new String[] { master.toString() });

		assertEquals(1, code);
		assertTrue(f.printed().contains("already exists"), f.printed());
		assertEquals(0, started.get());
		try (Stream<Path> files = Files.list(existing)) {
			assertEquals(0, files.count(), "the folder is left as it was");
		}
	}

	@Test
	void aScenarioRunStartsOnlyFromACompleteLauncherCalibration() throws Exception {
		Path handMade = Files.createDirectories(tempDir.resolve("CRAFTY_calib1990"));
		Path master = f.master("s.yaml", f.scenarioMaster("hist_from_hand_made", handMade, 1992, ""));

		assertEquals(1, f.launcher(LauncherFixture.scenarioModels(1990, 1992)).run(new String[] { master.toString() }));
		assertTrue(f.printed().contains("has no run.yaml"), f.printed());

		Path running = Files.createDirectories(tempDir.resolve("calib_running"));
		new RunRecord().put("run_type", "calibration").put("status", "running").write(running);
		Path master2 = f.master("s2.yaml", f.scenarioMaster("hist_from_running", running, 1992, ""));

		assertEquals(1, f.launcher(LauncherFixture.scenarioModels(1990, 1992)).run(new String[] { master2.toString() }));
		assertTrue(f.printed().contains("has status running, not complete"), f.printed());
		assertFalse(Files.exists(f.outputRoot.resolve("hist_from_hand_made")));
		assertFalse(Files.exists(f.outputRoot.resolve("hist_from_running")));
	}

	@Test
	void stoppingTheLauncherStopsBothModelsAndRecordsTheRunAsInterrupted() throws Exception {
		Path calibration = f.calibrationRun("calib1990_v1");
		Path started = tempDir.resolve("both-started");
		Path master = f.master("s.yaml", f.scenarioMaster("hist_interrupted", calibration, 1992, ""));
		LandSymmLauncher launcher = f.launcher(LauncherFixture.models(p -> List.of("hang"),
				p -> List.of("write=" + started, "hang")));
		AtomicInteger code = new AtomicInteger(-1);

		Thread run = new Thread(() -> code.set(launcher.run(new String[] { master.toString() })));
		run.start();
		long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		while (!Files.exists(started) && System.nanoTime() < end) {
			Thread.sleep(50);
		}
		assertTrue(Files.exists(started), f.printed());
		launcher.interrupt(); // what the shutdown hook does
		run.join(30_000);

		assertFalse(run.isAlive());
		assertEquals(1, code.get());
		RunRecord record = RunRecord.read(f.outputRoot.resolve("hist_interrupted"));
		assertEquals("interrupted", record.text("status"));
		assertEquals(Boolean.TRUE, record.get("crafty", "stopped_by_launcher"));
		assertEquals(Boolean.TRUE, record.get("plum", "stopped_by_launcher"));
	}

	@Test
	void checkStartsNothingAndMakesNoRunFolder() throws Exception {
		Path master = f.master("m.yaml", f.calibrationMaster("calib_checked", ""));
		AtomicInteger started = new AtomicInteger();

		int code = f.launcher(LauncherFixture.models(p -> {
			started.incrementAndGet();
			return List.of();
		}, p -> List.of())).run(new String[] { "--check", master.toString() });

		assertEquals(0, code, f.printed());
		assertTrue(f.printed().contains("Check passed"), f.printed());
		assertTrue(f.printed().contains("use_price_feedback: true; the run's copy turns it off"), f.printed());
		assertEquals(0, started.get());
		assertFalse(Files.exists(f.outputRoot.resolve("calib_checked")));
	}

	@Test
	void runsOnTheSameBuildShareOneJarCopy() throws Exception {
		Path first = f.calibrationRun("calib_a");
		Path second = f.calibrationRun("calib_b");

		try (Stream<Path> jars = Files.list(f.outputRoot.resolve("jars"))) {
			assertEquals(1, jars.count());
		}
		String sha = LandSymmLauncher.sha256(f.jar);
		for (Path run : List.of(first, second)) {
			RunRecord record = RunRecord.read(run);
			assertEquals(sha, record.text("crafty", "jar_sha256"));
			assertEquals("../jars/crafty-landsymm-" + sha.substring(0, 12) + ".jar", record.text("crafty", "jar"));
		}
		Files.writeString(f.jar, "a new build");
		Path third = f.calibrationRun("calib_c");
		try (Stream<Path> jars = Files.list(f.outputRoot.resolve("jars"))) {
			assertEquals(2, jars.count(), "a new build gets its own copy");
		}
		assertNotEquals(sha, RunRecord.read(third).text("crafty", "jar_sha256"));
	}

	@Test
	void theRealModelsAreStartedAsTheirOwnScriptsDo() throws Exception {
		Path master = f.master("m.yaml", f.calibrationMaster("calib_commands", ""));
		LandSymmLauncher launcher = f.launcher(LandSymmLauncher.JAVA);
		LandSymmLauncher.Prepared p = launcher.prepare(MasterConfig.read(master));
		Path jarCopy = tempDir.resolve("jars/x.jar");

		List<String> crafty = LandSymmLauncher.JAVA.crafty(p, jarCopy);
		List<String> plum = LandSymmLauncher.JAVA.plum(p);

		assertEquals(List.of("-Xmx1g", "-cp", jarCopy.toString(), "de.cesr.crafty.landsymm.MainLandSymm",
				"--config-file", p.run().craftyConfig().toString()), crafty.subList(1, crafty.size()));
		assertEquals("-Xmx1g", plum.get(1));
		assertEquals("-cp", plum.get(2));
		assertEquals(f.gamsDir.resolve("apifiles/Java/api/GAMSJavaAPI.jar") + java.io.File.pathSeparator
				+ f.plumClasses, plum.get(3));
		assertEquals(List.of("-DBUILDNUM=6", "-DBUILDVER=6",
				"-DCONFIG_FILE=" + LauncherFixture.slashes(p.run().plumConfig()), "ac.ed.lurg.ModelMain"),
				plum.subList(4, plum.size()));
	}

	@Test
	void theStartYearsMustAgree() throws Exception {
		Files.writeString(f.plumBase, Files.readString(f.plumBase).replace("BASE_YEAR=1990\n", ""));
		Path master = f.master("m.yaml", f.calibrationMaster("calib_2020", ""));

		assertEquals(1, f.launcher(LauncherFixture.calibrationModels()).run(new String[] { "--check", master.toString() }));
		assertTrue(f.printed().contains("PLUM's BASE_YEAR is 2020 (PLUM's default"), f.printed());
	}

	@Test
	void aScenarioEndYearMustBeInsideCraftysScenario() throws Exception {
		Path calibration = f.calibrationRun("calib1990_v1");
		for (int endYear : List.of(1990, 2021)) {
			Path master = f.master("s.yaml", f.scenarioMaster("hist_" + endYear, calibration, endYear, ""));
			assertEquals(1, f.launcher(LauncherFixture.scenarioModels(1990, 1991))
					.run(new String[] { "--check", master.toString() }));
			assertTrue(f.printed().contains("end_year must be after CRAFTY's start year 1990 and no later than the "
					+ "hist scenario's end, 2020; got " + endYear), f.printed());
		}
	}

	@Test
	void theHeapIsReadAsJavaReadsIt() {
		assertEquals(16L << 30, LandSymmLauncher.heapBytes("16g"));
		assertEquals(3000L << 20, LandSymmLauncher.heapBytes("3000m"));
		assertEquals(512L << 10, LandSymmLauncher.heapBytes("512K"));
		assertEquals(1000L, LandSymmLauncher.heapBytes("1000"));
	}

	@Test
	void theMemoryWarningCountsBothHeapsOnlyWhenBothRunAtOnce() {
		long g = 1L << 30;

		Optional<String> scenario = LandSymmLauncher.memoryWarning(false, 16 * g, 3 * g, 10 * g);
		assertTrue(scenario.isPresent());
		assertTrue(scenario.get().contains("come to 19.0 GB, more than the 10.0 GB"), "-Xmx's GB: " + scenario.get());

		Optional<String> calibration = LandSymmLauncher.memoryWarning(true, 16 * g, 3 * g, 10 * g);
		assertTrue(calibration.get().contains("come to 16.0 GB"), "one JVM at a time: " + calibration.get());

		assertTrue(LandSymmLauncher.memoryWarning(true, 16 * g, 3 * g, 17 * g).isEmpty());
		assertTrue(LandSymmLauncher.memoryWarning(false, 16 * g, 3 * g, 0).isEmpty(), "free memory unknown");
	}

	@Test
	void badArgumentsShowTheUsage() {
		assertEquals(2, f.launcher(LauncherFixture.calibrationModels()).run(new String[] {}));
		assertEquals(2, f.launcher(LauncherFixture.calibrationModels()).run(new String[] { "a.yaml", "b.yaml" }));
		assertTrue(f.printed().contains("Usage: java -jar crafty-landsymm-1.0.jar [--check] <master.yaml>"));
	}

	/** Whether a process is still alive after up to 10 s. */
	private static boolean waitUntilGone(long pid) throws InterruptedException {
		long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (System.nanoTime() < end) {
			Optional<ProcessHandle> h = ProcessHandle.of(pid);
			if (h.isEmpty() || !h.get().isAlive()) {
				return false;
			}
			Thread.sleep(100);
		}
		return true;
	}
}
