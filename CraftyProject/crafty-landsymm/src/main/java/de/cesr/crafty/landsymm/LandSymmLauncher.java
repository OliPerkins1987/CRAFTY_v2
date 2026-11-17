package de.cesr.crafty.landsymm;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import de.cesr.crafty.core.cli.Config;

/**
 * The coupled-run launcher (48e): one command runs a CRAFTY-PLUM calibration or scenario run from a master YAML.
 *
 * <pre>
 * java -jar crafty-landsymm-1.0.jar [--check] master.yaml
 * </pre>
 *
 * It checks everything first (48e PLAN section 4), then makes the run folder, writes the run's copies of CRAFTY's
 * config.yaml and PLUM's config.properties, and starts the two models as child JVMs:
 * <ul>
 * <li>a calibration run: CRAFTY writes its start-year production and exits, then PLUM calibrates on it; the run is
 * complete once PLUM has exited with 0 and left its calibrated state (section 5);</li>
 * <li>a scenario run: CRAFTY and PLUM together, taking turns through their done files, until both have exited
 * (section 6).</li>
 * </ul>
 * If one model fails, the other is stopped. run.yaml records what was resolved and how the run ended. With
 * {@code --check} nothing is started or written, apart from the configs in a temporary folder.
 */
public final class LandSymmLauncher {

	/** How the launcher starts the models; the tests put stand-ins in. */
	interface ModelCommands {
		List<String> crafty(Prepared run, Path jar);

		List<String> plum(Prepared run);
	}

	/** The real models, on the java the launcher runs on. */
	static final ModelCommands JAVA = new ModelCommands() {
		@Override
		public List<String> crafty(Prepared p, Path jar) {
			return ModelProcess.craftyCommand(ModelProcess.javaExecutable(), p.master().craftyHeap(),
					p.master().craftyJvmArgs(), jar, p.run().craftyConfig());
		}

		@Override
		public List<String> plum(Prepared p) {
			return ModelProcess.plumCommand(ModelProcess.javaExecutable(), p.master().plumHeap(),
					p.master().plumJvmArgs(), p.master().gamsJar(), p.master().plumClasses(), p.run().plumConfig());
		}
	};

	/**
	 * What the tests change.
	 *
	 * @param launcherJar  the jar the launcher runs from, which CRAFTY runs from unless crafty.jar is set
	 * @param poll         how often the models are checked
	 * @param errorGrace   how long PLUM has to exit after writing an error file
	 * @param stopWait     how long a stopped model has to go
	 * @param shutdownHook whether stopping the launcher stops the models
	 */
	record Settings(ModelCommands commands, Path launcherJar, Duration poll, Duration errorGrace, Duration stopWait,
			boolean shutdownHook) {

		static Settings standard() {
			return new Settings(JAVA, ownJar(), Duration.ofSeconds(2), Duration.ofMinutes(2), Duration.ofSeconds(30),
					true);
		}
	}

	/** Everything resolved and checked before anything is written (48e PLAN section 4). */
	record Prepared(MasterConfig master, RunFolder run, CalibrationCheck.Calibration calibration,
			CraftyRunConfig.Scenario scenario, int craftyEndYear, int plumBaseYear, int plumEndTimestep,
			int plumLastYear, RunConfigWriter.Copy craftyCopy, RunConfigWriter.Copy plumCopy, Path jar,
			String jarSha256, String craftyBaseSha256, String plumBaseSha256, List<String> notes, List<String> warnings,
			String differences, int differenceCount) {

		int startYear() {
			return scenario.startYear();
		}
	}

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

	private final Settings settings;
	private final LauncherLog log;
	private final RunSupervisor supervisor;
	private final AtomicBoolean finished = new AtomicBoolean();
	private volatile RunRecord record;
	private volatile RunFolder runFolder;

