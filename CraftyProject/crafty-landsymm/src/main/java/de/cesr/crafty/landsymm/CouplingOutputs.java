package de.cesr.crafty.landsymm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import de.cesr.crafty.core.cli.ConfigLoader;

/**
 * The coupler's own output files, in {@code plum_coupling/} inside CRAFTY's output folder for the run.
 *
 * A yearly file is cumulative: it is started afresh with its header when the run starts, then appended to and closed
 * every year. So a run that stops keeps the years it finished, and a re-used output folder doesn't mix two runs.
 *
 * Each yearly file can be switched off with config plum_coupling_outputs, which names the ones to write (48e). A file
 * that's off is never written, and the steps still do all their work. Files written once with {@link #file(String)}
 * (price_factors.csv, baseline_check.csv) have no switch.
 */
final class CouplingOutputs {

	static final String FOLDER = "plum_coupling";

	private final Path folder;
	private final Set<String> switchedOff;
	private final Map<String, Integer> columns = new HashMap<>();

	/** The outputs in a folder, which is made if it doesn't exist, with every yearly file written. */
	CouplingOutputs(Path folder) {
		this(folder, ConfigLoader.PLUM_COUPLING_OUTPUTS);
	}

	/**
	 * The outputs in a folder, which is made if it doesn't exist, writing only the yearly files named in {@code on}
	 * (plum_coupling_outputs names: a file's name without .csv).
	 */
	CouplingOutputs(Path folder, Collection<?> on) {
		this.folder = folder;
		Set<String> off = new TreeSet<>(ConfigLoader.PLUM_COUPLING_OUTPUTS);
		for (Object name : on) {
			off.remove(String.valueOf(name));
		}
		this.switchedOff = Collections.unmodifiableSet(off);
		try {
			Files.createDirectories(folder);
		} catch (IOException e) {
			throw new CouplingInputException("Could not make the coupler's output folder " + folder + ": " + e);
		}
	}

	/** The outputs in {@code plum_coupling/} inside CRAFTY's output folder (config output_folder_name, once set). */
	static CouplingOutputs inOutputFolder(String outputFolder) {
		return new CouplingOutputs(Paths.get(outputFolder).resolve(FOLDER));
	}

	/** As {@link #inOutputFolder(String)}, writing only the yearly files named in {@code on} (plum_coupling_outputs). */
	static CouplingOutputs inOutputFolder(String outputFolder, Collection<?> on) {
		return new CouplingOutputs(Paths.get(outputFolder).resolve(FOLDER), on);
	}

	Path folder() {
		return folder;
	}

	/** A file in the folder. */
	Path file(String name) {
		return folder.resolve(name);
	}

	/** The plum_coupling_outputs names of the yearly files that are switched off, in name order. */
	Set<String> switchedOff() {
		return switchedOff;
	}

	/** Whether a file is written: false only for a yearly file switched off in plum_coupling_outputs. */
	boolean isWritten(String name) {
		return !switchedOff.contains(name.endsWith(".csv") ? name.substring(0, name.length() - 4) : name);
	}

	/**
	 * Starts a yearly file afresh: its header and nothing else, replacing any earlier file. A file that's switched off
	 * isn't started, and an earlier copy of it in the folder is deleted, so a re-used folder shows nothing stale.
	 */
	void start(String name, List<String> header) {
		if (!isWritten(name)) {
			try {
				Files.deleteIfExists(file(name));
			} catch (IOException e) {
				throw new CouplingInputException("Could not delete the switched-off " + file(name) + ": " + e);
			}
			return;
		}
		write(name, line(header), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE);
		columns.put(name, header.size());
	}

	/**
	 * Appends rows to a started file. Each row must have as many values as the header. Nothing is written for a file
	 * that's switched off.
	 */
	void append(String name, List<List<String>> rows) {
		if (!isWritten(name)) {
			return;
		}
		Integer expected = columns.get(name);
		if (expected == null) {
			throw new IllegalStateException(name + " was appended to before it was started");
		}
		StringBuilder text = new StringBuilder();
		for (List<String> row : rows) {
			if (row.size() != expected) {
				throw new IllegalStateException(name + ": a row has " + row.size() + " values but the header has "
						+ expected + ": " + row);
			}
			text.append(line(row));
		}
		write(name, text.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
	}

	private void write(String name, String text, StandardOpenOption... options) {
		try {
			Files.writeString(file(name), text, StandardCharsets.UTF_8, options);
		} catch (IOException e) {
			throw new CouplingInputException("Could not write " + file(name) + ": " + e);
		}
	}

	private static String line(List<String> values) {
		StringBuilder line = new StringBuilder();
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				line.append(',');
			}
			line.append(csv(values.get(i)));
		}
		return line.append('\n').toString();
	}

	/** A value for a CSV file: quoted, with quotes doubled, only if it holds a comma, a quote or a line break. */
	static String csv(String value) {
		if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
			return value;
		}
		return '"' + value.replace("\"", "\"\"") + '"';
	}
}
