package de.cesr.crafty.core.utils.file;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.dataLoader.ProjectLoader;

	/**
	 * File-system helper utilities used by CRAFTY for discovering input/output resources and writing small text files.
	 *
	 * This class centralises common path operations needed during model initialization and coupling workflows, such as:
	 * - Listing subdirectories (e.g., for PLUM coupling inputs) via {@link #listSubdirectories(Path)}.
	 * - Recursively collecting all file paths under a root directory via {@link #findAllFilePaths(Path)}.
	 * - Filtering a pre-built list of project files using simple substring conditions
	 *   (see {@link #fileFilter(String...)} and overloads). This is primarily used to locate expected input
	 *   files inside the project data tree provided by {@link ProjectLoader}.
	 * - Picking one region's file (demand, utility weights) from those matches by the region name the file
	 *   name ends with, via {@link #regionFile(List, String, Collection)}.
	 * - Writing plain text to disk with optional append mode via {@link #writeFile(String, String, boolean)}.
	 * - Detecting direct child folders and walking all nested folders under a root path
	 *   ({@link #detectFolders(String)} and {@link #getAllFolders(String)}).
	 * - Ensuring output directories exist via {@link #makeDirectory(String)}.
	 *
	 * Notes:
	 * - The file filtering methods match by substring presence; they do not interpret glob/regex patterns.
	 * - Some methods return null on failure (instead of throwing) to allow the caller to decide how strict
	 *   missing/optional files should be.
	 * - Logging is handled through {@link CustomLogger} and respects runtime logging flags.
	 */
	
	/**
	 * @author Mohamed Byari
	 *
	 */

public class PathTools {

	private static final CustomLogger LOGGER = new CustomLogger(PathTools.class);

