package de.cesr.crafty.core.dataLoader.afts;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.crafty.Aft;
import de.cesr.crafty.core.crafty.IndexedValues;
import de.cesr.crafty.core.dataLoader.CsvProcessors;
import de.cesr.crafty.core.updaters.Timestep;
import de.cesr.crafty.core.utils.file.PathTools;

/**
 * Matrix give-in ({@code use_matrix_give_in}): each owning AFT's give-in threshold against each
 * challenging AFT, read from an owner x challenger matrix.
 *
 * File format: rows are owners and columns are challengers, both by AFT label; the first header
 * cell is ignored. It is read with {@link CsvProcessors#readCsvToMatrixMap(Path, boolean)}, so a
 * value that is not a number reads as 0, as in the other matrix files. A blank cell is left out,
 * and that pair falls back to the owner's givingInDistributionMean.
 *
 * Files: a year can have its own matrix, {@code Aft_give_in_matrix_<year>.csv}; a year without one
 * uses the static {@code Aft_give_in_matrix.csv}. They are looked for in
 * {@code aft_behaviour_parameters_directory} when that is set, otherwise in the project's
 * {@code agents/default_behaviour} folder. The run stops at startup if a simulation year would have
 * no matrix, or if a file name is found twice.
 *
 * Each AFT's row is held on the AFT itself ({@link Aft#getGiveInRow()}), keyed by challenger
 * label. A cell reaches it through its owner, so nothing is stored per cell and the row always
 * follows whoever owns the cell, however it got there.
 */
public class AftGiveInMatrix {

	private static final CustomLogger LOGGER = new CustomLogger(AftGiveInMatrix.class);

	static final String STATIC_FILE_NAME = "Aft_give_in_matrix.csv";
	private static final Pattern YEAR_FILE_NAME = Pattern.compile("Aft_give_in_matrix_(\\d{4})\\.csv");

	// One position per challenger label, shared by every AFT's row.
	private static final IndexedValues.Index CHALLENGERS = new IndexedValues.Index();

	private static Path staticMatrix = null;
	private static Map<Integer, Path> yearMatrices = new TreeMap<>();
	// The file whose rows the AFTs hold now, so a year that uses the same file does not re-read it.
	private static Path loadedMatrix = null;

	/** The matrix files found for a run, and anything that stops them being used. */
	record MatrixFiles(Path staticMatrix, Map<Integer, Path> byYear, List<String> problems) {
	}

	/**
	 * Finds the matrix files and gives every AFT its row for the start year. With the switch off,
	 * it only clears the rows, so a second run in the same JVM starts clean.
	 */
	public static void initialize() {
		staticMatrix = null;
		yearMatrices = new TreeMap<>();
		loadedMatrix = null;
		AFTsLoader.getAftHash().values().forEach(a -> a.setGiveInRow(null));

		if (!ConfigLoader.config.use_matrix_give_in) {
			return;
		}

		Path configured = configuredDirectory();
		List<Path> candidates;
		if (configured != null) {
			candidates = PathTools.findAllFilePaths(configured);
		} else {
			List<Path> inFolder = PathTools.fileFilter(PathTools.asFolder("agents"),
					PathTools.asFolder("default_behaviour"));
			candidates = inFolder == null ? List.of() : inFolder;
		}

		MatrixFiles files = findMatrixFiles(candidates, Timestep.getStartYear(), Timestep.getEndtYear());
		if (!files.problems().isEmpty()) {
			String where = configured != null ? configured.toString() : "the project's agents/default_behaviour folder";
			LOGGER.fatal("use_matrix_give_in is on, but in " + where + ": " + String.join("; ", files.problems()));
			return;
		}
		staticMatrix = files.staticMatrix();
		yearMatrices = files.byYear();
		LOGGER.info("Matrix give-in: static matrix " + staticMatrix + ", year matrices " + yearMatrices.keySet());

		updateForYear(Timestep.getStartYear());
	}

