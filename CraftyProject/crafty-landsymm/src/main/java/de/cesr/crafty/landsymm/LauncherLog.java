package de.cesr.crafty.landsymm;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The launcher's log: each line, with the time, goes to the console and, once the run folder exists, to
 * launcher.log. Lines from before the folder exists are written to the file when it's set.
 */
final class LauncherLog {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

	private final PrintStream console;
	private final List<String> early = new ArrayList<>();
	private Path file;

	LauncherLog(PrintStream console) {
		this.console = console;
	}

	/** Logs a message; each of its lines is printed with the time. */
	synchronized void line(String message) {
		String time = LocalTime.now().format(TIME);
		List<String> lines = new ArrayList<>();
		message.lines().forEach(l -> lines.add(time + "  " + l));
		lines.forEach(console::println);
		if (file == null) {
			early.addAll(lines);
		} else {
			append(lines);
		}
	}

	/** From now on, lines also go to this file; the lines logged so far are written to it first. */
	synchronized void toFile(Path file) {
		this.file = file;
		append(early);
		early.clear();
	}

	private void append(List<String> lines) {
		try {
			Files.write(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			console.println("Could not write to " + file + ": " + e);
		}
	}
}