	LandSymmLauncher(Settings settings, PrintStream console) {
		this.settings = settings;
		this.log = new LauncherLog(console);
		this.supervisor = new RunSupervisor(log, settings.poll(), settings.errorGrace(), settings.stopWait());
	}

	public static void main(String[] args) {
		System.exit(new LandSymmLauncher(Settings.standard(), System.out).run(args));
	}

	/** Runs the launcher on its arguments: 0 when the run (or check) is complete, 1 otherwise, 2 for bad arguments. */
	int run(String[] args) {
		boolean check = false;
		Path master = null;
		for (String a : args) {
			if (a.equals("--check")) {
				check = true;
			} else if (master == null && !a.startsWith("-")) {
				master = Paths.get(a);
			} else {
				return usage();
			}
		}
		if (master == null) {
			return usage();
		}
		try {
			Prepared p = prepare(MasterConfig.read(master));
			return check ? reportCheck(p) : execute(p);
		} catch (CouplingInputException e) {
			log.line("The run can't start:\n  " + String.join("\n  ", e.getProblems()));
			return 1;
		}
	}

	private int usage() {
		log.line("Usage: java -jar crafty-landsymm-1.0.jar [--check] <master.yaml>\n"
				+ "  Runs a coupled CRAFTY-PLUM calibration or scenario run from a master YAML (crafty-landsymm README).\n"
				+ "  --check  checks everything and writes the run's configs to a temporary folder, but starts nothing");
		return 2;
	}