	/**
	 * Gives every AFT its row from the year's matrix: the year's own file if there is one,
	 * otherwise the static one. Nothing is read if the AFTs already hold that file's rows.
	 */
	public static void updateForYear(int year) {
		Path path = matrixFor(year);
		if (path == null || path.equals(loadedMatrix)) {
			return;
		}
		applyRows(CsvProcessors.readCsvToMatrixMap(path, true), AFTsLoader.getAftHash().values(), path);
		loadedMatrix = path;
		LOGGER.info("Matrix give-in for " + year + ": " + path);
	}

	/** The matrix file for the year, or null if matrix give-in was not initialised. */
	static Path matrixFor(int year) {
		return yearMatrices.getOrDefault(year, staticMatrix);
	}

	/** aft_behaviour_parameters_directory when it names a directory, as for the AftParams files. */
	private static Path configuredDirectory() {
		String configured = ConfigLoader.config.aft_behaviour_parameters_directory;
		if (configured == null || configured.isBlank() || !Paths.get(configured).toFile().isDirectory()) {
			return null;
		}
		return Paths.get(configured);
	}

	/**
	 * Picks the static and year matrices out of the candidate files, by exact file name. Year
	 * files outside the simulation years are ignored. Kept free of logging and config because
	 * LOGGER.fatal exits the JVM and so cannot be exercised from a test.
	 */
	static MatrixFiles findMatrixFiles(Collection<Path> candidates, int startYear, int endYear) {
		List<Path> statics = new ArrayList<>();
		Map<Integer, List<Path>> byYear = new TreeMap<>();
		for (Path p : candidates) {
			String name = p.getFileName().toString();
			if (name.equals(STATIC_FILE_NAME)) {
				statics.add(p);
				continue;
			}
			Matcher m = YEAR_FILE_NAME.matcher(name);
			if (m.matches()) {
				int year = Integer.parseInt(m.group(1));
				if (year >= startYear && year <= endYear) {
					byYear.computeIfAbsent(year, y -> new ArrayList<>()).add(p);
				}
			}
		}

		List<String> problems = new ArrayList<>();
		if (statics.size() > 1) {
			problems.add("more than one " + STATIC_FILE_NAME + ": " + statics);
		}
		byYear.forEach((year, paths) -> {
			if (paths.size() > 1) {
				problems.add("more than one give-in matrix for " + year + ": " + paths);
			}
		});
		if (statics.isEmpty()) {
			List<Integer> uncovered = IntStream.rangeClosed(startYear, endYear).filter(y -> !byYear.containsKey(y))
					.boxed().toList();
			if (!uncovered.isEmpty()) {
				problems.add("there is no " + STATIC_FILE_NAME + ", and no year matrix for " + uncovered);
			}
		}

		Map<Integer, Path> chosen = new TreeMap<>();
		byYear.forEach((year, paths) -> chosen.put(year, paths.get(0)));
		return new MatrixFiles(statics.isEmpty() ? null : statics.get(0), chosen, problems);
	}

	/**
	 * Gives every AFT a new row from the matrix (keys "owner|challenger"). An AFT with no row in
	 * the matrix gets an empty one, so all its pairs fall back to its givingInDistributionMean.
	 * Labels that are not AFTs are ignored, with a warning.
	 */
	static void applyRows(Map<String, Double> matrix, Collection<Aft> afts, Path source) {
		Map<String, IndexedValues> rows = new HashMap<>();
		for (Aft a : afts) {
			rows.put(a.getLabel(), new IndexedValues(CHALLENGERS));
		}
		Set<String> unknown = new TreeSet<>();
		matrix.forEach((key, value) -> {
			int bar = key.indexOf('|');
			String owner = key.substring(0, bar);
			String challenger = key.substring(bar + 1);
			IndexedValues row = rows.get(owner);
			if (row == null) {
				unknown.add(owner);
			} else if (!rows.containsKey(challenger)) {
				unknown.add(challenger);
			} else {
				row.put(challenger, value);
			}
		});
		for (Aft a : afts) {
			a.setGiveInRow(rows.get(a.getLabel()));
		}
		if (!unknown.isEmpty()) {
			LOGGER.warn("Matrix give-in: labels in " + source + " that are not AFTs, ignored: " + unknown);
		}
	}
}
