package de.cesr.crafty.core.utils.file;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathToolsTest {

	@TempDir
	Path tempDir;

	// ---------- listSubdirectories ----------

	@Test
	void listSubdirectoriesShouldReturnOnlyImmediateDirectories() throws IOException {
		Path dirA = Files.createDirectory(tempDir.resolve("A"));
		Path dirB = Files.createDirectory(tempDir.resolve("B"));
		Files.createFile(tempDir.resolve("file.txt"));

		Set<Path> subs = PathTools.listSubdirectories(tempDir);

		assertNotNull(subs);
		assertEquals(2, subs.size());
		assertTrue(subs.contains(dirA));
		assertTrue(subs.contains(dirB));
		// should not contain the file
		assertFalse(subs.contains(tempDir.resolve("file.txt")));
	}

	// ---------- asFolder ----------

	@Test
	void asFolderShouldWrapInputWithFileSeparator() {
		String s = PathTools.asFolder("data");
		assertTrue(s.startsWith(File.separator));
		assertTrue(s.endsWith(File.separator));
		assertTrue(s.contains("data"));
	}

	// ---------- fileFilter (list-based overloads) ----------

	@Test
	void fileFilterWithConditionsShouldReturnOnlyMatchingPaths() {
		ArrayList<Path> all = new ArrayList<>();
		all.add(Paths.get("worlds/capitals.csv"));
		all.add(Paths.get("worlds/restrictions.csv"));
		all.add(Paths.get("GIS/capitals.csv"));

		// Must contain both "worlds" AND "capitals"
		ArrayList<Path> filtered = PathTools.fileFilter(all, "worlds", "capitals");

		assertNotNull(filtered);
		assertEquals(1, filtered.size());
		assertEquals(Paths.get("worlds/capitals.csv"), filtered.get(0));
	}

	@Test
	void fileFilterShouldReturnNullWhenNoMatchesAndIgnoreFalse() {
		ArrayList<Path> all = new ArrayList<>();
		all.add(Paths.get("worlds/capitals.csv"));

		ArrayList<Path> filtered = PathTools.fileFilter(all, false, "GIS"); // no GIS in any path

		assertNull(filtered, "When no match and ignoreIfFileNotExists=false, should return null");
	}

	// ---------- findAllFilePaths / creatListPaths ----------

	@Test
	void findAllFilePathsShouldFindAllFilesRecursively() throws IOException {
		Path sub1 = Files.createDirectory(tempDir.resolve("sub1"));
		Path sub2 = Files.createDirectory(tempDir.resolve("sub2"));
		Path f1 = Files.createFile(tempDir.resolve("rootFile.txt"));
		Path f2 = Files.createFile(sub1.resolve("sub1File.txt"));
		Path f3 = Files.createFile(sub2.resolve("sub2File.txt"));

		ArrayList<Path> paths = PathTools.findAllFilePaths(tempDir);

		assertEquals(3, paths.size());
		assertTrue(paths.contains(f1));
		assertTrue(paths.contains(f2));
		assertTrue(paths.contains(f3));
	}

	// ---------- writeFile ----------

	@Test
	void writeFileShouldOverwriteOrAppendDependingOnFlag() throws IOException {
		Path file = tempDir.resolve("out.txt");

		// First write (overwrite mode has no effect on non-existing file)
		PathTools.writeFile(file.toString(), "hello", false);
		String content1 = Files.readString(file);
		assertEquals("hello", content1);

		// Append
		PathTools.writeFile(file.toString(), " world", true);
		String content2 = Files.readString(file);
		assertEquals("hello world", content2);

		// Overwrite again
		PathTools.writeFile(file.toString(), "new", false);
		String content3 = Files.readString(file);
		assertEquals("new", content3);
	}

	// ---------- detectFolders ----------

	@Test
	void detectFoldersShouldReturnOnlySubdirectories() throws IOException {
		Files.createDirectory(tempDir.resolve("A"));
		Files.createDirectory(tempDir.resolve("B"));
		Files.createFile(tempDir.resolve("file.txt"));

		List<File> dirs = PathTools.detectFolders(tempDir.toString());

		List<String> names = new ArrayList<>();
		dirs.forEach(d -> names.add(d.getName()));

		assertEquals(2, dirs.size());
		assertTrue(names.contains("A"));
		assertTrue(names.contains("B"));
		assertFalse(names.contains("file.txt"));
	}

	// ---------- getAllFolders ----------

	@Test
	void getAllFoldersShouldReturnAllSubdirectoriesRecursively() throws IOException {
		Path sub1 = Files.createDirectory(tempDir.resolve("sub1"));
		Path sub2 = Files.createDirectory(sub1.resolve("sub2"));
		Files.createFile(tempDir.resolve("file.txt"));

		List<Path> folders = PathTools.getAllFolders(tempDir.toString());

		assertNotNull(folders);
		assertTrue(folders.contains(sub1));
		assertTrue(folders.contains(sub2));
		assertFalse(folders.contains(tempDir)); // root excluded by design
	}

	@Test
	void getAllFoldersShouldThrowOnNonDirectory() throws IOException {
		Path file = Files.createFile(tempDir.resolve("notDir.txt"));
		assertThrows(IllegalArgumentException.class, () -> PathTools.getAllFolders(file.toString()));
	}

	// ---------- makeDirectory ----------

	@Test
	void makeDirectoryShouldCreateDirectoryAndReturnPathString() {
		String dirPath = tempDir.resolve("newFolder").toString();

		String result = PathTools.makeDirectory(dirPath);

		assertNotNull(result);
		assertEquals(dirPath, result);
		assertTrue(Files.isDirectory(Paths.get(result)));
	}

	@Test
	void makeDirectoryShouldReturnNullForNullInput() {
		assertNull(PathTools.makeDirectory(null));
	}

	// ---------- regionFile ----------

	private static ArrayList<Path> demandFiles(String... names) {
		ArrayList<Path> files = new ArrayList<>();
		for (String n : names) {
			files.add(Paths.get("demand", n));
		}
		return files;
	}

	// what the demand loader does: candidates containing "_<region>", then regionFile
	private static Path demandFile(ArrayList<Path> files, String region, Collection<String> regions) {
		return PathTools.regionFile(PathTools.fileFilter(files, "_" + region), region, regions);
	}

	@Test
	void regionFileGivesSudanAndSouthSudanTheirOwnFilesInEitherOrder() {
		List<String> regions = List.of("Sudan", "South_Sudan");
		ArrayList<Path> files = demandFiles("hist_demand_South_Sudan.csv", "hist_demand_Sudan.csv");

		for (int i = 0; i < 2; i++) {
			assertEquals(Paths.get("demand", "hist_demand_Sudan.csv"), demandFile(files, "Sudan", regions));
			assertEquals(Paths.get("demand", "hist_demand_South_Sudan.csv"), demandFile(files, "South_Sudan", regions));
			Collections.reverse(files);
		}
	}

	@Test
	void regionFileGivesEachGuineaItsOwnFile() {
		List<String> regions = List.of("Guinea", "Equatorial_Guinea", "Guinea_Bissau", "Papua_New_Guinea");
		ArrayList<Path> files = demandFiles("hist_demand_Equatorial_Guinea.csv", "hist_demand_Guinea.csv",
				"hist_demand_Guinea_Bissau.csv", "hist_demand_Papua_New_Guinea.csv");

		for (String r : regions) {
			assertEquals(Paths.get("demand", "hist_demand_" + r + ".csv"), demandFile(files, r, regions));
		}
	}

	@Test
	void regionFileSeparatesNigerAndNigeriaWithBareNameCandidates() {
		// the weight loader's scenario-folder lookup matches the bare region name
		List<String> regions = List.of("Niger", "Nigeria");
		ArrayList<Path> files = demandFiles("Utility_Weight_Nigeria.csv", "Utility_Weight_Niger.csv");

		assertEquals(Paths.get("demand", "Utility_Weight_Niger.csv"),
				PathTools.regionFile(PathTools.fileFilter(files, "Niger"), "Niger", regions));
		assertEquals(Paths.get("demand", "Utility_Weight_Nigeria.csv"),
				PathTools.regionFile(PathTools.fileFilter(files, "Nigeria"), "Nigeria", regions));
	}

	@Test
	void regionFileNeverReturnsAnotherRegionsFile() {
		// "_India" is inside Madagascar_and_Indian_Ocean's file name; "_Sudan" inside South_Sudan's
		List<String> regions = List.of("India", "Madagascar_and_Indian_Ocean", "Sudan", "South_Sudan");
		ArrayList<Path> files = demandFiles("hist_demand_Madagascar_and_Indian_Ocean.csv",
				"hist_demand_South_Sudan.csv");

		assertNull(demandFile(files, "India", regions));
		assertNull(demandFile(files, "Sudan", regions));
	}

	@Test
	void regionFileFallsBackToAFileNoRegionOwns() {
		ArrayList<Path> files = demandFiles("demand_regionA_v2.csv", "demand_regionB.csv");

		assertEquals(Paths.get("demand", "demand_regionA_v2.csv"),
				demandFile(files, "regionA", List.of("regionA", "regionB")));
		// once a region called regionA_v2 exists, the file is its own and regionA has none
		assertNull(demandFile(files, "regionA", List.of("regionA", "regionB", "regionA_v2")));
	}

	@Test
	void regionFilePrefersTheFileEndingInTheRegionName() {
		List<String> regions = List.of("regionA");
		ArrayList<Path> files = demandFiles("demand_regionA_old.csv", "demand_regionA.csv");

		for (int i = 0; i < 2; i++) {
			assertEquals(Paths.get("demand", "demand_regionA.csv"), demandFile(files, "regionA", regions));
			Collections.reverse(files);
		}
	}

	@Test
	void regionFileTakesTheFirstOfSeveralOwnFiles() {
		ArrayList<Path> files = new ArrayList<>(
				List.of(Paths.get("ssp1", "demand_regionA.csv"), Paths.get("ssp2", "demand_regionA.csv")));

		assertEquals(Paths.get("ssp1", "demand_regionA.csv"), demandFile(files, "regionA", List.of("regionA")));
	}

	@Test
	void regionFileReturnsNullForNoCandidates() {
		assertNull(PathTools.regionFile(null, "regionA", List.of("regionA")));
		assertNull(PathTools.regionFile(new ArrayList<>(), "regionA", List.of("regionA")));
	}

	@Test
	void regionFileGivesEveryPlumCountryItsOwnDemandFile() {
		List<String> regions = List.of(
			"Afghanistan", "Albania", "Algeria", "Angola", "Argentina", "Armenia", "Australia", "Austria", "Azerbaijan",
			"Bahrain_and_Qatar", "Bangladesh", "Belarus", "Belgium_and_Luxembourg", "Benin", "Bhutan", "Bolivia",
			"Bosnia_and_Herzegovina", "Botswana", "Brazil", "Bulgaria", "Burkina_Faso", "Burundi", "Cambodia",
			"Cameroon", "Canada", "Caribbean_Other", "Central_African_Republic", "Chad", "Chile", "China", "Colombia",
			"Congo", "Costa_Rica", "Cote_dIvoire", "Croatia", "Cuba", "Cyprus", "Czechia", "DR_Congo", "Denmark",
			"Djibouti", "Dominican_Republic", "Ecuador", "Egypt", "El_Salvador", "Equatorial_Guinea", "Eritrea",
			"Estonia", "Eswatini", "Ethiopia", "Finland", "France", "Gabon_and_Sao_Tome", "Georgia", "Germany", "Ghana",
			"Greece", "Guatemala_and_Belize", "Guinea", "Guinea_Bissau", "Guyana", "Haiti", "Honduras", "Hungary",
			"Iceland", "India", "Indonesia", "Iran", "Iraq", "Ireland", "Israel", "Italy_and_Malta", "Japan", "Jordan",
			"Kazakhstan", "Kenya", "Kuwait", "Kyrgyzstan", "Laos", "Latvia", "Lebanon", "Lesotho", "Liberia", "Libya",
			"Lithuania", "Madagascar_and_Indian_Ocean", "Malawi", "Malaysia_Singapore_and_Brunei", "Mali", "Mauritania",
			"Mexico", "Moldova", "Mongolia", "Montenegro", "Morocco", "Mozambique", "Myanmar", "Namibia", "Nepal",
			"Netherlands", "New_Zealand", "Nicaragua", "Niger", "Nigeria", "North_Korea", "North_Macedonia", "Norway",
			"Oman", "Pacific_Other", "Pakistan", "Panama", "Papua_New_Guinea", "Paraguay", "Peru", "Philippines",
			"Poland", "Portugal", "Romania", "Russia", "Rwanda", "Saudi_Arabia", "Senegal_Gambia_and_Cabo_Verde",
			"Serbia", "Sierra_Leone", "Slovakia", "Slovenia", "Somalia", "South_Africa", "South_Korea", "South_Sudan",
			"Spain", "Sri_Lanka_and_Maldives", "Sudan", "Suriname", "Sweden", "Switzerland", "Syria", "Taiwan",
			"Tajikistan", "Tanzania", "Thailand", "Timor_Leste", "Togo", "Tunisia", "Turkiye", "Turkmenistan", "Uganda",
			"Ukraine", "United_Arab_Emirates", "United_Kingdom", "United_States_of_America", "Uruguay", "Uzbekistan",
			"Venezuela", "Viet_Nam", "Yemen", "Zambia", "Zimbabwe");
		assertEquals(158, regions.size());

		// as written by Make_country_demand.R, in reverse alphabetical order so listing order cannot help
		ArrayList<Path> files = demandFiles("Make_country_demand.R", "demand_country_log.csv",
				"hist_demand_global.csv");
		regions.forEach(r -> files.add(Paths.get("demand", "hist_demand_" + r + ".csv")));
		files.sort(Comparator.reverseOrder());

		for (String r : regions) {
			assertEquals(Paths.get("demand", "hist_demand_" + r + ".csv"), demandFile(files, r, regions), r);
		}
	}
}