	/** Every check before anything is written (48e PLAN section 4); stops with every problem found. */
	Prepared prepare(MasterConfig m) {
		List<String> problems = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		List<String> notes = new ArrayList<>();
		RunFolder run = new RunFolder(m.runFolder());
		if (Files.exists(run.root())) {
			problems.add("The run folder " + run.root() + " already exists. Each run gets a new folder, so choose "
					+ "another run_name");
		}
		requireFile(m.craftyConfig(), "crafty.config", problems);
		requireFile(m.plumConfig(), "plum.config", problems);
		if (!Files.isDirectory(m.plumClasses())) {
			problems.add("plum.classes " + m.plumClasses() + " isn't a folder");
		}
		if (!Files.isRegularFile(m.gamsJar())) {
			problems.add("plum.gams_dir has no " + m.gamsJar() + ", which PLUM needs on its class path");
		}
		Path jar = m.craftyJar() != null ? m.craftyJar() : settings.launcherJar();
		if (jar == null) {
			problems.add("crafty.jar isn't set and the launcher isn't running from a jar, so there's no jar to run "
					+ "CRAFTY from");
		} else {
			requireFile(jar, "crafty.jar", problems);
		}
		CalibrationCheck.Calibration calibration = null;
		if (!m.calibrationRun()) {
			try {
				calibration = CalibrationCheck.ofRun(m.calibrationRunFolder());
			} catch (CouplingInputException e) {
				problems.addAll(e.getProblems());
			}
		}
		CraftyRunConfig.Base craftyBase = Files.isRegularFile(m.craftyConfig())
				? CraftyRunConfig.readBase(m.craftyConfig(), problems)
				: null;
		PlumRunConfig.Base plumBase = Files.isRegularFile(m.plumConfig())
				? PlumRunConfig.readBase(m.plumConfig(), problems)
				: null;
		CraftyRunConfig.Scenario scenario = null;
		if (craftyBase != null) {
			problems.addAll(CraftyRunConfig.baseProblems(craftyBase));
			scenario = CraftyRunConfig.scenario(craftyBase, problems);
		}
		Integer baseYear = null;
		if (plumBase != null) {
			baseYear = PlumRunConfig.baseYear(plumBase, problems);
			problems.addAll(PlumRunConfig.baseProblems(plumBase, m.calibrationRun()));
		}
		if (scenario != null && baseYear != null && baseYear != scenario.startYear()) {
			problems.add("PLUM's BASE_YEAR is " + baseYear
					+ (plumBase.properties().getProperty("BASE_YEAR") == null ? " (PLUM's default: " + m.plumConfig()
							+ " doesn't set it)" : "")
					+ " but CRAFTY's " + scenario.name() + " scenario starts in " + scenario.startYear()
					+ ". They must be the same year");
		}
		if (!m.calibrationRun() && scenario != null) {
			if (m.endYear() <= scenario.startYear() || m.endYear() > scenario.endYear()) {
				problems.add("end_year must be after CRAFTY's start year " + scenario.startYear() + " and no later "
						+ "than the " + scenario.name() + " scenario's end, " + scenario.endYear() + "; got "
						+ m.endYear());
			}
			if (calibration != null && calibration.startYear() != scenario.startYear()) {
				problems.add("The calibration run " + calibration.folder() + " starts in " + calibration.startYear()
						+ ", but this run's CRAFTY scenario " + scenario.name() + " starts in "
						+ scenario.startYear());
			}
		}
		stopIf(problems);

		int craftyEndYear = m.calibrationRun() ? scenario.startYear() : m.endYear();
		int endTimestep = PlumRunConfig.endTimestep(m, baseYear);
		Map<String, Object> craftyValues = CraftyRunConfig.values(m, run, calibration);
		Map<String, String> plumValues = PlumRunConfig.values(m, run, baseYear, calibration);
		String when = LocalDateTime.now().format(TIME);
		RunConfigWriter.Copy craftyCopy = CraftyRunConfig.copy(craftyBase, craftyValues, header(m, m.craftyConfig(), when));
		RunConfigWriter.Copy plumCopy = PlumRunConfig.copy(plumBase, plumValues, header(m, m.plumConfig(), when));
		problems.addAll(craftyCopy.problems());
		problems.addAll(plumCopy.problems());
		stopIf(problems);

		CraftyRunConfig.CoreCheck core = checkWithCore(craftyCopy, craftyValues);
		problems.addAll(core.problems());
		warnings.addAll(core.warnings());
		notes.addAll(core.notes());
		stopIf(problems);

		for (String key : CraftyRunConfig.switchesTurnedOff(craftyBase)) {
			notes.add(craftyBase.values().containsKey(key)
					? "Your config.yaml has " + key + ": true; the run's copy turns it off (48e Q2 a)"
					: "Your config.yaml doesn't set " + key + ", which is on by default; the run's copy turns it off");
		}
		craftyCopy.replaced().forEach(l -> notes.add("Replaced in the run's CRAFTY config: " + l.strip()));
		plumCopy.replaced().forEach(l -> notes.add("Replaced in the run's PLUM config: " + l.strip()));
		if (!m.outputsOff().isEmpty()) {
			notes.add("The coupler's yearly files switched off: " + m.outputsOff());
		}
		memoryWarning(m).ifPresent(warnings::add);

		String differences = null;
		int differenceCount = 0;
		if (calibration != null) {
			List<String> ignoredCrafty = new ArrayList<>(craftyValues.keySet());
			ignoredCrafty.addAll(CraftyRunConfig.RUN_KEYS);
			ignoredCrafty.addAll(MasterConfig.COUPLING_KEYS.keySet());
			ignoredCrafty.addAll(CraftyRunConfig.OLD_NAMES.keySet());
			List<ConfigDifferences.Difference> c;
			List<ConfigDifferences.Difference> pl;
			try {
				c = ConfigDifferences.crafty(
						CraftyRunConfig.parse(Files.readString(calibration.craftyConfig(), StandardCharsets.UTF_8)),
						CraftyRunConfig.parse(craftyCopy.text()), ignoredCrafty);
				pl = ConfigDifferences.plum(
						PlumRunConfig.load(Files.readString(calibration.plumConfig(), StandardCharsets.ISO_8859_1)),
						PlumRunConfig.load(plumCopy.text()), PlumRunConfig.LAUNCHER_KEYS);
			} catch (IOException e) {
				throw new CouplingInputException("Can't read the calibration run's configs: " + e);
			}
			differences = ConfigDifferences.text(calibration.folder(), c, pl);
			differenceCount = c.size() + pl.size();
		}
		return new Prepared(m, run, calibration, scenario, craftyEndYear, baseYear, endTimestep, baseYear + endTimestep,
				craftyCopy, plumCopy, jar, sha256(jar), sha256(m.craftyConfig()), sha256(m.plumConfig()),
				List.copyOf(notes), List.copyOf(warnings), differences, differenceCount);
	}

