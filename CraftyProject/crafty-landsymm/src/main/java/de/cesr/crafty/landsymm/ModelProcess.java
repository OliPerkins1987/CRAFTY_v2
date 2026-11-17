package de.cesr.crafty.landsymm;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One model, CRAFTY or PLUM, run as a child JVM by the launcher (48e PLAN sections 5 and 6): its command, the folder
 * it runs in, the log its console output goes to, and an optional folder put first on its PATH (GAMS, for PLUM).
 */
final class ModelProcess {

	private final String name;
	private final List<String> command;
	private final Path folder;
	private final Path log;
	private final Path firstOnPath;
	private volatile Process process;
	private volatile boolean stoppedByLauncher;

	ModelProcess(String name, List<String> command, Path folder, Path log, Path firstOnPath) {
		this.name = name;
		this.command = List.copyOf(command);
		this.folder = folder;
		this.log = log;
		this.firstOnPath = firstOnPath;
	}

	String name() {
		return name;
	}

	List<String> command() {
		return command;
	}

	Path log() {
		return log;
	}

	/** Starts the process, its console output (both streams) appended to the log. */
	void start() throws IOException {
		Files.createDirectories(folder);
		ProcessBuilder builder = new ProcessBuilder(command).directory(folder.toFile()).redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
		if (firstOnPath != null) {
			Map<String, String> env = builder.environment();
			String key = env.keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
			String old = env.get(key);
			env.put(key, firstOnPath + (old == null || old.isEmpty() ? "" : File.pathSeparator + old));
		}
		process = builder.start();
	}

	boolean started() {
		return process != null;
	}

	long pid() {
		return process == null ? -1 : process.pid();
	}

	boolean isAlive() {
		return process != null && process.isAlive();
	}

	/** The exit code, or null while it runs (or before it starts). */
	Integer exitCode() {
		Process p = process;
		return p == null || p.isAlive() ? null : p.exitValue();
	}

	/** Whether the launcher stopped it, rather than it exiting by itself. */
	boolean stoppedByLauncher() {
		return stoppedByLauncher;
	}

	/**
	 * Stops the process and everything it started. On Windows, stopping a JVM leaves its children running (PLUM's
	 * gams.exe), so the descendants are stopped too. Waits up to {@code wait} for them to go.
	 */
	void stopTree(Duration wait) {
		Process p = process;
		if (p == null || !p.isAlive()) {
			return;
		}
		stoppedByLauncher = true;
		List<ProcessHandle> tree = new ArrayList<>(p.descendants().toList());
		tree.forEach(ProcessHandle::destroyForcibly);
		p.destroyForcibly();
		long deadline = System.nanoTime() + wait.toNanos();
		try {
			p.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
			for (ProcessHandle h : tree) {
				long left = deadline - System.nanoTime();
				if (left > 0 && h.isAlive()) {
					h.onExit().get(left, TimeUnit.NANOSECONDS);
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			// gone or not, there's nothing more to do: the run is already stopping
		}
	}

	/** The log's last lines, to say why a model stopped. */
	List<String> lastLines(int n) {
		try {
			List<String> lines = Files.readAllLines(log, StandardCharsets.ISO_8859_1);
			return lines.subList(Math.max(0, lines.size() - n), lines.size());
		} catch (IOException e) {
			return List.of("(can't read " + log + ": " + e + ")");
		}
	}

	/** The java the launcher itself runs on, which both models run on too. */
	static Path javaExecutable() {
		Path bin = Paths.get(System.getProperty("java.home"), "bin");
		Path exe = bin.resolve("java.exe");
		return Files.isRegularFile(exe) ? exe : bin.resolve("java");
	}

	/** CRAFTY with the coupler: {@code java -Xmx<heap> <args> -cp <jar> MainLandSymm --config-file <config>}. */
	static List<String> craftyCommand(Path java, String heap, List<String> jvmArgs, Path jar, Path config) {
		List<String> c = new ArrayList<>();
		c.add(java.toString());
		c.add("-Xmx" + heap);
		c.addAll(jvmArgs);
		c.add("-cp");
		c.add(jar.toString());
		c.add(MainLandSymm.class.getName());
		c.add("--config-file");
		c.add(config.toString());
		return c;
	}

	/**
	 * PLUM, as its runPlum.sh starts it: {@code java -Xmx<heap> <args> -cp <GAMSJavaAPI.jar><sep><classes>
	 * -DBUILDNUM=<n> -DBUILDVER=<n> -DCONFIG_FILE=<config> ac.ed.lurg.ModelMain}, n being the build folder's name.
	 */
	static List<String> plumCommand(Path java, String heap, List<String> jvmArgs, Path gamsJar, Path classes,
			Path config) {
		String build = classes.getFileName() == null ? "" : classes.getFileName().toString();
		List<String> c = new ArrayList<>();
		c.add(java.toString());
		c.add("-Xmx" + heap);
		c.addAll(jvmArgs);
		c.add("-cp");
		c.add(gamsJar + File.pathSeparator + classes);
		c.add("-DBUILDNUM=" + build);
		c.add("-DBUILDVER=" + build);
		c.add("-DCONFIG_FILE=" + RunConfigWriter.slashes(config));
		c.add("ac.ed.lurg.ModelMain");
		return c;
	}
}
