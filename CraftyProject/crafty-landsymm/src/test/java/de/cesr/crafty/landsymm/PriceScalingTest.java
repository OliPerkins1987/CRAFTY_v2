package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.landsymm.PriceScaling.Basis;
import de.cesr.crafty.landsymm.PriceScaling.Scaling;

class PriceScalingTest {

	@TempDir
	Path tempDir;

	private PlumYearOutputs y2029;

	@BeforeEach
	void readExtract() throws Exception {
		y2029 = PlumYearOutputs.read(PlumYearOutputsTest.all1990Extract(), 2029);
	}

	// ---- The three bases, on all1990's 2029 rows ----

	@Test
	void exporterIsTheWorldPriceForEveryCountry() {
		assertEquals(0.10112894, PriceScaling.plumPrice(Basis.EXPORTER, y2029, "Tanzania", "wheat"));
		assertEquals(0.10112894, PriceScaling.plumPrice(Basis.EXPORTER, y2029, "Argentina", "wheat"));
		assertEquals(0.02986134, PriceScaling.plumPrice(Basis.EXPORTER, y2029, "Tanzania", "wood"));
	}

	@Test
	void importerIsTheWorldPriceTimesTheCountrysMarkUp() {
		// Tanzania's wheat: 0.10112894 x 0.1319 / 0.1013
		assertEquals(0.1316772674, PriceScaling.plumPrice(Basis.IMPORTER, y2029, "Tanzania", "wheat"), 1e-9);
		// Senegal's wood, from wood.txt: 0.02986134 x 0.0433 / 0.0299
		assertEquals(0.0432440141,
				PriceScaling.plumPrice(Basis.IMPORTER, y2029, "Senegal Gambia & Cabo Verde", "wood"), 1e-9);
	}

	@Test
	void domesticWeightsTheImportAndExportPricesByTheImportShare() {
		// Tanzania imports 0.0612 of 0.1725 Mt of wheat: w = 0.3548, m = 1.3021
		assertEquals(0.1119669553, PriceScaling.plumPrice(Basis.DOMESTIC, y2029, "Tanzania", "wheat"), 1e-9);
		// Senegal imports almost all its wheat: close to its import price
		assertEquals(0.1255934855,
				PriceScaling.plumPrice(Basis.DOMESTIC, y2029, "Senegal Gambia & Cabo Verde", "wheat"), 1e-9);
		// Argentina is a net exporter of ruminants: the world price
		assertEquals(0.13263045, PriceScaling.plumPrice(Basis.DOMESTIC, y2029, "Argentina", "ruminants"), 1e-12);
		// Senegal's wood, a little imported
		assertEquals(0.0299675889,
				PriceScaling.plumPrice(Basis.DOMESTIC, y2029, "Senegal Gambia & Cabo Verde", "wood"), 1e-9);
	}

	@Test
	void domesticFromOneYearIsPlumsOwnCountryPriceTheNextYear() throws Exception {
		Path domestic = PlumYearOutputsTest.all1990Extract().resolve("domestic.txt");
		List<String> compared = new ArrayList<>();
		CsvReader.forEachRow(domestic, List.of("Year", "Country", "Crop", "Consumer_price"), (line, v) -> {
			if (!v[0].equals("2030") || !PlumCommodities.PRICE_NAMES.contains(v[2])
					|| v[2].equals(PlumCommodities.ENERGY_CROPS)) {
				return;
			}
			double coupler = PriceScaling.plumPrice(Basis.DOMESTIC, y2029, v[1], v[2]);
			double plum = Double.parseDouble(v[3]);
			// PLUM writes domestic.txt prices to 4 decimals, so the mark-up and its own price are rounded
			assertEquals(plum, coupler, 0.002 * plum, v[1] + ", " + v[2]);
			compared.add(v[1] + "|" + v[2]);
		});
		assertEquals(3 * 12, compared.size(), "3 countries x 9 crops, ruminants, monogastrics, fish");
	}

	@Test
	void theImportShareFollowsPlumsRule() {
		assertEquals(1.0, PriceScaling.importShare(new PlumYearOutputs.Trade(0, 0.5, 0.13, 0.10), "x"),
				"no production: everything used is imported");
		assertEquals(0.0, PriceScaling.importShare(new PlumYearOutputs.Trade(10, -4, 0.13, 0.10), "x"),
				"a net exporter");
		assertEquals(0.0, PriceScaling.importShare(new PlumYearOutputs.Trade(10, -10, 0.13, 0.10), "x"),
				"exports all it produces");
		assertEquals(0.25, PriceScaling.importShare(new PlumYearOutputs.Trade(3, 1, 0.13, 0.10), "x"), 1e-12);
		assertThrows(CouplingInputException.class,
				() -> PriceScaling.importShare(new PlumYearOutputs.Trade(Double.NaN, 1, 0.13, 0.10), "x"));
	}

