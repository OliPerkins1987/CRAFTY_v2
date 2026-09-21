package de.cesr.crafty.react.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds a year's file in a "year folder": a folder holding one .csv per year, with any file name.
 *
 * The file for year Y is the .csv whose name contains Y as a number on its own: {@code EU_capitals_2020.csv}
 * and {@code x2020y.csv} match 2020; {@code data_20201.csv} and {@code 12020.csv} do not. There must be
 * exactly one such file; none, or more than one, is an error that names what was found.
 *
 * Searching by name is safe here, unlike in core's run folder (whose name contains the year), because
 * these folders hold only react's per-year inputs and the one-file rule catches any clash.
 */
public final class YearFileFinder {

	private YearFileFinder() {
	}

	/** The one file for a year. */
	public static Path find(Path folder, int year) {
		List<Path> csvFiles = csvFilesIn(folder);
		List<Path> matches = matching(csvFiles, year);
		String problem = problem(folder, year, matches);
		if (problem != null) {
			throw new ReactInputException(problem);
		}
		return matches.get(0);
	}

	/**
	 * The file for every year from {@code firstYear} to {@code lastYear}. Every year is checked before
	 * anything is reported, so one message lists all the problems.
	 */
	public static Map<Integer, Path> findAll(Path folder, int firstYear, int lastYear) {
		List<Path> csvFiles = csvFilesIn(folder);
		Map<Integer, Path> files = new LinkedHashMap<>();
		List<String> problems = new ArrayList<>();
		for (int year = firstYear; year <= lastYear; year++) {
			List<Path> matches = matching(csvFiles, year);
			String problem = problem(folder, year, matches);
			if (problem == null) {
				files.put(year, matches.get(0));
			} else {
				problems.add(problem);
			}
		}
		if (!problems.isEmpty()) {
			throw new ReactInputException(String.join("\n", problems));
		}
		return files;
	}

	/** Whether a file name contains the year as a number on its own. */
	static boolean nameHasYear(String fileName, int year) {
		return Pattern.compile("(?<!\\d)" + year + "(?!\\d)").matcher(fileName).find();
	}

	private static List<Path> csvFilesIn(Path folder) {
		if (!Files.isDirectory(folder)) {
			throw new ReactInputException("Folder not found: " + folder);
		}
		try (Stream<Path> entries = Files.list(folder)) {
			return entries.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv"))
					.sorted()
					.toList();
		} catch (IOException e) {
			throw new ReactInputException("Could not list " + folder + ": " + e.getMessage(), e);
		}
	}

	private static List<Path> matching(List<Path> csvFiles, int year) {
		return csvFiles.stream().filter(p -> nameHasYear(p.getFileName().toString(), year)).toList();
	}

	private static String problem(Path folder, int year, List<Path> matches) {
		if (matches.isEmpty()) {
			return "No .csv file for " + year + " in " + folder;
		}
		if (matches.size() > 1) {
			return "More than one .csv file for " + year + " in " + folder + ": "
					+ matches.stream().map(p -> p.getFileName().toString()).toList();
		}
		return null;
	}
}
