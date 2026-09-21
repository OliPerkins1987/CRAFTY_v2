package de.cesr.crafty.react.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReactCsvTest {

	@TempDir
	Path dir;

	@Test
	void quotedHeadersAreReadWithoutTheirQuotes() {
		Path file = ReactToyData.write(dir, "runoff.csv", "\"Lon\",\"Lat\",\"Total\"", "-91.25,17.75,719.9");
		assertEquals(List.of("Lon", "Lat", "Total"), ReactCsv.readHeader(file));
	}

	@Test
	void aByteOrderMarkAndWindowsLineEndingsAreAccepted() throws IOException {
		Path file = dir.resolve("excel.csv");
		Files.write(file, "﻿Item,Cost\r\nNfert,1.08\r\n".getBytes(StandardCharsets.UTF_8));

		ReactCsv.Table table = ReactCsv.readAll(file);

		assertEquals(List.of("Item", "Cost"), table.header());
		assertEquals("Nfert", table.get(0, "Item"));
		assertEquals(1.08, table.number(0, "Cost"));
	}

	@Test
	void valuesAreKeptExactlyAsWrittenAndBlanksStayBlank() {
		// Core's reader would turn 200 into "200.0" and a blank into "NaN"; react needs the text as written.
		Path file = ReactToyData.write(dir, "p.csv", "Label,react_N_anchor,react_I_eff", "IntC3C,200,");

		ReactCsv.Table table = ReactCsv.readAll(file);

		assertEquals("200", table.get(0, "react_N_anchor"));
		assertEquals("", table.get(0, "react_I_eff"));
	}

	@Test
	void aQuotedFieldMayHoldCommasAndQuotes() {
		assertEquals(List.of("a", "b, c", "say \"hi\"", ""), ReactCsv.split("a,\"b, c\",\"say \"\"hi\"\"\","));
	}

	@Test
	void blankLinesAreSkippedAndLineNumbersStillMatchTheFile() {
		Path file = ReactToyData.write(dir, "t.csv", "A,B", "1,2", "", "3,4");

		ReactCsv.Table table = ReactCsv.readAll(file);

		assertEquals(2, table.rowCount());
		assertEquals(4, table.lineNumber(1));
	}

	@Test
	void forEachRowHandsOverOnlyTheRequestedColumnsInTheRequestedOrder() {
		Path file = ReactToyData.write(dir, "t.csv", "A,B,C", "1,2,3", "4,5,6");
		List<String> seen = new ArrayList<>();

		ReactCsv.forEachRow(file, List.of("C", "A"), (line, values) -> seen.add(line + ":" + String.join("|", values)));

		assertEquals(List.of("2:3|1", "3:6|4"), seen);
	}

	@Test
	void everyMissingColumnIsNamedAtOnce() {
		Path file = ReactToyData.write(dir, "t.csv", "A,B", "1,2");

		ReactInputException e = assertThrows(ReactInputException.class,
				() -> ReactCsv.forEachRow(file, List.of("A", "X", "Y"), (line, values) -> {
				}));

		assertTrue(e.getMessage().contains("[X, Y]"), e.getMessage());
	}

	@Test
	void aRowWithTheWrongNumberOfValuesIsReportedWithItsLine() {
		Path file = ReactToyData.write(dir, "t.csv", "A,B", "1,2", "3");

		ReactInputException e = assertThrows(ReactInputException.class, () -> ReactCsv.readAll(file));

		assertTrue(e.getMessage().contains("line 3"), e.getMessage());
	}

	@Test
	void aRepeatedOrUnnamedHeaderIsRefused() {
		Path repeated = ReactToyData.write(dir, "r.csv", "A,A", "1,2");
		Path unnamed = ReactToyData.write(dir, "u.csv", "A,,C", "1,2,3");

		assertThrows(ReactInputException.class, () -> ReactCsv.readAll(repeated));
		assertThrows(ReactInputException.class, () -> ReactCsv.readAll(unnamed));
	}

	@Test
	void missingAndEmptyFilesAreReportedByName() {
		Path missing = dir.resolve("nope.csv");
		Path empty = ReactToyData.write(dir, "empty.csv", "");

		assertTrue(assertThrows(ReactInputException.class, () -> ReactCsv.readAll(missing)).getMessage()
				.contains("nope.csv"));
		assertTrue(assertThrows(ReactInputException.class, () -> ReactCsv.readAll(empty)).getMessage()
				.contains("empty"));
	}

	@Test
	void numbersInAnyUsualFormatAreRead() {
		Path file = dir.resolve("f.csv");
		assertEquals(1.74799E-05, ReactCsv.parseNumber(file, 2, "c", "1.74799E-05"));
		assertEquals(-91.25, ReactCsv.parseNumber(file, 2, "c", " -91.25 "));
		assertEquals(200, ReactCsv.parseNumber(file, 2, "c", "200"));
	}

	@Test
	void blankAndMissingValueMarkersAreNotNumbers() {
		Path file = dir.resolve("f.csv");
		for (String text : new String[] { "", " ", "NA", "NaN", "Infinity", "abc" }) {
			ReactInputException e = assertThrows(ReactInputException.class,
					() -> ReactCsv.parseNumber(file, 7, "Total", text), text);
			assertTrue(e.getMessage().contains("line 7") && e.getMessage().contains("Total"), e.getMessage());
		}
	}

	@Test
	void requireColumnsNamesEveryMissingColumn() {
		ReactCsv.Table table = ReactCsv.readAll(ReactToyData.write(dir, "t.csv", "A", "1"));

		assertTrue(table.hasColumn("A"));
		assertFalse(table.hasColumn("B"));
		ReactInputException e = assertThrows(ReactInputException.class, () -> table.requireColumns("A", "B", "C"));
		assertTrue(e.getMessage().contains("[B, C]"), e.getMessage());
	}

	@Test
	void theHandlersArrayIsReusedSoValuesToKeepMustBeCopied() {
		Path file = ReactToyData.write(dir, "t.csv", "A", "1", "2");
		List<String[]> arrays = new ArrayList<>();
		List<String[]> copies = new ArrayList<>();

		ReactCsv.forEachRow(file, List.of("A"), (line, values) -> {
			arrays.add(values);
			copies.add(values.clone());
		});

		assertSame(arrays.get(0), arrays.get(1));
		assertArrayEquals(new String[] { "1" }, copies.get(0));
		assertArrayEquals(new String[] { "2" }, copies.get(1));
	}
}