	private static List<String> header(MasterConfig m, Path base, String when) {
		return List.of("Run " + m.runName() + "'s copy of " + RunConfigWriter.slashes(base) + ",",
				"written by LandSymmLauncher on " + when + " from " + RunConfigWriter.slashes(m.file()) + ".",
				"Lines starting \"" + RunConfigWriter.MARKER.strip() + "\" are that file's values for keys the launcher "
						+ "sets; the launcher's values are at the end.");
	}

	/** The run's CRAFTY config, checked with core's loader through a temporary file. */
	private static CraftyRunConfig.CoreCheck checkWithCore(RunConfigWriter.Copy copy, Map<String, Object> values) {
		Path temp = null;
		try {
			temp = Files.createTempFile("landsymm-config-", ".yaml");
			Files.writeString(temp, copy.text(), StandardCharsets.UTF_8);
			return CraftyRunConfig.checkWithCore(temp, values);
		} catch (IOException e) {
			throw new CouplingInputException("Can't write a temporary copy of the run's config.yaml to check: " + e);
		} finally {
			if (temp != null) {
				try {
					Files.deleteIfExists(temp);
				} catch (IOException e) {
					// a temporary file left behind does no harm
				}
			}
		}
	}

	private static void requireFile(Path file, String key, List<String> problems) {
		if (file != null && !Files.isRegularFile(file)) {
			problems.add(key + " " + file + " isn't a file");
		}
	}

	private static void stopIf(List<String> problems) {
		if (!problems.isEmpty()) {
			throw new CouplingInputException(problems);
		}
	}

	/** --check: everything checked, the configs written to a temporary folder, nothing started. */
	private int reportCheck(Prepared p) {
		Path folder;
		try {
			folder = Files.createTempDirectory("landsymm-check-");
			Files.writeString(folder.resolve("config.yaml"), p.craftyCopy().text(), StandardCharsets.UTF_8);
			Files.writeString(folder.resolve("config.properties"), p.plumCopy().text(), StandardCharsets.ISO_8859_1);
			if (p.differences() != null) {
				Files.writeString(folder.resolve("config_vs_calibration.txt"), p.differences(), StandardCharsets.UTF_8);
			}
		} catch (IOException e) {
			log.line("The checks passed, but the configs couldn't be written to a temporary folder: " + e);
			return 1;
		}
		describe(p);
		log.line("Check passed: nothing was started, and " + p.run().root() + " wasn't made. The run's configs "
				+ "as they'd be written are in " + folder);
		return 0;
	}

	private void describe(Prepared p) {
		MasterConfig m = p.master();
		log.line((m.calibrationRun() ? "Calibration" : "Scenario") + " run " + m.runName() + " in " + p.run().root());
		log.line("CRAFTY: scenario " + p.scenario().name() + ", " + p.startYear()
				+ (p.craftyEndYear() == p.startYear() ? " only" : "-" + p.craftyEndYear()) + ", heap "
				+ m.craftyHeap() + ", jar " + p.jar() + " (" + p.jarSha256().substring(0, 12) + ")");
		log.line("PLUM: BASE_YEAR " + p.plumBaseYear() + ", START_TIMESTEP 0, END_TIMESTEP " + p.plumEndTimestep()
				+ ", so years " + p.plumBaseYear() + "-" + p.plumLastYear() + ", heap " + m.plumHeap() + ", build "
				+ m.plumClasses());
		if (p.calibration() != null) {
			log.line("From calibration run " + p.calibration().folder() + ": PLUM's state at "
					+ p.calibration().finalYear() + "; " + p.differenceCount()
					+ " setting(s) differ from it (config_vs_calibration.txt)");
		}
		p.notes().forEach(log::line);
		p.warnings().forEach(w -> log.line("Warning: " + w));
	}

