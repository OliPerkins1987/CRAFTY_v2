package de.cesr.crafty.landsymm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A small world for the launcher's tests, in a temporary folder: a CRAFTY project with a scenarios.csv, base CRAFTY
 * and PLUM configs set up as the user's are (price feedback on, a calibration PLUM config), a PLUM build folder, a
 * GAMS folder with an empty GAMSJavaAPI.jar, a stand-in coupler jar, and an output root for the runs.
 *
 * The models are {@link FakeModel}s; {@link #calibrationModels()} and {@link #scenarioModels(int, int)} play a run
 * that goes well, using the coupler's test extracts (all1990's 2029-2030 rows; three countries).
 */
final class LauncherFixture {

	static final int START = 1990;
	/** BASE_YEAR 1990 + 40 calibration steps: the extract's 2030 rows are the calibration's final year. */
	static final int CALIBRATION_STEPS = 40;
	static final int FINAL_YEAR = START + CALIBRATION_STEPS;

	final Path root;
	final Path project;
	final Path craftyBase;
	final Path plumBase;
	final Path plumClasses;
	final Path gamsDir;
	final Path jar;
	final Path outputRoot;
	final ByteArrayOutputStream console = new ByteArrayOutputStream();

	LauncherFixture(Path root) throws IOException {
		this.root = root;
		project = root.resolve("project");
		Files.createDirectories(project.resolve("csv"));
		Files.writeString(project.resolve("csv").resolve("scenarios.csv"),
				"Name,startYear,endtYear\nhist,1990,2020\nssp126,2020,2100\n");
		craftyBase = root.resolve("crafty-config.yaml");
		Files.writeString(craftyBase, "# The user's standalone CRAFTY config, in short\n"
				+ "project_path: " + q(project) + "\n"
				+ "scenario: \"hist\"\n"
				+ "regionalisation: true\n"
				+ "use_explicit_price_utility: true\n"
				+ "use_price_feedback: true\n"
				+ "global_price_feedback: true\n"
				+ "output_folder_name: \"standalone-run\"\n");
		plumBase = root.resolve("plum-config.properties");
		Files.writeString(plumBase, "BASE_DIR=" + slashes(root) + "\n"
				+ "ALL_CRAFTY_COUNTRIES=true\n"
				+ "IS_CALIBRATION_RUN=true\n"
				+ "BASE_YEAR=1990\n"
				+ "END_TIMESTEP=15\n"
				+ "# Ruminant husbandry cost\n"
				+ "PROD_COST_RUMINANTS=0.05\n");
		plumClasses = root.resolve("plum-build").resolve("6");
		Files.createDirectories(plumClasses);
		gamsDir = root.resolve("gams");
		Files.createDirectories(gamsDir.resolve("apifiles").resolve("Java").resolve("api"));
		Files.writeString(gamsDir.resolve("apifiles").resolve("Java").resolve("api").resolve("GAMSJavaAPI.jar"), "");
		jar = root.resolve("crafty-landsymm-test.jar");
		Files.writeString(jar, "a stand-in for the coupler jar");
		outputRoot = root.resolve("runs");
	}

	static String slashes(Path p) {
		return RunConfigWriter.slashes(p);
	}

	static String q(Path p) {
		return "\"" + slashes(p) + "\"";
	}

