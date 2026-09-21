package de.cesr.crafty.react.data;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

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
		Path file = ReactToyData.write(dir, "key.csv", "X,Y,LPJ_cell_x,LPJ_cell_y",
				"1,1," + ReactToyData.PIXEL_A, "1,1," + ReactToyData.PIXEL_B);

		ReactInputException e = assertThrows(ReactInputException.class, () -> CellKey.load(file));

		assertTrue(e.getMessage().contains("line 3") && e.getMessage().contains("1,1"), e.getMessage());
	}

	@Test
	void cellCoordinatesMustBeWholeNumbers() {
		Path file = ReactToyData.write(dir, "key.csv", "X,Y,LPJ_cell_x,LPJ_cell_y", "1.5,1," + ReactToyData.PIXEL_A);

		ReactInputException e = assertThrows(ReactInputException.class, () -> CellKey.load(file));

		assertTrue(e.getMessage().contains("whole number"), e.getMessage());
	}

	@Test
	void wholeNumbersWrittenWithADecimalPointAreAccepted() {
		Path file = ReactToyData.write(dir, "key.csv", "X,Y,LPJ_cell_x,LPJ_cell_y", "386.0,368," + ReactToyData.PIXEL_A);

		assertEquals(0, CellKey.load(file).pixelOf("386,368"));
	}

	@Test
	void pixelsAreNumberedInFileOrder() {
		Path file = ReactToyData.write(dir, "key.csv", "X,Y,LPJ_cell_x,LPJ_cell_y",
				"1,1," + ReactToyData.PIXEL_B, "2,1," + ReactToyData.PIXEL_A, "3,1," + ReactToyData.PIXEL_B);

		CellKey key = CellKey.load(file);

		assertEquals(0, key.pixelOf("1,1"));
		assertEquals(1, key.pixelOf("2,1"));
		assertEquals(0, key.pixelOf("3,1"));
		assertEquals(List.of("1,1", "3,1"), key.cellsIn(0));
	}

	@Test
	void aPixelOffTheGlobeIsReportedWithItsLine() {
		Path file = ReactToyData.write(dir, "key.csv", "X,Y,LPJ_cell_x,LPJ_cell_y", "1,1,17.75,-91.25");

		ReactInputException e = assertThrows(ReactInputException.class, () -> CellKey.load(file));

		assertTrue(e.getMessage().contains("line 2") && e.getMessage().contains("latitude"), e.getMessage());
	}

	@Test
	void missingColumnsAndEmptyKeysAreErrors() {
		Path noColumns = ReactToyData.write(dir, "a.csv", "ID,X,Y", "1,1,1");
		Path noRows = ReactToyData.write(dir, "b.csv", "X,Y,LPJ_cell_x,LPJ_cell_y");

		assertTrue(assertThrows(ReactInputException.class, () -> CellKey.load(noColumns)).getMessage()
				.contains("[LPJ_cell_x, LPJ_cell_y]"));
		assertTrue(assertThrows(ReactInputException.class, () -> CellKey.load(noRows)).getMessage()
				.contains("no rows"));
	}
}
