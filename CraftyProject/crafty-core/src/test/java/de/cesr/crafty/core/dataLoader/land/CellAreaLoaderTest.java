package de.cesr.crafty.core.dataLoader.land;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.core.cli.Config;
import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.crafty.Cell;
import de.cesr.crafty.core.dataLoader.ProjectLoader;

class CellAreaLoaderTest {

	@TempDir
	Path tmp;

	private Config configBackup;
	private String gisPathBackup;
	private ArrayList<Path> allFilesBackup;
	private Map<String, Cell> hashCellBackup;
	private Set<String> regionNamesBackup;
	private Map<String, Integer> regionIdsBackup;
	private String worldNameBackup;
	private boolean regionalisationBackup;

	@BeforeEach
	void backUpStatics() {
		configBackup = ConfigLoader.config;
		if (ConfigLoader.config == null) {
			ConfigLoader.config = new Config();
		}
		gisPathBackup = ConfigLoader.config.gis_path;
		allFilesBackup = ProjectLoader.getAllfilesPathInData();
		hashCellBackup = new HashMap<>(CellsLoader.hashCell);
		regionNamesBackup = new HashSet<>(CellsLoader.regionsNamesSet);
		regionIdsBackup = new HashMap<>(GisLoader.regionIDs);
		worldNameBackup = ProjectLoader.WorldName;
		regionalisationBackup = CellsLoader.regionalisation;
	}

	@AfterEach
	void restoreStatics() {
		ConfigLoader.config.gis_path = gisPathBackup;
		ConfigLoader.config = configBackup;
		ProjectLoader.setAllfilesPathInData(allFilesBackup);
		CellsLoader.hashCell.clear();
		CellsLoader.hashCell.putAll(hashCellBackup);
		CellsLoader.regionsNamesSet.clear();
		CellsLoader.regionsNamesSet.addAll(regionNamesBackup);
		GisLoader.regionIDs.clear();
		GisLoader.regionIDs.putAll(regionIdsBackup);
		ProjectLoader.WorldName = worldNameBackup;
		CellsLoader.regionalisation = regionalisationBackup;
	}

	// =========================================================
	// Reading the Area column
	// =========================================================

	@Test
	void readsEachCellsAreaFromTheGisFile() throws Exception {
		// As R's write.csv writes it (quoted header, ID first), here also with a byte-order mark, CRLF, a
		// quoted value holding a comma, a lower-case "area" and another column whose name contains "Area".
		Path file = write("﻿\"ID\",\"X\",\"Y\",\"Region_Code\",\"Name\",\"Protected_Area\",\"area\"\r\n"
				+ "a,0,0,R1,\"Korea, Republic of\",1,2500\r\n"
				+ "b,1,0,R1,\"Plain\",0,1234.5\r\n"
				+ "c,0,1,R2,\"Plain\",0,0.25\r\n");
		Map<String, Cell> cells = cells(new int[][] { { 0, 0 }, { 1, 0 }, { 0, 1 } });

		CellAreaLoader.load(file, cells);

		assertEquals(2500.0, cells.get("0,0").getArea(), 0.0);
		assertEquals(1234.5, cells.get("1,0").getArea(), 0.0);
		assertEquals(0.25, cells.get("0,1").getArea(), 0.0);
	}

	@Test
	void aCellWithNoRowGetsAreaOneAndTheRunGoesOn() throws Exception {
		Path file = write("X,Y,Region_Code,Area\n0,0,R1,2500\n");
		Map<String, Cell> cells = cells(new int[][] { { 0, 0 }, { 1, 0 } });
		cells.get("1,0").setArea(9.0); // whatever it had before, a cell with no row gets 1

		CellAreaLoader.load(file, cells);

		assertEquals(2500.0, cells.get("0,0").getArea(), 0.0);
		assertEquals(1.0, cells.get("1,0").getArea(), 0.0);
	}

