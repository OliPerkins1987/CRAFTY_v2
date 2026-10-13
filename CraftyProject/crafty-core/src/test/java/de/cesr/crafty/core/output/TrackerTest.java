package de.cesr.crafty.core.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.crafty.Aft;
import de.cesr.crafty.core.crafty.Cell;
import de.cesr.crafty.core.crafty.Region;
import de.cesr.crafty.core.dataLoader.afts.AFTsLoader;
import de.cesr.crafty.core.dataLoader.land.CellsLoader;
import de.cesr.crafty.core.dataLoader.serivces.ServiceSet;

/**
 * Unit tests for {@link Tracker}.
 *
 * Focus: - writeCSV: header creation, numeric values, and filling missing
 * values with "0" - trackSupply: early exit when track_changes is false (no
 * file created); supply weighted by cell area and the AggregateArea column
 * only when use_cell_area is on
 */
class TrackerTest {

	@TempDir
	Path tempDir;

	/**
	 * Ensure that ConfigLoader.config is non-null and return it.
	 */
	private Object ensureConfigNotNull() {
		try {
			Field configField = ConfigLoader.class.getField("config");
			Object config = configField.get(null);
			if (config == null) {
				Class<?> cfgType = configField.getType();
				Object instance = cfgType.getDeclaredConstructor().newInstance();
				configField.setAccessible(true);
				configField.set(null, instance);
				config = instance;
			}
			return config;
		} catch (Exception e) {
			throw new RuntimeException("Failed to ensure ConfigLoader.config is initialised", e);
		}
	}

	/**
	 * Set a field on ConfigLoader.config via reflection.
	 */
	private void setConfigField(String fieldName, Object value) {
		Object cfg = ensureConfigNotNull();
		try {
			Field f = cfg.getClass().getField(fieldName);
			f.setAccessible(true);
			f.set(cfg, value);
		} catch (NoSuchFieldException e) {
			throw new RuntimeException("Config class does not have public field '" + fieldName + "'", e);
		} catch (Exception e) {
			throw new RuntimeException("Failed to set config field '" + fieldName + "'", e);
		}
	}

	@Test
	void writeCSV_WritesHeaderAndValuesAndFillsMissingWithZero() throws Exception {
		// Arrange: container with two AFTs and non-identical keys
		Map<String, Map<String, Double>> container = new ConcurrentHashMap<>();

		ConcurrentHashMap<String, Double> aft1Map = new ConcurrentHashMap<>();
		aft1Map.put("S1", 1.0);
		aft1Map.put("AggregateAFT", 10.0);

		ConcurrentHashMap<String, Double> aft2Map = new ConcurrentHashMap<>();
		aft2Map.put("S1", 2.0); // no AggregateAFT -> should become "0" in CSV

		container.put("AFT1", aft1Map);
		container.put("AFT2", aft2Map);

		Path csvPath = tempDir.resolve("tracker.csv");

		// Act
		Tracker.writeMatrixToCSV(container, csvPath.toString());

		// Assert: read file & check content
		try (BufferedReader reader = new BufferedReader(new FileReader(csvPath.toFile()))) {
			List<String> lines = reader.lines().collect(Collectors.toList());

			// We expect 3 lines: header + 2 rows
			assertEquals(3, lines.size());

			String header = lines.get(0);
			// Headers are built from TreeSet of keys, so sorted lexicographically:
			// "AggregateAFT", "S1" -> "ID,AggregateAFT,S1"
			assertEquals(",AggregateAFT,S1", header);

			// Remaining lines: we don't know row order (ConcurrentHashMap), so parse them
			List<String> dataLines = lines.subList(1, 3);

			// Convert each row to (ID -> rowValuesMap)
			// e.g. "AFT1,10.0,1.0"
			// "AFT2,0,2.0"
			dataLines.forEach(line -> {
				String[] parts = line.split(",");
				String id = parts[0];
				List<String> vals = Arrays.asList(parts).subList(1, parts.length);
				if ("AFT1".equals(id)) {
					// AFT1: AggregateAFT=10.0, S1=1.0
					assertEquals("10.0", vals.get(0));
					assertEquals("1.0", vals.get(1));
				} else if ("AFT2".equals(id)) {
					// AFT2: missing AggregateAFT -> "0", S1=2.0
					assertEquals("0", vals.get(0));
					assertEquals("2.0", vals.get(1));
				}
			});
		}
	}