	@Test
	void aMissingOrNonPositivePriceStopsWithItsCountryAndCrop() {
		CouplingInputException noRow = assertThrows(CouplingInputException.class,
				() -> PriceScaling.plumPrice(Basis.IMPORTER, y2029, "Chile", "wheat"));
		assertTrue(noRow.getMessage().contains("Chile, wheat"), noRow.getMessage());

		CouplingInputException noPrice = assertThrows(CouplingInputException.class,
				() -> PriceScaling.plumPrice(Basis.EXPORTER, y2029, "Tanzania", "barley"));
		assertTrue(noPrice.getMessage().contains("barley"), noPrice.getMessage());

		CouplingInputException zeroExport = assertThrows(CouplingInputException.class,
				() -> PriceScaling.markUp(new PlumYearOutputs.Trade(1, 1, 0.13, 0), "Tanzania, wheat in 2029"));
		assertTrue(zeroExport.getMessage().contains("Tanzania, wheat"), zeroExport.getMessage());
	}

	// ---- Factors ----

	@Test
	void indexFactorsStartEachRegionAtItsOwnFilePrice() throws Exception {
		Fixture f = fixture();
		Map<String, Map<String, Double>> weights = f.weights(300);
		weights.get("Argentina").put("C3cereals", 250.0);

		PriceScaling exporter = PriceScaling.create(Scaling.INDEX, Basis.EXPORTER, f.services, f.key, weights, y2029);
		assertEquals(2966.5098833232, exporter.factor("Tanzania", "C3cereals").factor(), 1e-6);
		assertEquals(250 / 0.10112894, exporter.factor("Argentina", "C3cereals").factor(), 1e-6);
		assertEquals(300, exporter.price("Tanzania", "C3cereals", y2029), 1e-9, "no jump at the hand-over");
		assertEquals(250, exporter.price("Argentina", "C3cereals", y2029), 1e-9);

		PriceScaling domestic = PriceScaling.create(Scaling.INDEX, Basis.DOMESTIC, f.services, f.key, weights, y2029);
		assertEquals(300 / 0.1119669553, domestic.factor("Tanzania", "C3cereals").factor(), 1e-6);
		assertEquals(300, domestic.price("Tanzania", "C3cereals", y2029), 1e-9);
		assertEquals(300, domestic.price("Senegal_and_co", "Pasture", y2029), 1e-9, "pasture follows ruminants");
	}

	@Test
	void factorScalingUsesServicesCsvsPriceFactor() throws Exception {
		Fixture f = fixture();

		PriceScaling s = PriceScaling.create(Scaling.FACTOR, Basis.EXPORTER, f.services, f.key, f.weights(300),
				y2029);

		assertEquals(1000, s.factor("Tanzania", "C3cereals").factor());
		assertEquals(101.12894, s.price("Tanzania", "C3cereals", y2029), 1e-9);
	}

	@Test
	void underIndexImporterFollowsExporterUnlessTheMarkUpChanges() throws Exception {
		Fixture f = fixture();
		// Year 1: world wheat 0.10, Tanzania's import mark-up 1.3. Year 2: world 0.12, same mark-up.
		PlumYearOutputs start = synthetic("start", 0.10, 0.130, 0.100, 0.10, 0.05);
		PlumYearOutputs sameMarkUp = synthetic("same", 0.12, 0.156, 0.120, 0.10, 0.05);
		PlumYearOutputs higherMarkUp = synthetic("higher", 0.12, 0.180, 0.120, 0.10, 0.05);

		PriceScaling exporter = PriceScaling.create(Scaling.INDEX, Basis.EXPORTER, f.services, f.key, f.weights(300),
				start);
		PriceScaling importer = PriceScaling.create(Scaling.INDEX, Basis.IMPORTER, f.services, f.key, f.weights(300),
				start);

		assertEquals(360, exporter.price("Tanzania", "C3cereals", sameMarkUp), 1e-9, "world price up 20%");
		assertEquals(360, importer.price("Tanzania", "C3cereals", sameMarkUp), 1e-9, "the same, mark-up unchanged");
		assertTrue(importer.price("Tanzania", "C3cereals", higherMarkUp) > 360, "the mark-up rose");
	}

