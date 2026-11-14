package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductionFileTest {

	@TempDir
	Path tempDir;

	private static final String SENEGAL = "Senegal Gambia & Cabo Verde";

	/** data1990/crafty/1990/production.csv's rows for the three countries of the PLUM extract, with its header. */
	static Path production1990Extract() throws URISyntaxException {
		return Paths.get(ProductionFileTest.class.getResource("/production_1990_extract/production.csv").toURI());
	}

	/** A key for the PLUM extract's three countries. */
	static RegionCountryKey threeCountryKey(Path dir) throws IOException {
		Path gis = dir.resolve("global_Regions.csv");
		Files.writeString(gis, "\"X\",\"Y\",\"Region_Code\",\"PLUM_country\"\n1,1,\"Senegal_and_co\",\"" + SENEGAL
				+ "\"\n2,1,\"Tanzania\",\"Tanzania\"\n3,1,\"Argentina\",\"Argentina\"\n");
		return RegionCountryKey.read(gis);
	}

	private static ServiceCommodityMap services() throws URISyntaxException {
		return ServiceCommodityMap.read(ServiceCommodityMapTest.globalServices());
	}

	/** Every market service's supply in every region of the key. */
	private static Map<String, Map<String, Double>> supply(ServiceCommodityMap services, RegionCountryKey key,
			double tonnes) {
		Map<String, Map<String, Double>> supply = new TreeMap<>();
		for (String region : key.regions()) {
			Map<String, Double> byService = new LinkedHashMap<>();
			services.links().forEach(l -> byService.put(l.service(), tonnes));
			supply.put(region, byService);
		}
		return supply;
	}

	// ---- From supply to PLUM's commodities ----

	@Test
	void sharedCommoditiesAreSummedAndUnitsConverted() throws Exception {
		RegionCountryKey key = threeCountryKey(tempDir);
		Map<String, Map<String, Double>> supply = supply(services(), key, 1e6);
		Map<String, Double> tanzania = supply.get("Tanzania");
		tanzania.put("C3cereals", 7.91e6);
		tanzania.put("C3sugarcane", 3e6);
		tanzania.put("C3sugarbeet", 1.5e6);
		tanzania.put("Timber", 2e6);
		tanzania.put("Fuelwood", 5e5);

		Map<String, Map<String, Double>> p = ProductionFile.aggregate(supply, services(), key);

		assertEquals(List.of("Argentina", SENEGAL, "Tanzania"), new ArrayList<>(p.keySet()));
		for (Map<String, Double> byCommodity : p.values()) {
			assertEquals(PlumCommodities.PRODUCTION, new ArrayList<>(byCommodity.keySet()));
			assertEquals(0.0, byCommodity.get("energycrops"), 0.0, "no service supplies it");
		}
		assertEquals(7.91, p.get("Tanzania").get("wheat"), 1e-12);
		assertEquals(4.5, p.get("Tanzania").get("sugar"), 1e-12, "cane + beet");
		assertEquals(2.5, p.get("Tanzania").get("wood"), 1e-12, "timber + fuelwood");
		assertEquals(2.0, p.get(SENEGAL).get("sugar"), 1e-12);
		assertEquals(1.0, p.get("Argentina").get("pasture"), 1e-12);
	}

	@Test
	void badSupplyStopsWithEveryCaseListed() throws Exception {
		RegionCountryKey key = threeCountryKey(tempDir);
		Map<String, Map<String, Double>> supply = supply(services(), key, 1e6);
		supply.get("Tanzania").put("C3rice", Double.NaN);
		supply.get("Argentina").put("Pasture", -1.0);
		supply.get("Senegal_and_co").remove("Timber");

		CouplingInputException e = assertThrows(CouplingInputException.class,
				() -> ProductionFile.aggregate(supply, services(), key));

		assertEquals(4, e.getProblems().size(), e.getMessage());
		assertTrue(e.getMessage().contains("Tanzania, C3rice: supply is NaN"), e.getMessage());
		assertTrue(e.getMessage().contains("Argentina, Pasture: supply is -1.0"), e.getMessage());
		assertTrue(e.getMessage().contains("Senegal_and_co: no supply of Timber"), e.getMessage());
	}

	@Test
	void aKeyRegionWithNoSupplyAndASupplyRegionOutsideTheKeyStop() throws Exception {
		RegionCountryKey key = threeCountryKey(tempDir);
		Map<String, Map<String, Double>> supply = supply(services(), key, 1e6);
		supply.put("Atlantis", supply.remove("Argentina"));

		CouplingInputException e = assertThrows(CouplingInputException.class,
				() -> ProductionFile.aggregate(supply, services(), key));

		assertEquals(3, e.getProblems().size(), e.getMessage());
		assertTrue(e.getMessage().contains("Region Atlantis has supply but no PLUM country"), e.getMessage());
		assertTrue(e.getMessage().contains("Region Argentina (Argentina) has no supply"), e.getMessage());
	}

	// ---- The file ----

	@Test
	void theFileIsWrittenInPlumsFormatAndOrderThenDone() throws Exception {
		RegionCountryKey key = threeCountryKey(tempDir);
		Path dir = tempDir.resolve("production");

		Path file = ProductionFile.write(dir, 1990, ProductionFile.aggregate(supply(services(), key, 1e6), services(), key));

		assertEquals(dir.resolve("1990").resolve("production.csv"), file);
		String text = Files.readString(file, StandardCharsets.UTF_8);
		assertFalse(text.contains("\r"), "LF line ends");
		assertFalse(text.startsWith("﻿"), "no byte-order mark");
		assertFalse(text.contains("\""), "nothing quoted");
		String[] lines = text.split("\n");
		assertEquals(1 + 3 * 12, lines.length);
		assertEquals("Country,Commodity,Production", lines[0]);
		assertEquals("Argentina,wheat,1.0", lines[1]);
		assertEquals("Argentina,energycrops,0.0", lines[10]);
		assertEquals(SENEGAL + ",wheat,1.0", lines[13]);
		assertTrue(Files.exists(dir.resolve("1990").resolve("done")));
		assertFalse(Files.exists(dir.resolve("1990").resolve("production.csv.tmp")));
	}

	@Test
	void valuesReadBackExactlyAsPlumParsesThem() throws Exception {
		double[] values = { 1.2345678901234567, 1.2e-5, 8.399999999999999e-4, 0.0, -0.0, 123456789.123, 0.1 + 0.2,
				Double.MIN_VALUE, 7.910000000000001, 1e300, 42 };
		Map<String, Double> byCommodity = new LinkedHashMap<>();
		for (int i = 0; i < values.length; i++) {
			byCommodity.put(PlumCommodities.PRODUCTION.get(i), values[i]);
		}

		Path file = ProductionFile.write(tempDir, 2000, Map.of("Tanzania", byCommodity));

		List<String> lines = Files.readAllLines(file);
		for (int i = 0; i < values.length; i++) {
			// As PLUM's CraftyProductionReader: split on commas, trim, Double.parseDouble
			String[] tokens = lines.get(i + 1).split(",", -1);
			assertEquals(values[i], Double.parseDouble(tokens[2].trim()), 0.0, lines.get(i + 1));
		}
		assertEquals("Tanzania,oilcropsOther,0.0", lines.get(5), "-0.0 is written as 0.0");
	}

	@Test
	void anExistingDoneStopsTheWrite() throws Exception {
		Path dir = tempDir.resolve("production");
		Files.createDirectories(dir.resolve("1990"));
		Files.createFile(dir.resolve("1990").resolve("done"));

		CouplingInputException e = assertThrows(CouplingInputException.class,
				() -> ProductionFile.write(dir, 1990, Map.of("Tanzania", Map.of("wheat", 1.0))));

		assertTrue(e.getMessage().contains("already exists"), e.getMessage());
		assertFalse(Files.exists(dir.resolve("1990").resolve("production.csv")));
	}

	@Test
	void aFailedWriteLeavesNoDone() throws Exception {
		Path dir = tempDir.resolve("production");
		// production.csv can't be put in place: a folder with a file in it is there instead
		Path blocked = dir.resolve("1990").resolve("production.csv");
		Files.createDirectories(blocked);
		Files.writeString(blocked.resolve("x"), "x");

		assertThrows(CouplingInputException.class,
				() -> ProductionFile.write(dir, 1990, Map.of("Tanzania", Map.of("wheat", 1.0))));

		assertFalse(Files.exists(dir.resolve("1990").resolve("done")), "PLUM must never see done without the file");
	}

	@Test
	void aPlumFormatFileWrittenElsewhereIsReadAndRoundTrips() throws Exception {
		Map<String, Map<String, Double>> p = ProductionFile.read(production1990Extract());

		assertEquals(List.of("Argentina", SENEGAL, "Tanzania"), new ArrayList<>(p.keySet()));
		assertEquals(PlumCommodities.PRODUCTION, new ArrayList<>(p.get("Tanzania").keySet()));
		assertEquals(11.1993, p.get("Argentina").get("wheat"));
		assertEquals(8.399999999999999e-4, p.get(SENEGAL).get("wheat"));

		Path written = ProductionFile.write(tempDir, 1990, p);
		assertEquals(p, ProductionFile.read(written));
	}

	@Test
	void badRowsInAProductionFileAreListedTogether() throws Exception {
		Path file = tempDir.resolve("production.csv");
		Files.writeString(file, "Country,Commodity,Production\nChile,wheat,-1\nChile,maize,lots\nChile,rice,1\n"
				+ "Chile,rice,2\nChile,pulses,NaN\n");

		CouplingInputException e = assertThrows(CouplingInputException.class, () -> ProductionFile.read(file));

		assertEquals(5, e.getProblems().size(), e.getMessage());
		assertTrue(e.getMessage().contains("Chile, rice appears more than once"), e.getMessage());

		Path noColumn = tempDir.resolve("old.csv");
		Files.writeString(noColumn, "Country,Crop,Production\nChile,wheat,1\n");
		assertTrue(assertThrows(CouplingInputException.class, () -> ProductionFile.read(noColumn)).getMessage()
				.contains("Commodity"));
	}

	// ---- The 1990 check ----

	private static Map<String, Map<String, Double>> copy(Map<String, Map<String, Double>> p) {
		Map<String, Map<String, Double>> copy = new TreeMap<>();
		p.forEach((country, byCommodity) -> copy.put(country, new LinkedHashMap<>(byCommodity)));
		return copy;
	}

	@Test
	void theSameProductionIsIdentical() throws Exception {
		Map<String, Map<String, Double>> p = ProductionFile.read(production1990Extract());

		ProductionFile.Comparison c = ProductionFile.compare(p, copy(p));

		assertTrue(c.identical());
		assertEquals(0, c.differing());
		assertEquals(36, c.pairs().size());
	}

	@Test
	void onlyDifferencesOfMoreThanATenthOfAPercentCount() throws Exception {
		Map<String, Map<String, Double>> p = ProductionFile.read(production1990Extract());
		Map<String, Map<String, Double>> run = copy(p);

		run.get("Tanzania").put("wheat", 0.1113 * 1.0009);
		ProductionFile.Comparison within = ProductionFile.compare(p, run);
		assertFalse(within.identical());
		assertEquals(0, within.differing());

		run.get("Tanzania").put("wheat", 0.1113 * 1.0011);
		assertEquals(1, ProductionFile.compare(p, run).differing());
	}

	@Test
	void zerosAreEqualAndRowsInOneFileDiffer() throws Exception {
		Map<String, Map<String, Double>> p = ProductionFile.read(production1990Extract());
		Map<String, Map<String, Double>> run = copy(p);
		run.get("Tanzania").put("energycrops", 0.5);
		run.remove("Argentina");
		run.put("Atlantis", new LinkedHashMap<>(Map.of("wheat", 0.0)));

		ProductionFile.Comparison c = ProductionFile.compare(p, run);

		assertEquals(37, c.pairs().size());
		assertEquals(1 + 12 + 1, c.differing(), "Tanzania's energy crops, Argentina's 12 rows, Atlantis");
		assertFalse(c.pairs().stream().filter(x -> x.country().equals(SENEGAL) && x.commodity().equals("energycrops"))
				.findFirst().orElseThrow().differs(), "0 against 0");
	}

	@Test
	void theWarningGivesTheCountTheLargestDifferencesAndWorldTotals() throws Exception {
		Map<String, Map<String, Double>> p = ProductionFile.read(production1990Extract());
		Map<String, Map<String, Double>> run = copy(p);
		run.get("Tanzania").put("pasture", 2 * 5.894718965872674);
		run.get("Argentina").put("wheat", 12.1993);

		ProductionFile.Comparison c = ProductionFile.compare(p, run);
		String w = c.warning(1990, Paths.get("calib", "1990", "production.csv"));

		assertTrue(w.contains("in 2 of 36"), w);
		int pasture = w.indexOf("Tanzania, pasture");
		int wheat = w.indexOf("Argentina, wheat");
		assertTrue(pasture > 0 && wheat > pasture, "largest first: " + w);
		assertTrue(w.contains("(+100.00%)"), w);
		assertTrue(w.contains("World totals"), w);
		assertEquals(11.1993 + 8.399999999999999e-4 + 0.1113, c.worldTotals().get("wheat")[0], 1e-12);
		assertEquals(12.1993 + 8.399999999999999e-4 + 0.1113, c.worldTotals().get("wheat")[1], 1e-12);
	}

	@Test
	void theBaselineCheckCsvHasEveryPair() throws Exception {
		Map<String, Map<String, Double>> p = ProductionFile.read(production1990Extract());
		Map<String, Map<String, Double>> run = copy(p);
		run.get("Tanzania").put("wheat", 0.2226);
		run.get("Tanzania").remove("rice");
		Path file = tempDir.resolve("plum_coupling").resolve("baseline_check.csv");

		ProductionFile.compare(p, run).writeCsv(file);

		List<String> lines = Files.readAllLines(file);
		assertEquals("Country,Commodity,Calibration,This_run,Difference,Relative_difference,Differs", lines.get(0));
		assertEquals(1 + 36, lines.size());
		assertTrue(lines.contains("Tanzania,wheat,0.1113,0.2226,0.1113,1.0,true"), String.join("\n", lines));
		assertTrue(lines.contains("Tanzania,rice,0.74,,-0.74,-1.0,true"), "a row missing from this run");
		assertTrue(lines.contains("Tanzania,energycrops,0.0,0.0,0.0,,false"), "no relative difference from 0");
	}
}