	@Test
	void writeCSV_EmptyContainer_WritesOnlyHeader() throws Exception {
		Map<String, Map<String, Double>> container = new ConcurrentHashMap<>();
		Path csvPath = tempDir.resolve("empty.csv");

		Tracker.writeMatrixToCSV(container, csvPath.toString());

		try (BufferedReader reader = new BufferedReader(new FileReader(csvPath.toFile()))) {
			List<String> lines = reader.lines().collect(Collectors.toList());

			// Only header row should be present
			assertEquals(1, lines.size());
			assertEquals("", lines.get(0));
		}
	}

	@Test
	void trackSupply_DoesNothingWhenTrackChangesDisabled() throws Exception {
		// Arrange
		ensureConfigNotNull();
		setConfigField("output_folder_name", tempDir.toString());
		setConfigField("track_changes", false);

		// Act: should return early and not write anything
		Tracker.trackSupply("RegionX");

		// Assert: temp directory should still be empty
		// (no SupplyTracker_*.csv created)
		try (var paths = Files.list(tempDir)) {
			assertFalse(paths.findAny().isPresent(), "No files should be created when track_changes is false");
		}
	}

	@Test
	void trackSupply_weightsSupplyByAreaAndAddsAggregateArea_onlyWhenCellAreaIsOn() throws Exception {
		ensureConfigNotNull();
		List<String> servicesBackup = new ArrayList<>(ServiceSet.getServicesList());
		Map<String, Aft> aftsBackup = new HashMap<>(AFTsLoader.getAftHash());
		Map<String, Region> regionsBackup = new HashMap<>(CellsLoader.regions);
		String folderBackup = ConfigLoader.config.output_folder_name;
		boolean trackBackup = ConfigLoader.config.track_changes;
		boolean areaBackup = ConfigLoader.config.use_cell_area;
		try {
			ServiceSet.getServicesList().clear();
			ServiceSet.getServicesList().addAll(List.of("ser1", "ser2"));
			Aft aft1 = new Aft("AFT1");
			Aft aft2 = new Aft("AFT2");
			AFTsLoader.getAftHash().clear();
			AFTsLoader.getAftHash().put("AFT1", aft1);
			AFTsLoader.getAftHash().put("AFT2", aft2);
			AFTsLoader.getAftHash().put("AFT3", new Aft("AFT3")); // owns no cells
			AFTsLoader.getAftHash().put("Abandoned", new Aft("Abandoned"));

			// Two AFT1 cells (2 + 3 ha), one AFT2 cell (0.5 ha) and one cell with no owner (7 ha).
			Cell c1 = cell(0, aft1, 2.0, 1, 0);
			Cell c2 = cell(1, aft1, 3.0, 2, 0);
			Cell c3 = cell(2, aft2, 0.5, 0, 4);
			Cell c4 = cell(3, null, 7.0, 9, 9);
			Region region = new Region("R1");
			ConcurrentHashMap<String, Cell> cells = new ConcurrentHashMap<>();
			for (Cell c : List.of(c1, c2, c3, c4)) {
				cells.put(c.getX() + "," + c.getY(), c);
			}
			region.setCells(cells);
			CellsLoader.regions.put("R1", region);
			AFTsLoader.hashAgentNbr("R1");
			setConfigField("track_changes", true);

			// Off: production summed per cell, no area column (as before cell areas existed).
			setConfigField("use_cell_area", false);
			Map<String, Map<String, String>> off = trackSupplyInto(tempDir.resolve("off"));
			assertFalse(off.get("AFT1").containsKey(Tracker.AGGREGATE_AREA));
			assertEquals(3.0, Double.parseDouble(off.get("AFT1").get("ser1")), 1e-12);
			assertEquals(4.0, Double.parseDouble(off.get("AFT2").get("ser2")), 1e-12);
			assertEquals(2.0, Double.parseDouble(off.get("AFT1").get("AggregateAFT")), 0.0);

			// On: production x area, and the land area of each AFT's cells next to the cell count.
			setConfigField("use_cell_area", true);
			Map<String, Map<String, String>> on = trackSupplyInto(tempDir.resolve("on"));
			assertEquals(1 * 2.0 + 2 * 3.0, Double.parseDouble(on.get("AFT1").get("ser1")), 1e-12);
			assertEquals(4 * 0.5, Double.parseDouble(on.get("AFT2").get("ser2")), 1e-12);
			assertEquals(2.0, Double.parseDouble(on.get("AFT1").get("AggregateAFT")), 0.0);
			assertEquals(5.0, Double.parseDouble(on.get("AFT1").get(Tracker.AGGREGATE_AREA)), 1e-12);
			assertEquals(0.5, Double.parseDouble(on.get("AFT2").get(Tracker.AGGREGATE_AREA)), 1e-12);
			assertEquals(0.0, Double.parseDouble(on.get("AFT3").get(Tracker.AGGREGATE_AREA)), 0.0);
			assertEquals(1.0, Double.parseDouble(on.get("Abandoned").get("AggregateAFT")), 0.0);
			assertEquals(7.0, Double.parseDouble(on.get("Abandoned").get(Tracker.AGGREGATE_AREA)), 1e-12);
		} finally {
			ServiceSet.getServicesList().clear();
			ServiceSet.getServicesList().addAll(servicesBackup);
			AFTsLoader.getAftHash().clear();
			AFTsLoader.getAftHash().putAll(aftsBackup);
			CellsLoader.regions.clear();
			CellsLoader.regions.putAll(regionsBackup);
			AFTsLoader.hashAgentNbrRegions.remove("R1");
			setConfigField("output_folder_name", folderBackup);
			setConfigField("track_changes", trackBackup);
			setConfigField("use_cell_area", areaBackup);
		}
	}

