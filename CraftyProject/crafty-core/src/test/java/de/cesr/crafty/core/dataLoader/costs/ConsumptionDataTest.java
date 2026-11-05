package de.cesr.crafty.core.dataLoader.costs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsumptionDataTest {

	@TempDir
	Path dir;

	private static final List<String> SERVICES = List.of("S1", "S2", "S3");
	// S1 and S2 pay transport; S3 does not.
	private static final Predicate<String> PAYERS = s -> !s.equals("S3");
	private static final String PREFIX = "|R1| consumption_R1.csv: ";

	/** Reads the lines as region R1's file, for a run of 2000-2002. */
	private ConsumptionData read(String... lines) throws IOException {
		Path file = dir.resolve("consumption_R1.csv");
		Files.write(file, List.of(lines), StandardCharsets.UTF_8);
		return new ConsumptionData(file, "R1", SERVICES, PAYERS, 2000, 2002);
	}

	@Test
	void readsEachRunYear_byItsYearValue_inAnyOrder() throws IOException {
		ConsumptionData data = read("Year,S2,S1", "2002,6,3", "2000,4,1", "2001,5,2");

		assertEquals(List.of(), data.problems());
		assertArrayEquals(new double[] { 1, 4, 0 }, data.get(2000), 0.0, "indexed like the services, not the file");
		assertArrayEquals(new double[] { 2, 5, 0 }, data.get(2001), 0.0);
		assertArrayEquals(new double[] { 3, 6, 0 }, data.get(2002), 0.0);
		assertEquals(3, data.byYear().size());
	}

	@Test
	void ignoresServicesThatDoNotPayTransport_otherColumns_andYearsOutsideTheRun() throws IOException {
		ConsumptionData data = read("Year,S1,S2,S3,Other",
				"1999,,,,",
				"2000,1,2,,x",
				"2001,1,2,abc,",
				"2002,1,2,-5,",
				"2003,,,,");

		assertEquals(List.of(), data.problems(), "S3 does not pay transport, and 1999 and 2003 are not run years");
		assertNull(data.get(1999));
		assertNull(data.get(2003));
		assertArrayEquals(new double[] { 1, 2, 0 }, data.get(2001), 0.0);
	}

	@Test
	void readsQuotedFields_aByteOrderMark_aYearColumnInAnyCase_andDecimalYears() throws IOException {
		ConsumptionData data = read("﻿\"year\",\"S1\",\"S2\"", "2000.0,1.5,2", "\"2001\",\"3\",4", "2002,5,6", "");

		assertEquals(List.of(), data.problems());
		assertArrayEquals(new double[] { 1.5, 2, 0 }, data.get(2000), 0.0);
		assertArrayEquals(new double[] { 3, 4, 0 }, data.get(2001), 0.0);
	}

	@Test
	void reportsAMissingColumn_forAServiceThatPaysTransport() throws IOException {
		ConsumptionData data = read("Year,S1,S3", "2000,1,0", "2001,1,0", "2002,1,0");

		assertEquals(List.of(PREFIX + "has no column for S2"), data.problems());
	}

	@Test
	void reportsTheRunYearsWithNoRow_forEachServiceThatPaysTransport() throws IOException {
		ConsumptionData data = read("Year,S1,S2", "2001,1,2");

		assertEquals(List.of(PREFIX + "no consumption for S1 in 2000, 2002",
				PREFIX + "no consumption for S2 in 2000, 2002"), data.problems());
	}

	@Test
	void reportsEmptyValues_shortRows_andValuesThatAreNotANumberOf0OrMore() throws IOException {
		ConsumptionData data = read("Year,S1,S2", "2000,,2", "2001,abc,-1", "2002,NaN");

		assertEquals(List.of(PREFIX + "no consumption for S1 in 2000",
				PREFIX + "no consumption for S2 in 2002",
				PREFIX + "consumption for S1 is not a number of 0 or more in 2001-2002",
				PREFIX + "consumption for S2 is not a number of 0 or more in 2001"), data.problems());
	}

	@Test
	void reportsNoYearColumn_anUnreadableYear_aRepeatedYear_andAnEmptyFile() throws IOException {
		assertEquals(List.of(PREFIX + "has no Year column"), read("S1,S2", "1,2").problems());

		assertEquals(List.of(PREFIX + "line 3: '20x1' is not a year", PREFIX + "has 2001 twice"),
				read("Year,S1,S2", "2000,1,2", "20x1,1,2", "2001,1,2", "2001,7,7", "2002,1,2").problems());

		assertEquals(List.of(PREFIX + "is empty"), read().problems());
	}

	@Test
	void reportsAFileThatCannotBeRead() {
		ConsumptionData data = new ConsumptionData(dir.resolve("consumption_R1.csv"), "R1", SERVICES, PAYERS,
				2000, 2002);

		assertEquals(1, data.problems().size());
		assertTrue(data.problems().get(0).startsWith(PREFIX + "could not be read"), data.problems().get(0));
	}

	@Test
	void yearRanges_joinsRunsOfYears() {
		assertEquals("2000-2002, 2005, 2007-2008",
				ConsumptionData.yearRanges(new TreeSet<>(List.of(2008, 2000, 2001, 2002, 2005, 2007))));
		assertEquals("2000", ConsumptionData.yearRanges(new TreeSet<>(List.of(2000))));
		assertEquals("", ConsumptionData.yearRanges(new TreeSet<>()));
	}
}
