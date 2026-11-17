package de.cesr.crafty.landsymm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * A run's {@code run.yaml} (48e PLAN section 7): what the launcher resolved and how the run ended. A scenario run
 * reads its calibration run's.
 *
 * It's written at the start and at the end, each time to a temporary file that's then moved into place. Paths inside
 * the run folder, and to the shared jars folder beside it, are relative, so a run folder can be moved with its
 * output root and still be named as a calibration_run.
 */
final class RunRecord {

	static final String FILE = "run.yaml";
	static final String CALIBRATION = "calibration";
	static final String SCENARIO = "scenario";
	static final String RUNNING = "running";
	static final String COMPLETE = "complete";
	static final String FAILED = "failed";
	static final String INTERRUPTED = "interrupted";

	private static final String HEADER = "# Written by LandSymmLauncher (crafty-landsymm). A scenario run reads this "
			+ "when it names this run as its\n# calibration_run. Relative paths are relative to this folder.\n";

	private final Map<String, Object> root;

	RunRecord() {
		this(new LinkedHashMap<>());
	}

	private RunRecord(Map<String, Object> root) {
		this.root = root;
	}

	/** Sets a top-level value. */
	synchronized RunRecord put(String key, Object value) {
		root.put(key, value);
		return this;
	}

	/** A block of values, made if it isn't there yet. */
	@SuppressWarnings("unchecked")
	synchronized Map<String, Object> section(String name) {
		return (Map<String, Object>) root.computeIfAbsent(name, k -> new LinkedHashMap<String, Object>());
	}

	/** A value by its path of keys, e.g. ("calibration", "final_year"); null if it isn't there. */
	synchronized Object get(String... path) {
		Object value = root;
		for (String key : path) {
			if (!(value instanceof Map<?, ?> map)) {
				return null;
			}
			value = map.get(key);
		}
		return value;
	}

	String text(String... path) {
		Object value = get(path);
		return value == null ? null : String.valueOf(value);
	}

	Integer number(String... path) {
		return get(path) instanceof Integer i ? i : null;
	}

	/** Writes {@code run.yaml} into the folder, through a temporary file. */
	synchronized void write(Path folder) {
		DumperOptions options = new DumperOptions();
		options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
		options.setIndent(2);
		options.setWidth(160);
		String text = HEADER + new Yaml(options).dump(root);
		Path file = folder.resolve(FILE);
		Path temp = folder.resolve(FILE + ".tmp");
		try {
			Files.writeString(temp, text, StandardCharsets.UTF_8);
			try {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			throw new CouplingInputException("Could not write " + file + ": " + e);
		}
	}

	/** Reads a run folder's {@code run.yaml}. */
	static RunRecord read(Path folder) {
		Path file = folder.resolve(FILE);
		Object raw;
		try {
			raw = new Yaml(new SafeConstructor(new LoaderOptions()))
					.load(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new CouplingInputException("Can't read " + file + ": " + e);
		} catch (YAMLException e) {
			throw new CouplingInputException(file + " isn't valid YAML: " + e.getMessage());
		}
		if (!(raw instanceof Map<?, ?> map)) {
			throw new CouplingInputException(file + " isn't a run record");
		}
		Map<String, Object> root = new LinkedHashMap<>();
		map.forEach((k, v) -> root.put(String.valueOf(k), v));
		return new RunRecord(root);
	}

	/**
	 * A path as run.yaml holds it: relative inside the run folder or the jars folder beside it, otherwise absolute,
	 * always with forward slashes.
	 */
	static String pathText(Path runFolder, Path path) {
		Path p = path.toAbsolutePath().normalize();
		Path run = runFolder.toAbsolutePath().normalize();
		Path jars = run.getParent() == null ? null : run.getParent().resolve("jars");
		if (p.startsWith(run) || (jars != null && p.startsWith(jars))) {
			return run.relativize(p).toString().replace('\\', '/');
		}
		return p.toString().replace('\\', '/');
	}

	/** A path from run.yaml, resolved against the run folder if it's relative. */
	static Path resolve(Path runFolder, String text) {
		Path p = Paths.get(text);
		return p.isAbsolute() ? p.normalize() : runFolder.resolve(p).toAbsolutePath().normalize();
	}
}