	@Test
	void underIndexDomesticFloatsWithTheImportShare() throws Exception {
		Fixture f = fixture();
		// Tanzania's wheat, mark-up 1.3 throughout and the world price unchanged.
		PlumYearOutputs start = synthetic("start", 0.10, 0.130, 0.100, 0.10, 0.05); // w = 1/3
		PlumYearOutputs moreImports = synthetic("more", 0.10, 0.130, 0.100, 0.10, 0.20); // w = 2/3
		PlumYearOutputs exports = synthetic("exports", 0.10, 0.130, 0.100, 0.10, -0.05); // w = 0

		PriceScaling exporter = PriceScaling.create(Scaling.INDEX, Basis.EXPORTER, f.services, f.key, f.weights(300),
				start);
		PriceScaling domestic = PriceScaling.create(Scaling.INDEX, Basis.DOMESTIC, f.services, f.key, f.weights(300),
				start);

		assertEquals(300, exporter.price("Tanzania", "C3cereals", moreImports), 1e-9, "world price unchanged");
		// domestic: 0.10 x (1 + w x 0.3): 0.11 at the start, 0.12 with w = 2/3, 0.10 as an exporter
		assertEquals(300 * 0.12 / 0.11, domestic.price("Tanzania", "C3cereals", moreImports), 1e-9);
		assertEquals(300 * 0.10 / 0.11, domestic.price("Tanzania", "C3cereals", exports), 1e-9);
	}

	@Test
	void everyFactorProblemIsReportedTogether() throws Exception {
		Fixture f = fixture();
		Map<String, Map<String, Double>> weights = f.weights(300);
		weights.get("Tanzania").put("C3rice", 0.0);
		weights.get("Argentina").remove("Timber");
		weights.put("Atlantis", new HashMap<>(weights.get("Tanzania")));

		CouplingInputException e = assertThrows(CouplingInputException.class,
				() -> PriceScaling.create(Scaling.INDEX, Basis.EXPORTER, f.services, f.key, weights, y2029));

		assertEquals(3, e.getProblems().size(), e.getMessage());
		assertTrue(e.getMessage().contains("Atlantis") && e.getMessage().contains("C3rice")
				&& e.getMessage().contains("Timber"), e.getMessage());
	}

	@Test
	void factorsAreWrittenToPriceFactorsCsv() throws Exception {
		Fixture f = fixture();
		PriceScaling s = PriceScaling.create(Scaling.INDEX, Basis.DOMESTIC, f.services, f.key, f.weights(300), y2029);
		Path file = tempDir.resolve("plum_coupling").resolve("price_factors.csv");

		s.writeCsv(file);

		List<String> lines = Files.readAllLines(file);
		assertEquals("Region,Country,Service,PLUM_price_name,Basis,Scaling,File_weight,CRAFTY_start_weight,"
				+ "PLUM_start_price,Factor", lines.get(0));
		assertEquals(1 + 3 * 13, lines.size(), "3 regions x 13 market services");
		// Argentina is a net exporter of ruminants, so its domestic price is the world price
		assertTrue(lines.contains("Argentina,Argentina,Pasture,ruminants,domestic,index,300.0,300.0," + 0.13263045 + ","
				+ (300 / 0.13263045)), String.join("\n", lines));
	}

	@Test
	void aRegionalStartGivesEachRegionItsCountrysMarkUpOnTheFilePrice() throws Exception {
		Fixture f = fixture();
		double world = y2029.price("wheat");

		for (Basis basis : List.of(Basis.DOMESTIC, Basis.IMPORTER)) {
			PriceScaling s = PriceScaling.create(Scaling.INDEX, basis, f.services, f.key, f.weights(300), y2029, true);
			for (String country : List.of("Tanzania", "Argentina")) {
				double countryPrice = PriceScaling.plumPrice(basis, y2029, country, "wheat");
				PriceScaling.Factor factor = s.factor(country, "C3cereals");
				assertEquals(300 / world, factor.factor(), 1e-9, basis + ", " + country + ": the world-price factor");
				assertEquals(300 * countryPrice / world, factor.startWeight(), 1e-9);
				assertEquals(300.0, factor.fileWeight(), 0.0);
				assertEquals(300 * countryPrice / world, s.price(country, "C3cereals", y2029), 1e-9,
						"the start year's price is the regional start");
			}
		}
		// Tanzania imports wheat, so it starts above the file price; Argentina exports it, so at the file price
		PriceScaling domestic = PriceScaling.create(Scaling.INDEX, Basis.DOMESTIC, f.services, f.key, f.weights(300),
				y2029, true);
		assertTrue(domestic.factor("Tanzania", "C3cereals").startWeight() > 300);
		assertEquals(300.0, domestic.factor("Argentina", "C3cereals").startWeight(), 1e-9);
	}