	/** Makes the run folder and runs the models (48e PLAN sections 5 and 6). */
	int execute(Prepared p) {
		MasterConfig m = p.master();
		RunFolder run = p.run();
		try {
			Files.createDirectories(m.outputRoot());
			Files.createDirectory(run.root()); // fails if another launcher made it in the meantime
		} catch (IOException e) {
			log.line("Can't make the run folder " + run.root() + ": " + e);
			return 1;
		}
		runFolder = run;
		log.toFile(run.launcherLog());
		Path jarCopy = run.jars().resolve("crafty-landsymm-" + p.jarSha256().substring(0, 12) + ".jar");
		record = startRecord(p, jarCopy);
		record.write(run.root());
		describe(p);

		Thread hook = settings.shutdownHook() ? new Thread(this::interrupt, "landsymm-launcher-stop") : null;
		if (hook != null) {
			Runtime.getRuntime().addShutdownHook(hook);
		}
		try {
			try {
				Files.createDirectories(run.production());
				Files.createDirectories(run.craftyOutput());
				Files.createDirectories(run.plumOutput());
				Files.copy(m.file(), run.master());
				Files.writeString(run.craftyConfig(), p.craftyCopy().text(), StandardCharsets.UTF_8);
				Files.writeString(run.plumConfig(), p.plumCopy().text(), StandardCharsets.ISO_8859_1);
				if (p.differences() != null) {
					Files.writeString(run.configDifferences(), p.differences(), StandardCharsets.UTF_8);
				}
				copyJar(p.jar(), jarCopy);
			} catch (IOException e) {
				return finish(RunRecord.FAILED, "Couldn't set up the run folder: " + e);
			}
			return m.calibrationRun() ? calibrationRun(p, jarCopy) : scenarioRun(p, jarCopy);
		} finally {
			if (hook != null) {
				try {
					Runtime.getRuntime().removeShutdownHook(hook);
				} catch (IllegalStateException e) {
					// the JVM is shutting down, and the hook is running
				}
			}
		}
	}

	/** CRAFTY's start year, then PLUM's calibration on it (48e PLAN section 5). */
	private int calibrationRun(Prepared p, Path jarCopy) {
		RunFolder run = p.run();
		ModelProcess crafty = craftyProcess(p, jarCopy);
		RunSupervisor.Outcome o = supervisor.run(List.of(crafty), null, null);
		recordExits(o, crafty);
		if (!o.succeeded()) {
			return finish(RunRecord.FAILED, o.failure());
		}
		Path production = run.production().resolve(String.valueOf(p.startYear())).resolve(ProductionFile.FILE);
		Path done = DoneFileWaiter.doneFile(run.production(), p.startYear());
		if (!Files.isRegularFile(production) || !Files.isRegularFile(done)) {
			return finish(RunRecord.FAILED, "CRAFTY exited with 0 but didn't leave " + production + " and " + done);
		}
		log.line("Starting PLUM's calibration on CRAFTY's " + p.startYear() + " production: " + p.plumEndTimestep()
				+ " steps, to " + p.plumLastYear());
		ModelProcess plum = plumProcess(p);
		o = supervisor.run(List.of(plum),
				new RunSupervisor.ErrorWatch(plum, run.plumOutput(), p.startYear(), p.plumLastYear()), null);
		recordExits(o, plum);
		if (!o.succeeded()) {
			return finish(RunRecord.FAILED, o.failure());
		}
		List<String> problems = CalibrationCheck.fileProblems(run.production(), run.plumOutput(), p.startYear(),
				p.plumLastYear());
		if (!problems.isEmpty()) {
			return finish(RunRecord.FAILED,
					"PLUM exited with 0, but the calibration isn't complete:\n  " + String.join("\n  ", problems));
		}
		Map<String, Object> calibration = record.section(RunRecord.CALIBRATION);
		calibration.put("production", RunRecord.pathText(run.root(), run.production()));
		calibration.put("plum_output", RunRecord.pathText(run.root(), run.plumOutput()));
		calibration.put("final_year", p.plumLastYear());
		return finish(RunRecord.COMPLETE, "");
	}