	public static Set<Path> listSubdirectories(Path directoryPath) {// used for Plum coupling
		// Use try-with-resources to ensure the stream is closed properly
		try (Stream<Path> paths = Files.list(directoryPath)) {
			return paths.filter(Files::isDirectory) // Filter to include only directories
					.collect(Collectors.toSet()); // Collect results into a set to eliminate duplicates
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
	}

	public static String asFolder(String input) {
		return File.separator + input + File.separator;
	}

	static void creatListPaths(final File folder, ArrayList<Path> Listpathe) {
		try {
			for (final File fileEntry : folder.listFiles()) {
				if (fileEntry.isDirectory()) {
					creatListPaths(fileEntry, Listpathe);
				} else {
					Listpathe.add(fileEntry.toPath());
				}
			}
		} catch (NullPointerException e) {
//			LOGGER.fatal(" \n Fatal error. Project folder Path not found " + folder + ":  " + e.getMessage());
		}
	}

	public static ArrayList<Path> fileFilter(String... condition) {
		return fileFilter(false, condition);
	}

	public static ArrayList<Path> fileFilter(boolean ignoreIfFileNotExists, String... condition) {
		return fileFilter(ProjectLoader.getAllfilesPathInData(), ignoreIfFileNotExists, condition);
	}

	public static ArrayList<Path> fileFilter(ArrayList<Path> getAllfilesPathInFolder, String... condition) {
		return fileFilter(getAllfilesPathInFolder, false, condition);
	}

	public static ArrayList<Path> fileFilter(List<Path> getAllfilesPathInFolder, boolean ignoreIfFileNotExists,
			String... condition) {

		ArrayList<Path> turn = new ArrayList<>();
		getAllfilesPathInFolder.forEach(e -> {
			boolean testCodition = true;
			for (int j = 0; j < condition.length; j++) {
				if (!e.toString().contains(condition[j])) {
					testCodition = false;
					break;
				}
			}
			if (testCodition)
				turn.add(e);
		});
		String str = "";
		for (int j = 0; j < condition.length; j++) {
			str = condition[j] + " " + str;
		}

		if (turn.size() == 0) {
			if (ignoreIfFileNotExists) {
				LOGGER.warn(" File ignored because there is no file in its path with all these key worlds : "
						+ Arrays.toString(condition));
				return null;
			}
			return null;
		} else {
			return turn;
		}
	}

	/**
	 * Picks one region's file (e.g. its demand or utility-weight file) from candidate paths.
	 *
	 * A file belongs to the region whose name its file name ends with: {@code <anything>_<region>.csv}. If it ends
	 * with two region names, it belongs to the longer one, so {@code hist_demand_South_Sudan.csv} belongs to
	 * {@code South_Sudan}, not {@code Sudan}.
	 * - The region's own file is returned. If there are several (the same name in two folders), the first is
	 *   returned, with a warning.
	 * - If it has none, the first candidate that belongs to no region (e.g. {@code demand_regionA_v2.csv}) is
	 *   returned, with a warning. A file that belongs to another region is never returned.
	 * - Otherwise, and for null or empty candidates, null.
	 *
	 * @param candidates paths matched by the caller's substring rule (see {@link #fileFilter})
	 * @param region     the region to find a file for
	 * @param regions    all region names, used to tell whose file each candidate is
	 * @return the region's file, or null
	 */
	public static Path regionFile(List<Path> candidates, String region, Collection<String> regions) {
		if (candidates == null || candidates.isEmpty()) {
			return null;
		}
		Set<String> names = new HashSet<>(regions);
		names.add(region);

		List<Path> own = new ArrayList<>();
		Path unowned = null;
		for (Path p : candidates) {
			String owner = owner(p, names);
			if (region.equals(owner)) {
				own.add(p);
			} else if (owner == null && unowned == null) {
				unowned = p;
			}
		}
		if (!own.isEmpty()) {
			if (own.size() > 1) {
				LOGGER.warn("Several files for region |" + region + "|, using the first: " + own);
			}
			return own.get(0);
		}
		if (unowned != null) {
			LOGGER.warn("No file ends with _" + region + ".csv, so region |" + region + "| uses " + unowned);
		}
		return unowned;
	}

	/** The longest region name that the file name ends with as {@code _<region>.csv}, or null. */
	static String owner(Path path, Collection<String> regions) {
		String name = path.getFileName().toString();
		String owner = null;
		for (String r : regions) {
			if (name.endsWith("_" + r + ".csv") && (owner == null || r.length() > owner.length())) {
				owner = r;
			}
		}
		return owner;
	}

	public static ArrayList<Path> findAllFilePaths(Path path) {
		ArrayList<Path> Listpathe = new ArrayList<>();
		final File folder = path.toFile();
		creatListPaths(folder, Listpathe);
		return Listpathe;
	}

	static public void writeFile(String path, String text, boolean keepTxt) {
		File file = new File(path);
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, keepTxt))) {
			writer.write(text);
		} catch (IOException ex) {
			LOGGER.error("Error writing to file: " + ex.getMessage());
		}
	}

	public static List<File> detectFolders(String folderPath) {
		List<File> filePaths = new ArrayList<>();
		File folder = new File(folderPath);
		// Check if the folder exists and is a directory
		if (folder.exists() && folder.isDirectory()) {
			File[] files = folder.listFiles();
			// Iterate over the files in the folder
			for (File file : files) {
				// Check if it is a directory
				if (file.isDirectory()) {
					filePaths.add(file);
				}
			}
		} else {
			LOGGER.warn("Folder not found: " + folderPath);
		}
		return filePaths;
	}

	public static List<Path> getAllFolders(String rootFolder) {
		Path root = Paths.get(rootFolder);
		try {
			if (!Files.isDirectory(root)) {
				throw new IllegalArgumentException("Provided path is not a directory: " + rootFolder);
			}
			try (Stream<Path> stream = Files.walk(root)) {
				return stream.filter(Files::isDirectory) // Include only directories
						.filter(path -> !path.equals(root)) // Exclude the root folder itself
						.collect(Collectors.toList());
			}
		} catch (IOException e) {
			return null;
		}
	}

	public static String makeDirectory(String dir) {
		if (dir == null) {
			return null;
		}
		File directory = new File(dir);
		if (!directory.exists()) {
			// Use mkdirs() if you want to create all parent dirs automatically
			boolean created = directory.mkdirs();
			if (!created) {
				return null;
			}
		}
		return dir;
	}
	
	public static String readFileToString(Path path) {
		try {
			return Files.readString(path, StandardCharsets.UTF_8);
		} catch (IOException e) {
			e.printStackTrace();
		}
		return "";
	}

}