	@Test
	void aRegionalStartChangesNothingWithTheExporterBasisOrFactorScaling() throws Exception {
		Fixture f = fixture();
		for (Scaling scaling : Scaling.values()) {
			PriceScaling plain = PriceScaling.create(scaling, Basis.EXPORTER, f.services, f.key, f.weights(300), y2029);
			PriceScaling regional = PriceScaling.create(scaling, Basis.EXPORTER, f.services, f.key, f.weights(300), y2029,
					true);
			assertEquals(plain.price("Tanzania", "C3cereals", y2029), regional.price("Tanzania", "C3cereals", y2029),
					1e-12, scaling.key());
		}
		PriceScaling plainFactor = PriceScaling.create(Scaling.FACTOR, Basis.DOMESTIC, f.services, f.key,
				f.weights(300), y2029);
		PriceScaling regionalFactor = PriceScaling.create(Scaling.FACTOR, Basis.DOMESTIC, f.services, f.key,
				f.weights(300), y2029, true);
		assertEquals(plainFactor.price("Tanzania", "C3cereals", y2029),
				regionalFactor.price("Tanzania", "C3cereals", y2029), 0.0);
	}

	// ---- Fixtures ----

	/** The global Services.csv, and a key for the extract's three countries. */
	private record Fixture(ServiceCommodityMap services, RegionCountryKey key) {
		/** Every region's start-year weight for every market service. */
		Map<String, Map<String, Double>> weights(double weight) {
			Map<String, Map<String, Double>> weights = new HashMap<>();
			for (String region : key.regions()) {
				Map<String, Double> byService = new HashMap<>();
				services.links().forEach(l -> byService.put(l.service(), weight));
				weights.put(region, byService);
			}
			return weights;
		}
	}

	private Fixture fixture() throws Exception {
		Path gis = tempDir.resolve("global_Regions.csv");
		Files.writeString(gis, "\"X\",\"Y\",\"Region_Code\",\"PLUM_country\"\n"
				+ "1,1,\"Senegal_and_co\",\"Senegal Gambia & Cabo Verde\"\n2,1,\"Tanzania\",\"Tanzania\"\n"
				+ "3,1,\"Argentina\",\"Argentina\"\n");
		return new Fixture(ServiceCommodityMap.read(ServiceCommodityMapTest.globalServices()),
				RegionCountryKey.read(gis));
	}

	/**
	 * A one-year PLUM output folder for the three countries. Every crop, ruminants and wood have world price 0.10 and
	 * each country produces 1 and exports 0.5 of them at import price 0.13, except Tanzania's wheat, which has the
	 * given world price, import and export prices, production and net imports.
	 */
	private PlumYearOutputs synthetic(String name, double worldWheat, double tzImport, double tzExport,
			double tzProduction, double tzNetImports) throws IOException {
		Path dir = tempDir.resolve(name);
		Files.createDirectories(dir);
		List<String> names = new ArrayList<>(PlumCommodities.PRICE_NAMES);
		names.remove(PlumCommodities.WOOD);
		StringBuilder prices = new StringBuilder("Year,Crop,Imports (Mt),Exports (Mt),New export price, Stock Levels (Mt)\n");
		StringBuilder domestic = new StringBuilder("Year, Country, Crop, Area, Production, Production_cost, Import_price,"
				+ " Export_price, Consumer_price, Net_imports, Net_import_cost, Prod_shock, Rum_feed_amount,"
				+ " Mon_feed_amount, Fish_feed_amount\n");
		StringBuilder wood = new StringBuilder(
				"Year, Country, RoundwoodDemand, WoodfuelDemand, Production, Import_price, Export_price, Net_imports\n");
		for (String crop : PlumCommodities.PRICE_NAMES) {
			prices.append("2000,").append(crop).append(",1,1,").append(crop.equals("wheat") ? worldWheat : 0.10)
					.append(",1\n");
		}
		for (String country : List.of("Senegal Gambia & Cabo Verde", "Tanzania", "Argentina")) {
			for (String crop : names) {
				boolean tzWheat = country.equals("Tanzania") && crop.equals("wheat");
				domestic.append("2000,").append(country).append(',').append(crop).append(",NaN,")
						.append(tzWheat ? tzProduction : 1).append(",0,").append(tzWheat ? tzImport : 0.13).append(',')
						.append(tzWheat ? tzExport : 0.10).append(",0,").append(tzWheat ? tzNetImports : -0.5)
						.append(",0,0,0,0,0\n");
			}
			wood.append("2000,").append(country).append(",1,1,1,0.13,0.10,-0.5\n");
		}
		Files.writeString(dir.resolve("prices.txt"), prices);
		Files.writeString(dir.resolve("domestic.txt"), domestic);
		Files.writeString(dir.resolve("wood.txt"), wood);
		return PlumYearOutputs.read(dir, 2000);
	}
}
