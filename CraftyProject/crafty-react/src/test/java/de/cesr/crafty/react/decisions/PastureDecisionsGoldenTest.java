package de.cesr.crafty.react.decisions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.react.data.CellKey;
import de.cesr.crafty.react.data.ReactConfigLoader;
import de.cesr.crafty.react.data.ReactInputs;
import de.cesr.crafty.react.data.ReactToyData;
import de.cesr.crafty.react.science.GoldenCsv;

/**
 * The pasture stage against R. {@code make_golden_30b.R} (kept outside the repo, in
 * {@code CRAFTY_PLUM/CRAFTY_dev/debugging/golden}) runs a small world of 12 pixels for three years through
 * an R version of the loop built from the calibration functions ({@code animal.husbandry},
 * {@code prospect_stocking}). It writes the world to {@code pasture_years_inputs.csv} and what each AFT
 * decides in each unit and year to {@code pasture_years.csv}.
 *
 * This test writes that world as a toy project, runs {@link PastureDecisions} over the same three years, with
 * husbandry and stocking both reactive and 10 spin-up steps (set in the project's react_config.yaml, as the
 * R script has them), then 1 a year. It checks every row, one test per quantity. The world has pixels in two
 * regions with different prices, units that reach the top stocking rate and come down after a price crash,
 * a pixel with no grass and one whose NPP is below 0, and an AFT whose threshold holds back stocking up.
 */
class PastureDecisionsGoldenTest {

	private static final String INPUTS = "pasture_years_inputs";
	private static final String EXPECTED = "pasture_years";
	private static final int FIRST_YEAR = 2020;
	private static final int LAST_YEAR = 2022;

	/** The spin-up make_golden_30b.R uses, set in the project's react_config.yaml. */
	private static final int SPINUP_STEPS = 10;

	/** The AFTs, as make_golden_30b.R has them. Baselines are set in the context below. */
	private static final String[] ROWS = {
			"IntP,AFT,1,Pasture,,,,,react_GDP_100,1,0,",
			"ExtP,AFT,1,Pasture,,,,,react_GDP_50,0.8,0.33,",
			"VExtP,AFT,1,Pasture,,,,,react_GDP_50,0,0.1," };

	/** What the Java decided: for each "year AFT unit", {S, h, production, intensityCost, stockingCost}. */
	private static final Map<String, double[]> DECIDED = new HashMap<>();

	private static CellKey key;
	private static DecisionUnits units;

	@BeforeAll
	static void runTheWorld(@TempDir Path dir) {
		List<GoldenCsv.Row> world = GoldenCsv.read(INPUTS);
		ReactToyData.Context context = writeProject(dir, world);

		// Prices by region and year, from the expected file.
		Map<String, Double> prices = new HashMap<>();
		for (GoldenCsv.Row row : GoldenCsv.read(EXPECTED)) {
			prices.put(row.text("region") + " " + (int) row.get("year"), row.get("price"));
		}
		context.prices((service, region, year) -> prices.get(region + " " + year));

		ReactInputs inputs = ReactInputs.create(ReactConfigLoader.load(dir), context.build());
		key = inputs.checked().cellKey();
		units = DecisionUnits.build(key, inputs.context().regions());
		PastureDecisions pasture = PastureDecisions.create(inputs, units);
		for (int year = FIRST_YEAR; year <= LAST_YEAR; year++) {
			YearPrices yearPrices = YearPrices.forYear(year, pasture.servicesNeedingPrices(), units,
					inputs.context().prices());
			for (PastureManagement m : pasture.decide(inputs.forYear(year), yearPrices).values()) {
				for (int unit = 0; unit < units.size(); unit++) {
					DECIDED.put(year + " " + m.label() + " " + unit, new double[] { m.stocking()[unit],
							m.husbandry()[unit], m.production()[unit], m.intensityCost()[unit], m.stockingCost()[unit] });
				}
			}
		}
	}

