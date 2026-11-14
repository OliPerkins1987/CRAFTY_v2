package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CouplingOutputsTest {

	@TempDir
	Path tempDir;

	@Test
	void theFolderIsPlumCouplingInsideCraftysOutputFolder() {
		CouplingOutputs outputs = CouplingOutputs.inOutputFolder(tempDir.resolve("output").toString());

		assertEquals(tempDir.resolve("output").resolve("plum_coupling"), outputs.folder());
		assertTrue(Files.isDirectory(outputs.folder()));
	}

	@Test
	void aStartedFileHasOnlyItsHeaderEvenInAReusedFolder() throws Exception {
		CouplingOutputs outputs = new CouplingOutputs(tempDir);
		Files.writeString(outputs.file("x.csv"), "A,B\n1,2\n3,4\n");

		outputs.start("x.csv", List.of("A", "B"));

		assertEquals("A,B\n", Files.readString(outputs.file("x.csv")));
	}

	@Test
	void rowsAreAppendedYearByYear() throws Exception {
		CouplingOutputs outputs = new CouplingOutputs(tempDir);
		outputs.start("x.csv", List.of("Year", "Value"));

		outputs.append("x.csv", List.of(List.of("1990", "1.0")));
		outputs.append("x.csv", List.of(List.of("1991", "2.0"), List.of("1991", "3.0")));

		assertEquals(List.of("Year,Value", "1990,1.0", "1991,2.0", "1991,3.0"), Files.readAllLines(outputs.file("x.csv")));
	}

	@Test
	void valuesWithACommaOrAQuoteAreQuoted() throws Exception {
		CouplingOutputs outputs = new CouplingOutputs(tempDir);
		outputs.start("x.csv", List.of("A", "B", "C"));

		outputs.append("x.csv", List.of(List.of("Korea, Republic of", "a \"b\"", "Italy & Malta")));

		assertEquals("\"Korea, Republic of\",\"a \"\"b\"\"\",Italy & Malta", Files.readAllLines(outputs.file("x.csv")).get(1));
		assertEquals(List.of("Korea, Republic of", "a \"b\"", "Italy & Malta"),
				CsvReader.split(Files.readAllLines(outputs.file("x.csv")).get(1)), "the coupler's reader reads it back");
	}

	@Test
	void appendingToAFileNotStartedOrWithTheWrongCountFails() {
		CouplingOutputs outputs = new CouplingOutputs(tempDir);
		assertThrows(IllegalStateException.class, () -> outputs.append("x.csv", List.of(List.of("1"))));

		outputs.start("x.csv", List.of("A", "B"));
		assertThrows(IllegalStateException.class, () -> outputs.append("x.csv", List.of(List.of("1"))));
	}
}
