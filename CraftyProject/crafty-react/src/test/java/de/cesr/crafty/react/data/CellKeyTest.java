package de.cesr.crafty.react.data;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CellKeyTest {

	@TempDir
	Path dir;

	@Test
	void cellsAreLinkedToTheirPixels() {
		CellKey key = CellKey.load(ReactToyData.cellKey(dir));

		assertEquals(3, key.cellCount());
		assertEquals(2, key.grid().size(), "The grid holds only pixels that contain cells");
		int pixelA = key.grid().indexOf(-91.25, 17.75);
		int pixelB = key.grid().indexOf(-121.75, 37.25);
		assertEquals(pixelA, key.pixelOf("1,1"));
		assertEquals(pixelA, key.pixelOf("1,2"));
		assertEquals(pixelB, key.pixelOf("2,1"));
		assertEquals(-1, key.pixelOf("9,9"));
	}

	@Test
	void aPixelListsAllItsCellsInFileOrder() {
		CellKey key = CellKey.load(ReactToyData.cellKey(dir));

		assertEquals(List.of("1,1", "1,2"), key.cellsIn(key.grid().indexOf(-91.25, 17.75)));
		assertEquals(List.of("2,1"), key.cellsIn(key.grid().indexOf(-121.75, 37.25)));
	}

	@Test
	void eachCellCarriesItsRegionAndAPixelMayStraddleABorder() {
		CellKey key = CellKey.load(ReactToyData.cellKey(dir));
		int pixelA = key.grid().indexOf(-91.25, 17.75);
		int pixelB = key.grid().indexOf(-121.75, 37.25);

		assertEquals("North", key.regionOfCell("1,1"));
		assertEquals("South", key.regionOfCell("1,2"));
		assertEquals(Set.of("North", "South"), key.regions());
		assertEquals(List.of("North", "South"), key.regionsIn(pixelA), "Pixel A straddles a border");
		assertEquals(List.of("North"), key.regionsIn(pixelB));
		assertEquals(1, key.pixelsSpanningRegions());
	}

	@Test
	void theDominantRegionIsTheOneWithMostCellsAndTiesGoToTheFirstName() {
		Path file = ReactToyData.write(dir, "key.csv", "X,Y,LPJ_cell_x,LPJ_cell_y,region",
				"1,1," + ReactToyData.PIXEL_A + ",South",
				"1,2," + ReactToyData.PIXEL_A + ",North",
				"1,3," + ReactToyData.PIXEL_A + ",North",
				"2,1," + ReactToyData.PIXEL_B + ",South",
				"2,2," + ReactToyData.PIXEL_B + ",North");

		CellKey key = CellKey.load(file);

		assertEquals("North", key.dominantRegion(key.grid().indexOf(-91.25, 17.75)), "Two cells against one");
		assertEquals("North", key.dominantRegion(key.grid().indexOf(-121.75, 37.25)), "A tie goes to the first name");
	}

	@Test
	void aBlankOrMissingRegionIsAnError() {
		Path blank = ReactToyData.write(dir, "blank.csv", "X,Y,LPJ_cell_x,LPJ_cell_y,region",
				"1,1," + ReactToyData.PIXEL_A + ",");
		Path missing = ReactToyData.write(dir, "missing.csv", "X,Y,LPJ_cell_x,LPJ_cell_y",
				"1,1," + ReactToyData.PIXEL_A);

		assertTrue(assertThrows(ReactInputException.class, () -> CellKey.load(blank)).getMessage()
				.contains("region is blank"));
		assertTrue(assertThrows(ReactInputException.class, () -> CellKey.load(missing)).getMessage()
				.contains("[region]"));
	}

	@Test
	void cellIdsMatchCoresFormat() {
		// Core stores cells under x + "," + y (CsvProcessors.createCells).
		assertEquals("386,368", CellKey.cellId(386, 368));
	}

	@Test
	void everyModelCellMustHaveAPixel() {
		CellKey key = CellKey.load(ReactToyData.cellKey(dir));

		assertDoesNotThrow(() -> key.requireEveryCell(List.of("1,1", "2,1")),
				"Cells in the key but not in the model are fine");
		ReactInputException e = assertThrows(ReactInputException.class,
				() -> key.requireEveryCell(List.of("1,1", "5,5", "3,3")));
		assertTrue(e.getMessage().contains("2 CRAFTY cell(s)") && e.getMessage().contains("[3,3, 5,5]"),
				e.getMessage());
	}

	@Test
	void aCellListedTwiceIsAnError() {
		Path file = ReactToyData.write(dir, "key.csv", "X,Y,LPJ_cell_x,LPJ_cell_y,region",
				"1,1," + ReactToyData.PIXEL_A + ",North", "1,1," + ReactToyData.PIXEL_B + ",North");

		ReactInputException e = assertThrows(ReactInputException.class, () -> CellKey.load(file));

		assertTrue(e.getMessage().contains("line 3") && e.getMessage().contains("1,1"), e.getMessage());
	}

	@Test
	void cellCoordinatesMustBeWholeNumbers() {
		Path file = ReactToyData.write(dir, "key.csv", "X,Y,LPJ_cell_x,LPJ_cell_y,region", "1.5,1," + ReactToyData.PIXEL_A + ",North");

		ReactInputException e = assertThrows(ReactInputException.class, () -> CellKey.load(file));

		assertTrue(e.getMessage().contains("whole number"), e.getMessage());
	}

	@Test
	void wholeNumbersWrittenWithADecimalPointAreAccepted() {
		Path file = ReactToyData.write(dir, "key.csv", "X,Y,LPJ_cell_x,LPJ_cell_y,region", "386.0,368," + ReactToyData.PIXEL_A + ",North");

		assertEquals(0, CellKey.load(file).pixelOf("386,368"));
	}

	@Test
	void pixelsAreNumberedInFileOrder() {
		Path file = ReactToyData.write(dir, "key.csv", "X,Y,LPJ_cell_x,LPJ_cell_y,region",
				"1,1," + ReactToyData.PIXEL_B + ",North", "2,1," + ReactToyData.PIXEL_A + ",South", "3,1," + ReactToyData.PIXEL_B + ",North");

		CellKey key = CellKey.load(file);

		assertEquals(0, key.pixelOf("1,1"));
		assertEquals(1, key.pixelOf("2,1"));
		assertEquals(0, key.pixelOf("3,1"));
		assertEquals(List.of("1,1", "3,1"), key.cellsIn(0));
	}

	@Test
	void aPixelOffTheGlobeIsReportedWithItsLine() {
		Path file = ReactToyData.write(dir, "key.csv", "X,Y,LPJ_cell_x,LPJ_cell_y,region", "1,1,17.75,-91.25,North");

		ReactInputException e = assertThrows(ReactInputException.class, () -> CellKey.load(file));

		assertTrue(e.getMessage().contains("line 2") && e.getMessage().contains("latitude"), e.getMessage());
	}

	@Test
	void missingColumnsAndEmptyKeysAreErrors() {
		Path noColumns = ReactToyData.write(dir, "a.csv", "ID,X,Y", "1,1,1");
		Path noRows = ReactToyData.write(dir, "b.csv", "X,Y,LPJ_cell_x,LPJ_cell_y,region");

		assertTrue(assertThrows(ReactInputException.class, () -> CellKey.load(noColumns)).getMessage()
				.contains("[LPJ_cell_x, LPJ_cell_y, region]"));
		assertTrue(assertThrows(ReactInputException.class, () -> CellKey.load(noRows)).getMessage()
				.contains("no rows"));
	}
}