	/**
	 * Writes the world: pixel i has cell (i, 1) in the North and, if it straddles the border, cell (i, 2) in
	 * the South. NPP goes into the files in kg/m², the files' default units, so react converts it back to
	 * t/ha as it does in a run.
	 */
	private static ReactToyData.Context writeProject(Path dir, List<GoldenCsv.Row> world) {
		ReactToyData.services(dir);
		ReactToyData.globalCosts(dir);
		ReactToyData.parameters(dir, ROWS);
		ReactToyData.write(dir, ReactConfigLoader.LOCATION.toString(), "spinup_iterations: " + SPINUP_STEPS);
		ReactToyData.Context context = ReactToyData.context(dir).years(FIRST_YEAR, LAST_YEAR)
				.withoutAft("IntC3C_irrig").withoutAft("ExtC3C").withoutAft("IntFodder").withoutAft("AF")
				.withoutAft("Urban")
				.aft("IntP", 0, 1.5, false, true)
				.aft("ExtP", 0, 1.0, false, true)
				.aft("VExtP", 0, 0.6, false, true);

		List<String> cellKey = new ArrayList<>(List.of("ID,X,Y,LPJ_cell_x,LPJ_cell_y,region"));
		int pixel = 0;
		for (GoldenCsv.Row row : world) {
			if ((int) row.get("year") != FIRST_YEAR) {
				continue;
			}
			pixel++;
			cellKey.add(pixel + "," + pixel + ",1," + lonLat(row) + ",North");
			context.cell(pixel + ",1", "North");
			if (row.get("straddles") == 1) {
				cellKey.add((100 + pixel) + "," + pixel + ",2," + lonLat(row) + ",South");
				context.cell(pixel + ",2", "South");
			}
		}
		ReactToyData.write(dir, "worlds/react/cell_key.csv", cellKey.toArray(new String[0]));

		for (int year = FIRST_YEAR; year <= LAST_YEAR; year++) {
			List<String> pasture = new ArrayList<>(List.of("Lon,Lat,Pasture_sum"));
			List<String> capitals = new ArrayList<>(List.of("Lon,Lat,react_GDP_50,react_GDP_100"));
			for (GoldenCsv.Row row : world) {
				if ((int) row.get("year") != year) {
					continue;
				}
				pasture.add(lonLat(row) + "," + Double.toString(row.get("npp") / 10));
				capitals.add(lonLat(row) + "," + row.get("gdp50") + "," + row.get("gdp100"));
			}
			ReactToyData.write(dir, "worlds/react/suitabilities/ssp126/pasture/Suit_Agri_pastoral_" + year + ".csv",
					pasture.toArray(new String[0]));
			ReactToyData.write(dir, "worlds/react/capitals/ssp126/EU_capitals_ssp126_" + year + ".csv",
					capitals.toArray(new String[0]));
		}
		return context;
	}

	private static String lonLat(GoldenCsv.Row row) {
		return row.get("Lon") + "," + row.get("Lat");
	}

	/** The Java's value for an expected row. */
	private static double decided(GoldenCsv.Row row, int quantity) {
		int pixel = key.grid().indexOf(row.get("Lon"), row.get("Lat"));
		String region = row.text("region");
		for (int unit = 0; unit < units.size(); unit++) {
			if (units.pixel(unit) == pixel && units.region(unit).equals(region)) {
				return DECIDED.get((int) row.get("year") + " " + row.text("aft") + " " + unit)[quantity];
			}
		}
		throw new IllegalStateException("No unit for pixel " + pixel + " in " + region);
	}

	@Test
	void everyAftUnitAndYearIsInTheGoldenFile() {
		Set<String> rows = new LinkedHashSet<>();
		for (GoldenCsv.Row row : GoldenCsv.read(EXPECTED)) {
			rows.add((int) row.get("year") + " " + row.text("aft") + " " + row.text("region") + " " + row.get("Lon")
					+ " " + row.get("Lat"));
		}
		assertEquals(16, units.size(), "12 pixels, 4 of them in both regions");
		assertEquals(3 * 3 * 16, rows.size());
		assertEquals(DECIDED.size(), rows.size());
	}

	@Test
	void stockingRate() {
		GoldenCsv.check(EXPECTED, "S", row -> decided(row, 0));
	}

	@Test
	void husbandry() {
		GoldenCsv.check(EXPECTED, "h", row -> decided(row, 1));
	}

	@Test
	void production() {
		GoldenCsv.check(EXPECTED, "production", row -> decided(row, 2));
	}

	@Test
	void husbandryCost() {
		GoldenCsv.check(EXPECTED, "intensityCost", row -> decided(row, 3));
	}

	@Test
	void stockingCost() {
		GoldenCsv.check(EXPECTED, "stockingCost", row -> decided(row, 4));
	}
}
