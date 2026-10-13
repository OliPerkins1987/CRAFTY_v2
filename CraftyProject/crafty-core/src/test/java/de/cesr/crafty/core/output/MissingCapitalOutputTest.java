package de.cesr.crafty.core.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.core.crafty.Cell;
import de.cesr.crafty.core.updaters.CapitalUpdater;

/**
 * A capital a cell never got is written as "null" in the cell CSV output and as NaN in PNG maps, while a NaN that
 * was put (as CRAFTY-react can hand over) is written as NaN. Production counts the missing one as 0 (see
 * CellTest).
 */
class MissingCapitalOutputTest {

	private List<String> originalCapitals;
	private Cell cell;

	@BeforeEach
	void setUp() {
		originalCapitals = CapitalUpdater.getCapitalsList();
		CapitalUpdater.setCapitalsList(List.of("capA", "capB", "capC"));
		cell = new Cell(0, 0);
		cell.setID("1");
		cell.getCapitals().put("capA", 1.5);
		cell.getCapitals().put("capC", Double.NaN);
		// capB is never put.
	}

	@AfterEach
	void tearDown() {
		CapitalUpdater.setCapitalsList(originalCapitals);
	}

	@Test
	void cellCsvWritesNullForAMissingCapitalAndNaNForANaNOne(@TempDir Path dir) throws IOException {
		ConcurrentHashMap<String, Cell> cells = new ConcurrentHashMap<>();
		cells.put("0,0", cell);
		Path out = dir.resolve("cells.csv");

		CellsToCSV.exportCellsToCSV(out.toString(), cells,
				List.of(CellCsvColumn.ID, CellCsvColumn.X, CellCsvColumn.Y, CellCsvColumn.CAPITALS));

		assertEquals(List.of("ID,X,Y,capA,capB,capC", "1,0,0,1.5,null,NaN"), Files.readAllLines(out));
	}

	@Test
	void pngCapitalValueIsNaNForAMissingCapital() {
		assertTrue(Double.isNaN(capitalValue("capB")), "never put");
		assertTrue(Double.isNaN(capitalValue("capC")), "put as NaN");
		assertEquals(1.5, capitalValue("capA"), 0.0);
	}

	private double capitalValue(String capital) {
		var request = PngGenerator.handler.buildSingleRequests(List.of("capital:" + capital)).get(0);
		return request.value.doubleExtractor.applyAsDouble(cell);
	}
}
