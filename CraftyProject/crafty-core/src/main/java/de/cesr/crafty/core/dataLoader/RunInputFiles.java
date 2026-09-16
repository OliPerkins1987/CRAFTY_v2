package de.cesr.crafty.core.dataLoader;

import java.nio.file.Files;
import java.nio.file.Path;

import de.cesr.crafty.core.cli.CustomLogger;

/**
 * Decides which file the model reads for a per-year input: the capitals file and
 * the spatial cost files.
 *
 * Normally that is simply the input file found at startup. In CRAFTY-react's
 * run-folder mode, react writes its own version of the input files it is
 * responsible for into a folder with the fixed name {@value #RUN_FOLDER_NAME}
 * inside the run's output folder, and the model reads those instead. Which files
 * those are depends on the reactive switches, so each caller says whether react
 * writes the file it is about to read. Each version sits at the same relative
 * path as the original, so the file to read is found by swapping the start of
 * the path:
 *
 * <pre>
 * &lt;project&gt;/worlds/capitals/ssp126/EU_capitals_ssp126_2026.csv
 *   -&gt; &lt;output folder&gt;/reactive_inputs/worlds/capitals/ssp126/EU_capitals_ssp126_2026.csv
 * </pre>
 *
 * The run folder is never searched for matching file names. CRAFTY finds input
 * files by checking whether the whole path contains text such as "_2026", and a
 * folder above the files (the default output folder is named after the date)
 * could contain that text too, making every year's file look like 2026's.
 */
public final class RunInputFiles {

	private static final CustomLogger LOGGER = new CustomLogger(RunInputFiles.class);

	/** The fixed name of the folder, inside the run's output folder, that holds the per-year files. */
	public static final String RUN_FOLDER_NAME = "reactive_inputs";

	/** Files from outside the project folder keep their full path under this sub-folder. */
	static final String EXTERNAL_FOLDER_NAME = "external";

	private static Path runFolder = null;

	private RunInputFiles() {
	}

	/** From now on, read each input file's version in the given run folder. */
	public static void useRunFolder(Path folder) {
		runFolder = folder.toAbsolutePath().normalize();
	}

	/** Go back to reading the original input files. */
	public static void clearRunFolder() {
		runFolder = null;
	}

	public static boolean isUsingRunFolder() {
		return runFolder != null;
	}

	/** The run folder in use, or null when the original input files are read. */
	public static Path getRunFolder() {
		return runFolder;
	}

	/**
	 * The file to read in place of an original input file: its version in the run
	 * folder, or the original itself when no run folder is in use.
	 */
	public static Path resolve(Path original) {
		if (original == null || runFolder == null) {
			return original;
		}
		return runFolderLocation(original, ProjectLoader.getProjectPath(), runFolder);
	}

	/**
	 * The path swap. A file inside the project folder keeps its path relative to the
	 * project folder. A file outside it (for example from a capitals_directory
	 * elsewhere) goes under {@value #EXTERNAL_FOLDER_NAME}, keeping its full path
	 * with the drive letter as a folder name.
	 */
	static Path runFolderLocation(Path original, Path projectFolder, Path runFolder) {
		Path file = original.toAbsolutePath().normalize();
		if (projectFolder != null) {
			Path project = projectFolder.toAbsolutePath().normalize();
			if (file.startsWith(project)) {
				return runFolder.resolve(project.relativize(file));
			}
		}
		Path external = runFolder.resolve(EXTERNAL_FOLDER_NAME);
		Path root = file.getRoot();
		if (root == null) {
			return external.resolve(file);
		}
		String drive = root.toString().replaceAll("[:\\\\/]", "");
		Path withoutRoot = root.relativize(file);
		return drive.isEmpty() ? external.resolve(withoutRoot) : external.resolve(drive).resolve(withoutRoot);
	}

	/**
	 * The file the model should read in place of an original input file.
	 *
	 * @param writtenByReact whether CRAFTY-react writes this file this run. If not,
	 *                       the original is read even when a run folder is in use.
	 *
	 * If react's version should exist but does not, the run stops. Without this a
	 * missing file would go unnoticed: the CSV reader only prints a stack trace, and
	 * the cells would keep the previous year's values.
	 */
	public static Path resolveForReading(Path original, boolean writtenByReact) {
		if (!writtenByReact) {
			return original;
		}
		Path path = resolve(original);
		if (runFolder != null && path != null && !Files.exists(path)) {
			LOGGER.fatal("The model expected " + path + " (in place of " + original + "), but it has not been "
					+ "written. In CRAFTY-react's run-folder mode, react must write each year's files before the "
					+ "model reads them.");
		}
		return path;
	}
}