	@Test
	void rowsForCellsNotInTheBaselineAreIgnored() throws Exception {
		Path file = write("X,Y,Region_Code,Area\n0,0,R1,2500\n50,50,R1,7\n");
		Map<String, Cell> cells = cells(new int[][] { { 0, 0 } });

		CellAreaLoader.load(file, cells);

		assertEquals(2500.0, cells.get("0,0").getArea(), 0.0);
		assertEquals(1, cells.size());
	}

	@Test
	void coordinatesWrittenWithDecimalsStillMatchTheirCells() throws Exception {
		// Areas match by the whole-number grid position, as the baseline loader reads it.
		Path file = write("X,Y,Area\n1.0,2.0,40\n");
		Map<String, Cell> cells = cells(new int[][] { { 1, 2 } });

		CellAreaLoader.load(file, cells);

		assertEquals(40.0, cells.get("1,2").getArea(), 0.0);
	}

	@Test
	void aBrokenFileStopsTheRunNamingTheProblemAndChangesNoCell() throws Exception {
		// { file content, what the message must contain }
		String[][] cases = {
				{ "X,Y,Region_Code\n0,0,R1\n", "has no column(s) [Area]" },
				{ "Y,Area\n0,5\n", "has no column(s) [X]" },
				{ "X,Area\n0,5\n", "has no column(s) [Y]" },
				{ "X,Y,Area\n0,0,\n", "data row 1: Area for cell (0,0) is blank or NA" },
				{ "X,Y,Area\n0,0,NA\n", "data row 1: Area for cell (0,0) is blank or NA" },
				{ "X,Y,Area\n0,0,abc\n", "data row 1: Area for cell (0,0) 'abc' is not a number" },
				{ "X,Y,Area\n0,0,0\n", "data row 1: Area 0.0 for cell (0,0) must be a number above 0" },
				{ "X,Y,Area\n0,0,-3\n", "data row 1: Area -3.0 for cell (0,0)" },
				{ "X,Y,Area\n0,0,Infinity\n", "data row 1: Area Infinity for cell (0,0)" },
				{ "X,Y,Area\n0,0,5\n1,0,-1\n", "data row 2: Area -1.0 for cell (1,0)" },
				{ "X,Y,Area\nleft,0,5\n", "data row 1: X 'left' is not a number" },
				{ "X,Y,Area\n0,,5\n", "data row 1: Y is blank or NA" },
				{ "X,Y,Area\n0,0,5\n1,0,5\n0,0,6\n", "cell (0,0) twice, on data rows 1 and 3" },
				// Every row is checked, including one for a cell outside the baseline.
				{ "X,Y,Area\n0,0,5\n50,50,NA\n", "data row 2: Area for cell (50,50) is blank or NA" }, };

		List<Executable> checks = new ArrayList<>();
		for (int i = 0; i < cases.length; i++) {
			String content = cases[i][0];
			String expected = cases[i][1];
			Path file = tmp.resolve("broken_" + i + ".csv");
			Files.writeString(file, content);
			checks.add(() -> {
				Map<String, Cell> cells = cells(new int[][] { { 0, 0 }, { 1, 0 } });
				cells.values().forEach(c -> c.setArea(9.0));

				IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
						() -> CellAreaLoader.load(file, cells), "No stop for: " + content);

				assertTrue(e.getMessage().contains(expected),
						"For " + content.replace("\n", "\\n") + " expected '" + expected + "' in: " + e.getMessage());
				// The whole file is checked before any cell changes.
				cells.values().forEach(c -> assertEquals(9.0, c.getArea(), 0.0, "A cell changed: " + content));
			});
		}
		assertAll(checks);
	}

	@Test
	void aFileThatCannotBeReadStopsTheRun() throws Exception {
		Path empty = tmp.resolve("empty.csv");
		Files.writeString(empty, "");
		Map<String, Cell> cells = cells(new int[][] { { 0, 0 } });

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> CellAreaLoader.load(empty, cells));

		assertTrue(e.getMessage().contains(empty.toString()), e.getMessage());
	}

	// =========================================================
	// The same GIS file GisLoader reads
	// =========================================================

	@Test
	void areasAndRegionsComeFromTheGisFileInTheGisFolder() throws Exception {
		Path gis = Files.createDirectories(tmp.resolve("worlds").resolve("GIS"));
		Path file = gis.resolve("World_Regions.csv");
		Files.writeString(file, "X,Y,Region_Code,Area\n0,0,R1,2500\n1,0,R2,40\n");
		ConfigLoader.config.gis_path = "";
		ProjectLoader.setAllfilesPathInData(new ArrayList<>(List.of(file)));
		useWorldOf(new int[][] { { 0, 0 }, { 1, 0 } });

		new GisLoader().loadGisData();
		CellAreaLoader.load();

		assertEquals(file, GisLoader.getGisFile());
		assertEquals("R1", CellsLoader.hashCell.get("0,0").getCurrentRegion());
		assertEquals("R2", CellsLoader.hashCell.get("1,0").getCurrentRegion());
		assertEquals(2500.0, CellsLoader.hashCell.get("0,0").getArea(), 0.0);
		assertEquals(40.0, CellsLoader.hashCell.get("1,0").getArea(), 0.0);
	}

	@Test
	void theGisFileNamedByGisPathIsUsed() throws Exception {
		Path file = tmp.resolve("Elsewhere_Regions.csv");
		Files.writeString(file, "X,Y,Region_Code,Area\n0,0,R1,12.5\n");
		ConfigLoader.config.gis_path = file.toString();
		useWorldOf(new int[][] { { 0, 0 } });

		new GisLoader().loadGisData();
		CellAreaLoader.load();

		assertEquals(file, GisLoader.getGisFile());
		assertEquals(12.5, CellsLoader.hashCell.get("0,0").getArea(), 0.0);
	}

	@Test
	void aGisFileWithoutAnAreaColumnStopsTheRunAndLeavesItsRegionsAlone() throws Exception {
		Path file = tmp.resolve("World_Regions.csv");
		Files.writeString(file, "X,Y,Region_Code\n0,0,R1\n");
		ConfigLoader.config.gis_path = file.toString();
		useWorldOf(new int[][] { { 0, 0 } });

		new GisLoader().loadGisData();
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, CellAreaLoader::load);

		assertTrue(e.getMessage().contains("has no column(s) [Area]"), e.getMessage());
		assertEquals("R1", CellsLoader.hashCell.get("0,0").getCurrentRegion());
	}

	@Test
	void withNoGisFileEveryCellHasAreaOne() throws Exception {
		ConfigLoader.config.gis_path = tmp.resolve("not_there.csv").toString();
		ProjectLoader.setAllfilesPathInData(new ArrayList<>()); // and nothing in a GIS folder
		useWorldOf(new int[][] { { 0, 0 }, { 1, 0 } });
		CellsLoader.hashCell.values().forEach(c -> c.setArea(9.0));

		new GisLoader().loadGisData();
		CellAreaLoader.load();

		assertNull(GisLoader.getGisFile());
		CellsLoader.hashCell.values().forEach(c -> assertEquals(1.0, c.getArea(), 0.0));
	}

	// =========================================================
	// Helpers
	// =========================================================

	private Path write(String content) throws Exception {
		Path file = tmp.resolve("World_Regions.csv");
		Files.writeString(file, content);
		return file;
	}

	private static Map<String, Cell> cells(int[][] xy) {
		Map<String, Cell> cells = new HashMap<>();
		for (int[] p : xy) {
			cells.put(p[0] + "," + p[1], new Cell(p[0], p[1]));
		}
		return cells;
	}

	private static void useWorldOf(int[][] xy) {
		CellsLoader.hashCell.clear();
		CellsLoader.hashCell.putAll(cells(xy));
	}
}