	/** CRAFTY and PLUM together, from the calibration's state (48e PLAN section 6). */
	private int scenarioRun(Prepared p, Path jarCopy) {
		RunFolder run = p.run();
		RunSupervisor.Timeline timeline = new RunSupervisor.Timeline(run.production(), run.plumOutput(),
				p.startYear(), p.craftyEndYear(), p.plumLastYear(), run.timeline());
		ModelProcess crafty = craftyProcess(p, jarCopy);
		ModelProcess plum = plumProcess(p);
		RunSupervisor.Outcome o = supervisor.run(List.of(crafty, plum),
				new RunSupervisor.ErrorWatch(plum, run.plumOutput(), p.startYear(), p.plumLastYear()), timeline);
		recordExits(o, crafty, plum);
		List<Integer> craftyMissing = timeline.craftyMissing();
		List<Integer> plumMissing = timeline.plumMissing();
		record.section("crafty").put("years_done", yearsDone(p.startYear(), p.craftyEndYear(), craftyMissing));
		record.section("plum").put("years_done", yearsDone(p.startYear(), p.plumLastYear(), plumMissing));
		if (!o.succeeded()) {
			return finish(RunRecord.FAILED, o.failure());
		}
		List<String> problems = new ArrayList<>();
		if (!craftyMissing.isEmpty()) {
			problems.add("CRAFTY exited with 0, but production years " + craftyMissing + " have no done file");
		}
		if (!plumMissing.isEmpty()) {
			problems.add("PLUM exited with 0, but years " + plumMissing + " have no done file");
		}
		for (int year = p.startYear(); year <= p.plumLastYear(); year++) {
			if (Files.exists(DoneFileWaiter.errorFile(run.plumOutput(), year))) {
				problems.add("PLUM wrote " + DoneFileWaiter.errorFile(run.plumOutput(), year));
			}
		}
		if (!problems.isEmpty()) {
			return finish(RunRecord.FAILED, String.join("\n", problems));
		}
		return finish(RunRecord.COMPLETE, "");
	}

	private static List<Integer> yearsDone(int first, int last, List<Integer> missing) {
		List<Integer> done = new ArrayList<>();
		for (int year = first; year <= last; year++) {
			if (!missing.contains(year)) {
				done.add(year);
			}
		}
		return done;
	}

	private ModelProcess craftyProcess(Prepared p, Path jarCopy) {
		return new ModelProcess("CRAFTY", settings.commands().crafty(p, jarCopy), p.run().crafty(),
				p.run().craftyLog(), null);
	}

	private ModelProcess plumProcess(Prepared p) {
		return new ModelProcess("PLUM", settings.commands().plum(p), p.run().plum(), p.run().plumLog(),
				p.master().gamsDir());
	}

	private void recordExits(RunSupervisor.Outcome o, ModelProcess... models) {
		for (ModelProcess m : models) {
			Map<String, Object> section = record.section(m.name().toLowerCase());
			Integer code = o.exitCodes().get(m.name());
			if (code != null) {
				section.put("exit_code", code);
			}
			if (m.stoppedByLauncher()) {
				section.put("stopped_by_launcher", true);
			}
		}
	}