	private static Cell cell(int x, Aft owner, double area, double ser1, double ser2) {
		Cell c = new Cell(x, 0);
		c.setOwner(owner);
		c.setArea(area);
		c.getCurrentProd()[0] = ser1;
		c.getCurrentProd()[1] = ser2;
		return c;
	}

	/** Runs trackSupply("R1") into a fresh output folder and reads the file back as row -> column -> value. */
	private Map<String, Map<String, String>> trackSupplyInto(Path outputFolder) throws Exception {
		Path regionFolder = Files.createDirectories(outputFolder.resolve("region_R1"));
		setConfigField("output_folder_name", outputFolder.toString());

		Tracker.trackSupply("R1");

		List<Path> written;
		try (var paths = Files.list(regionFolder)) {
			written = paths.toList();
		}
		assertEquals(1, written.size(), "One SupplyTracker file: " + written);
		assertTrue(written.get(0).getFileName().toString().startsWith("SupplyTracker_"));
		List<String> lines = Files.readAllLines(written.get(0));
		String[] header = lines.get(0).split(",", -1);
		Map<String, Map<String, String>> table = new LinkedHashMap<>();
		for (String line : lines.subList(1, lines.size())) {
			String[] values = line.split(",", -1);
			Map<String, String> row = new LinkedHashMap<>();
			for (int i = 1; i < header.length; i++) {
				row.put(header[i], values[i]);
			}
			table.put(values[0], row);
		}
		return table;
	}
}
