package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvReaderTest {

	@TempDir
	Path tempDir;

	private Path write(String name, String content) throws IOException {
		Path file = tempDir.resolve(name);
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}

	@Test
	void quotedValuesMayHoldCommasAndDoubledQuotes() {
		assertEquals(List.of("a", "b, c", "say \"hi\"", ""), CsvReader.split("a,\"b, c\",\"say \"\"hi\"\"\","));
	}

	@Test
	void headersAreTrimmedAsPlumWritesThem() throws IOException {
		Path file = write("domestic.txt", "Year, Country, Crop, Production\n2030,Argentina,wheat,1.5\n");

		CsvReader.Table table = CsvReader.readAll(file);

		assertEquals(List.of("Year", "Country", "Crop", "Production"), table.header());
		assertEquals("Argentina", table.get(0, "Country"));
		assertEquals(2, table.lineNumber(0));
	}

	@Test
	void aByteOrderMarkWindowsLineEndingsAndBlankLinesAreAccepted() throws IOException {
		Path file = write("bom.csv", "﻿\"X\",\"Y\",\"Region_Code\"\r\n1,2,\"Canada\"\r\n\r\n3,4,\"Chile\"\r\n");

		CsvReader.Table table = CsvReader.readAll(file);

		assertEquals(List.of("X", "Y", "Region_Code"), table.header());
		assertEquals(2, table.size());
		assertEquals("Chile", table.get(1, "Region_Code"));
		assertEquals(4, table.lineNumber(1), "the blank line still counts");
	}

	@Test
	void aRowWithTheWrongNumberOfValuesStopsTheReadNamingTheLine() throws IOException {
		Path file = write("short.csv", "a,b,c\n1,2,3\n4,5\n");

		CouplingInputException e = assertThrows(CouplingInputException.class, () -> CsvReader.readAll(file));

		assertTrue(e.getMessage().contains("line 3") && e.getMessage().contains("2 values"), e.getMessage());
	}

	@Test
	void forEachRowHandsOverOnlyTheRequestedColumnsInTheRequestedOrder() throws IOException {
		Path file = write("gis.csv", "\"X\",\"Y\",\"Region_Code\",\"area\",\"PLUM_country\"\n"
				+ "1,1,\"Italy_and_Malta\",10,\"Italy & Malta\"\n2,1,\"Canada\",11,\"Canada\"\n");
		List<String> seen = new ArrayList<>();

		CsvReader.forEachRow(file, List.of("PLUM_country", "Region_Code"),
				(line, v) -> seen.add(line + ":" + v[0] + "|" + v[1]));

		assertEquals(List.of("2:Italy & Malta|Italy_and_Malta", "3:Canada|Canada"), seen);
	}

	@Test
	void aMissingColumnOrFileStopsTheRead() throws IOException {
		Path file = write("a.csv", "a,b\n1,2\n");

		CouplingInputException missingColumn = assertThrows(CouplingInputException.class,
				() -> CsvReader.forEachRow(file, List.of("a", "c"), (line, v) -> {
				}));
		assertTrue(missingColumn.getMessage().contains("[c]"), missingColumn.getMessage());

		CouplingInputException missingFile = assertThrows(CouplingInputException.class,
				() -> CsvReader.readAll(tempDir.resolve("nope.csv")));
		assertTrue(missingFile.getMessage().contains("not found"), missingFile.getMessage());
	}

	@Test
	void aRepeatedOrUnnamedHeaderColumnStopsTheRead() throws IOException {
		assertThrows(CouplingInputException.class, () -> CsvReader.readAll(write("twice.csv", "a,b,a\n1,2,3\n")));
		assertThrows(CouplingInputException.class, () -> CsvReader.readAll(write("blank.csv", "a,,c\n1,2,3\n")));
	}

	@Test
	void numbersAcceptNaNButNotBlanksOrText() {
		Path file = tempDir.resolve("f.txt");
		assertEquals(0.1011, CsvReader.parseNumber(file, 2, "Export_price", " 0.1011 "));
		assertTrue(Double.isNaN(CsvReader.parseNumber(file, 2, "Area", "NaN")));
		CouplingInputException blank = assertThrows(CouplingInputException.class,
				() -> CsvReader.parseNumber(file, 7, "Production", ""));
		assertTrue(blank.getMessage().contains("line 7") && blank.getMessage().contains("Production"));
		assertThrows(CouplingInputException.class, () -> CsvReader.parseNumber(file, 7, "Production", "1.2.3"));
	}
}
