package de.cesr.crafty.landsymm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The coupler's own output files, in {@code plum_coupling/} inside CRAFTY's output folder for the run.
 *
 * A yearly file is cumulative: it is started afresh with its header when the run starts, then appended to and closed
 * every year. So a run that stops keeps the years it finished, and a re-used output folder doesn't mix two runs.
 */
final class CouplingOutputs {

	static final String FOLDER = "plum_coupling";

	private final Path folder;
	private final Map<String, Integer> columns = new HashMap<>();

	/** The outputs in a folder, which is made if it doesn't exist. */
	CouplingOutputs(Path folder) {
		this.folder = folder;
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

	Path folder() {
		return folder;
	}

	/** A file in the folder. */
	Path file(String name) {
		return folder.resolve(name);
	}

	/** Starts a yearly file afresh: its header and nothing else, replacing any earlier file. */
	void start(String name, List<String> header) {
		write(name, line(header), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE);
		columns.put(name, header.size());
	}

	/** Appends rows to a started file. Each row must have as many values as the header. */
	void append(String name, List<List<String>> rows) {
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
