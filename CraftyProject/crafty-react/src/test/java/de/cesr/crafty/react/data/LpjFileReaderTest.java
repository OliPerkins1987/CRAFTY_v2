package de.cesr.crafty.react.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LpjFileReaderTest {

	@TempDir
	Path dir;

	private LpjGrid grid;
	private int pixelA;
	private int pixelB;

	@BeforeEach
	void setUp() {
		grid = CellKey.load(ReactToyData.cellKey(dir)).grid();
		pixelA = grid.indexOf(-91.25, 17.75);
		pixelB = grid.indexOf(-121.75, 37.25);
	}

	@Test
	void requestedColumnsAreReadIntoPixelOrderAndConverted() {
		Path file = ReactToyData.write(dir, "crops_2020.csv",
				"Lon,Lat,CerealsC30,CerealsC30060,CerealsC31000",
				ReactToyData.PIXEL_A + ",0.182,0.336,0.851",
				ReactToyData.PIXEL_B + ",0.5,0.6,0.7");

		float[][] values = LpjFileReader.read(file, grid, List.of("CerealsC31000", "CerealsC30"), 10);

		assertEquals(2, values.length);
		assertEquals(8.51f, values[0][pixelA], 1e-5, "kg/m2 x 10 = t/ha");
		assertEquals(7.0f, values[0][pixelB], 1e-5);
		assertEquals(1.82f, values[1][pixelA], 1e-5);
		assertEquals(5.0f, values[1][pixelB], 1e-5);
	}

	@Test
	void rowsForPixelsWithoutCellsAreSkippedEvenIfTheyHoldRubbish() {
		Path file = ReactToyData.write(dir, "runoff_2020.csv",
				"\"Lon\",\"Lat\",\"Total\"",
				ReactToyData.PIXEL_NO_CELLS + ",NA",
				ReactToyData.PIXEL_A + ",719.9",
				ReactToyData.PIXEL_B + ",116");

		float[] runoff = LpjFileReader.read(file, grid, List.of("Total"), 1)[0];

		assertEquals(719.9f, runoff[pixelA], 1e-3);
		assertEquals(116f, runoff[pixelB], 1e-3);
	}

	@Test
	void aPixelWithCellsButNoRowIsAnError() {
		Path file = ReactToyData.write(dir, "runoff_2020.csv", "Lon,Lat,Total", ReactToyData.PIXEL_A + ",719.9");

		ReactInputException e = assertThrows(ReactInputException.class,
				() -> LpjFileReader.read(file, grid, List.of("Total"), 1));

		assertTrue(e.getMessage().contains("1 pixel(s)") && e.getMessage().contains("(-121.75, 37.25)"),
				e.getMessage());
	}

	@Test
	void aPixelWithTwoRowsIsAnError() {
		Path file = ReactToyData.write(dir, "runoff_2020.csv", "Lon,Lat,Total",
				ReactToyData.PIXEL_A + ",1", ReactToyData.PIXEL_B + ",2", ReactToyData.PIXEL_A + ",3");

		ReactInputException e = assertThrows(ReactInputException.class,
				() -> LpjFileReader.read(file, grid, List.of("Total"), 1));

		assertTrue(e.getMessage().contains("line 4") && e.getMessage().contains("more than once"), e.getMessage());
	}

	@Test
	void aBadValueForAPixelWithCellsIsReportedWithLineAndColumn() {
		Path file = ReactToyData.write(dir, "runoff_2020.csv", "Lon,Lat,Total",
				ReactToyData.PIXEL_A + ",1", ReactToyData.PIXEL_B + ",NA");

		ReactInputException e = assertThrows(ReactInputException.class,
				() -> LpjFileReader.read(file, grid, List.of("Total"), 1));

		assertTrue(e.getMessage().contains("line 3") && e.getMessage().contains("Total"), e.getMessage());
	}

	@Test
	void coordinatesWrittenSlightlyDifferentlyStillMatch() {
		Path file = ReactToyData.write(dir, "runoff_2020.csv", "Lon,Lat,Total",
				"-91.2500001,17.750,1", "-121.75,37.2499999,2");

		float[] runoff = LpjFileReader.read(file, grid, List.of("Total"), 1)[0];

		assertEquals(1f, runoff[pixelA]);
		assertEquals(2f, runoff[pixelB]);
	}

	@Test
	void columnsAreFoundByNameNotPosition() {
		Path file = ReactToyData.write(dir, "runoff_2020.csv", "Total,Lat,Lon",
				"1,17.75,-91.25", "2,37.25,-121.75");

		float[] runoff = LpjFileReader.read(file, grid, List.of("Total"), 1)[0];

		assertEquals(1f, runoff[pixelA]);
		assertEquals(2f, runoff[pixelB]);
	}

	@Test
	void swappedCoordinateValuesAreReportedWithTheirLine() {
		Path file = ReactToyData.write(dir, "runoff_2020.csv", "Lon,Lat,Total", "17.75,-91.25,1");

		ReactInputException e = assertThrows(ReactInputException.class,
				() -> LpjFileReader.read(file, grid, List.of("Total"), 1));

		assertTrue(e.getMessage().contains("line 2") && e.getMessage().contains("latitude -91.25"), e.getMessage());
	}

	@Test
	void missingColumnsAreNamed() {
		Path file = ReactToyData.write(dir, "crops_2020.csv", "Lon,Lat,CerealsC30", ReactToyData.PIXEL_A + ",1");

		ReactInputException e = assertThrows(ReactInputException.class,
				() -> LpjFileReader.read(file, grid, List.of("CerealsC30", "CerealsC3i0200"), 10));

		assertTrue(e.getMessage().contains("CerealsC3i0200"), e.getMessage());
	}
}