	/**
	 * Ends the run once. If the shutdown hook got there first, the run stays interrupted, but the record is written
	 * again with the exit codes the models left.
	 */
	private int finish(String status, String reason) {
		if (!finished.compareAndSet(false, true)) {
			record.write(runFolder.root());
			return 1;
		}
		record.put("status", status);
		record.put("reason", reason);
		record.put("finished", LocalDateTime.now().format(TIME));
		record.write(runFolder.root());
		log.line(RunRecord.COMPLETE.equals(status) ? "The run is complete: " + runFolder.root()
				: "The run failed: " + reason);
		return RunRecord.COMPLETE.equals(status) ? 0 : 1;
	}

	/** The shutdown hook: stops both models and records the run as interrupted, unless it has already ended. */
	void interrupt() {
		if (!finished.compareAndSet(false, true)) {
			return;
		}
		List<String> stopped = supervisor.stopAll("the launcher was stopped");
		if (record != null && runFolder != null) {
			stopped.forEach(name -> record.section(name.toLowerCase()).put("stopped_by_launcher", true));
			record.put("status", RunRecord.INTERRUPTED);
			record.put("reason", "The launcher was stopped before the run finished, and it stopped both models");
			record.put("finished", LocalDateTime.now().format(TIME));
			record.write(runFolder.root());
		}
		log.line("The run was interrupted: the launcher was stopped, and it stopped the models");
	}

	private RunRecord startRecord(Prepared p, Path jarCopy) {
		MasterConfig m = p.master();
		RunFolder run = p.run();
		RunRecord r = new RunRecord();
		r.put("run_name", m.runName());
		r.put("run_type", m.calibrationRun() ? RunRecord.CALIBRATION : RunRecord.SCENARIO);
		r.put("status", RunRecord.RUNNING);
		r.put("reason", "");
		r.put("started", LocalDateTime.now().format(TIME));
		r.put("finished", "");
		r.put("master", RunRecord.pathText(run.root(), run.master()));
		if (!m.calibrationRun()) {
			r.put("calibration_run", RunConfigWriter.slashes(p.calibration().folder()));
			r.put("end_year", m.endYear());
			r.put("coupling", resolvedCoupling(m));
		}
		r.put("outputs", m.outputs());
		Map<String, Object> crafty = r.section("crafty");
		crafty.put("base_config", RunConfigWriter.slashes(m.craftyConfig()));
		crafty.put("base_config_sha256", p.craftyBaseSha256());
		crafty.put("config", RunRecord.pathText(run.root(), run.craftyConfig()));
		crafty.put("jar", RunRecord.pathText(run.root(), jarCopy));
		crafty.put("jar_sha256", p.jarSha256());
		crafty.put("scenario", p.scenario().name());
		crafty.put("start_year", p.startYear());
		crafty.put("end_year", p.craftyEndYear());
		crafty.put("heap", m.craftyHeap());
		crafty.put("jvm_args", m.craftyJvmArgs());
		crafty.put("log", RunRecord.pathText(run.root(), run.craftyLog()));
		Map<String, Object> plum = r.section("plum");
		plum.put("base_config", RunConfigWriter.slashes(m.plumConfig()));
		plum.put("base_config_sha256", p.plumBaseSha256());
		plum.put("config", RunRecord.pathText(run.root(), run.plumConfig()));
		plum.put("classes", RunConfigWriter.slashes(m.plumClasses()));
		plum.put("gams_dir", RunConfigWriter.slashes(m.gamsDir()));
		plum.put("base_year", p.plumBaseYear());
		plum.put("end_timestep", p.plumEndTimestep());
		plum.put("last_year", p.plumLastYear());
		plum.put("heap", m.plumHeap());
		plum.put("jvm_args", m.plumJvmArgs());
		plum.put("log", RunRecord.pathText(run.root(), run.plumLog()));
		return r;
	}