	/** A file from the test resources. */
	static Path resource(String name) {
		try {
			return Paths.get(LauncherFixture.class.getResource("/" + name).toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Writes a master YAML. */
	Path master(String fileName, String yaml) throws IOException {
		Path file = root.resolve(fileName);
		Files.writeString(file, yaml);
		return file;
	}

	String modelsYaml(boolean calibration) {
		return "crafty:\n"
				+ "  config: " + q(craftyBase) + "\n"
				+ "  heap: 1g\n"
				+ "  jar: " + q(jar) + "\n"
				+ "plum:\n"
				+ "  config: " + q(plumBase) + "\n"
				+ "  classes: " + q(plumClasses) + "\n"
				+ "  gams_dir: " + q(gamsDir) + "\n"
				+ "  heap: 1g\n"
				+ (calibration ? "  calibration_steps: " + CALIBRATION_STEPS + "\n" : "");
	}

	String calibrationMaster(String runName, String extra) {
		return "run_name: " + runName + "\n"
				+ "output_root: " + q(outputRoot) + "\n"
				+ "is_calibration_run: true\n"
				+ extra
				+ modelsYaml(true);
	}

	String scenarioMaster(String runName, Path calibrationRun, int endYear, String extra) {
		return "run_name: " + runName + "\n"
				+ "output_root: " + q(outputRoot) + "\n"
				+ "is_calibration_run: false\n"
				+ "calibration_run: " + q(calibrationRun) + "\n"
				+ "end_year: " + endYear + "\n"
				+ extra
				+ modelsYaml(false);
	}

	/** A launcher with these models, quick timings and no shutdown hook, printing to {@link #console}. */
	LandSymmLauncher launcher(LandSymmLauncher.ModelCommands models) {
		return new LandSymmLauncher(new LandSymmLauncher.Settings(models, null, Duration.ofMillis(50),
				Duration.ofSeconds(1), Duration.ofSeconds(10), false),
				new PrintStream(console, true, StandardCharsets.UTF_8));
	}

	String printed() {
		return console.toString(StandardCharsets.UTF_8);
	}

	/** CRAFTY writes its start-year production and exits. */
	static List<String> craftyStartYear(LandSymmLauncher.Prepared p) {
		Path production = p.run().production().resolve(String.valueOf(START));
		return List.of("copy=" + resource("production_1990_extract/production.csv") + "*"
				+ production.resolve("production.csv"), "write=" + production.resolve("done"));
	}

	/** PLUM's calibration: waits for CRAFTY, writes the final year's rows and done, and its state. */
	static List<String> plumCalibration(LandSymmLauncher.Prepared p, boolean withState) {
		Path out = p.run().plumOutput();
		List<String> steps = new ArrayList<>();
		steps.add("wait=" + DoneFileWaiter.doneFile(p.run().production(), START));
		steps.add("copy=" + resource("plum_all1990_extract") + "*" + out);
		steps.add("write=" + DoneFileWaiter.doneFile(out, FINAL_YEAR));
		if (withState) {
			CalibrationCheck.PLUM_STATE_FILES.forEach(f -> steps.add("write=" + out.resolve(f)));
		}
		return steps;
	}

	/** A calibration run that goes well. */
	static LandSymmLauncher.ModelCommands calibrationModels() {
		return new LandSymmLauncher.ModelCommands() {
			@Override
			public List<String> crafty(LandSymmLauncher.Prepared p, Path jar) {
				return FakeModel.command(craftyStartYear(p).toArray(String[]::new));
			}

			@Override
			public List<String> plum(LandSymmLauncher.Prepared p) {
				return FakeModel.command(plumCalibration(p, true).toArray(String[]::new));
			}
		};
	}

	/** CRAFTY's side of a scenario run from {@code first} to {@code last}, taking turns with PLUM. */
	static List<String> craftyScenario(LandSymmLauncher.Prepared p, int first, int last) {
		List<String> steps = new ArrayList<>();
		for (int year = first; year <= last; year++) {
			Path folder = p.run().production().resolve(String.valueOf(year));
			steps.add("copy=" + resource("production_1990_extract/production.csv") + "*"
					+ folder.resolve("production.csv"));
			steps.add("write=" + folder.resolve("done"));
			if (year < last) {
				steps.add("wait=" + DoneFileWaiter.doneFile(p.run().plumOutput(), year));
			}
		}
		return steps;
	}

	/** PLUM's side of a scenario run from {@code first} to {@code last}. */
	static List<String> plumScenario(LandSymmLauncher.Prepared p, int first, int last) {
		List<String> steps = new ArrayList<>();
		for (int year = first; year <= last; year++) {
			steps.add("wait=" + DoneFileWaiter.doneFile(p.run().production(), year));
			steps.add("write=" + DoneFileWaiter.doneFile(p.run().plumOutput(), year));
		}
		return steps;
	}

	/** A scenario run that goes well. */
	static LandSymmLauncher.ModelCommands scenarioModels(int first, int last) {
		return new LandSymmLauncher.ModelCommands() {
			@Override
			public List<String> crafty(LandSymmLauncher.Prepared p, Path jar) {
				return FakeModel.command(craftyScenario(p, first, last).toArray(String[]::new));
			}

			@Override
			public List<String> plum(LandSymmLauncher.Prepared p) {
				return FakeModel.command(plumScenario(p, first, last).toArray(String[]::new));
			}
		};
	}

	/** Models with these scripts. */
	static LandSymmLauncher.ModelCommands models(java.util.function.Function<LandSymmLauncher.Prepared, List<String>> crafty,
			java.util.function.Function<LandSymmLauncher.Prepared, List<String>> plum) {
		return new LandSymmLauncher.ModelCommands() {
			@Override
			public List<String> crafty(LandSymmLauncher.Prepared p, Path jar) {
				return FakeModel.command(crafty.apply(p).toArray(String[]::new));
			}

			@Override
			public List<String> plum(LandSymmLauncher.Prepared p) {
				return FakeModel.command(plum.apply(p).toArray(String[]::new));
			}
		};
	}

	/** Makes a complete calibration run with the launcher, and returns its folder. */
	Path calibrationRun(String runName) throws IOException {
		Path master = master(runName + ".yaml", calibrationMaster(runName, ""));
		int code = launcher(calibrationModels()).run(new String[] { master.toString() });
		if (code != 0) {
			throw new IllegalStateException("The fixture's calibration run failed:\n" + printed());
		}
		return outputRoot.resolve(runName);
	}
}