	/** Every coupler setting with its value in this run: the master YAML's, or core's default. */
	static Map<String, Object> resolvedCoupling(MasterConfig m) {
		Config defaults = new Config();
		Map<String, Object> resolved = new LinkedHashMap<>();
		for (String key : MasterConfig.COUPLING_KEYS.keySet()) {
			Object value = m.coupling().get(key);
			if (value == null) {
				try {
					value = Config.class.getField(key).get(defaults);
				} catch (ReflectiveOperationException e) {
					value = "(unknown)";
				}
			}
			resolved.put(key, value);
		}
		return resolved;
	}

	/** One copy of the jar per build, shared by every run on it (48e L2). */
	private void copyJar(Path jar, Path copy) throws IOException {
		if (Files.isRegularFile(copy) && Files.size(copy) == Files.size(jar)) {
			log.line("CRAFTY runs from " + copy + ", this build's copy, already there");
			return;
		}
		Files.createDirectories(copy.getParent());
		Path temp = Files.createTempFile(copy.getParent(), "copying-", ".tmp");
		Files.copy(jar, temp, StandardCopyOption.REPLACE_EXISTING);
		try {
			Files.move(temp, copy, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException | FileAlreadyExistsException e) {
			Files.move(temp, copy, StandardCopyOption.REPLACE_EXISTING);
		}
		log.line("Copied " + jar + " to " + copy + ", for CRAFTY to run from (one copy per build)");
	}

	/** The jar the launcher runs from, or null when it runs from classes (in an IDE or the tests). */
	static Path ownJar() {
		try {
			Path p = Paths.get(LandSymmLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			return Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar") ? p : null;
		} catch (Exception e) {
			return null;
		}
	}

	static String sha256(Path file) {
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[1 << 16];
			for (int n; (n = in.read(buffer)) > 0;) {
				digest.update(buffer, 0, n);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (IOException e) {
			throw new CouplingInputException("Can't read " + file + ": " + e);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e); // every JVM has SHA-256
		}
	}

	/**
	 * A warning when the heaps come to more than the memory free now (main plan D5): both in a scenario run, where
	 * both JVMs are alive the whole time, and the larger in a calibration run.
	 */
	static java.util.Optional<String> memoryWarning(MasterConfig m) {
		OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
		if (!(os instanceof com.sun.management.OperatingSystemMXBean sun)) {
			return java.util.Optional.empty();
		}
		return memoryWarning(m.calibrationRun(), heapBytes(m.craftyHeap()), heapBytes(m.plumHeap()),
				sun.getFreeMemorySize());
	}

	/**
	 * The warning for these heaps and this much free memory, all in bytes. GB here are 2^30 bytes, as in -Xmx, so a
	 * 16g heap shows as 16.0 GB.
	 */
	static java.util.Optional<String> memoryWarning(boolean calibrationRun, long craftyHeap, long plumHeap, long free) {
		long need = calibrationRun ? Math.max(craftyHeap, plumHeap) : craftyHeap + plumHeap;
		if (free <= 0 || need <= free) {
			return java.util.Optional.empty();
		}
		double gb = 1L << 30;
		return java.util.Optional.of(String.format("The heaps (%s) come to %.1f GB, more than the %.1f GB of "
				+ "memory free now. A JVM short of memory can stop without an error message",
				calibrationRun ? "the larger of CRAFTY's and PLUM's, run one at a time" : "CRAFTY's + PLUM's",
				need / gb, free / gb));
	}

	/** A -Xmx size in bytes. */
	static long heapBytes(String heap) {
		char unit = Character.toLowerCase(heap.charAt(heap.length() - 1));
		long factor = switch (unit) {
		case 'k' -> 1L << 10;
		case 'm' -> 1L << 20;
		case 'g' -> 1L << 30;
		default -> 1L;
		};
		String number = Character.isDigit(unit) ? heap : heap.substring(0, heap.length() - 1);
		return Long.parseLong(number) * factor;
	}
}
