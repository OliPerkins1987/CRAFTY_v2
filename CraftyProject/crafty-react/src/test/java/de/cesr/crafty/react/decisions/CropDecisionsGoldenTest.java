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
import de.cesr.crafty.react.data.ReactConfig;
import de.cesr.crafty.react.data.ReactInputs;
import de.cesr.crafty.react.data.ReactToyData;
import de.cesr.crafty.react.data.ReactYearData;
import de.cesr.crafty.react.science.CropSurfaces;
import de.cesr.crafty.react.science.GoldenCsv;

/**
 * The cropland stage against R. {@code make_golden_29b.R} (kept outside the repo, in
 * {@code CRAFTY_PLUM/CRAFTY_dev/debugging/golden}) runs a small world of 12 pixels for three years through
 * an R version of the loop built from the calibration functions ({@code Eff_func}, {@code prospect_Nuse}
 * with the Nmax fix, {@code yield_response}). It writes the world to {@code crop_years_inputs.csv} and
 * what each AFT decides in each unit and year to {@code crop_years.csv}.
 *
 * This test writes that world as a toy project, runs {@link CropDecisions} over the same three years with
 * the default settings (5 spin-up steps, then 1 a year), and checks every row, one test per quantity. The
 * world has pixels in two regions with different prices, units that reach Nmax and come back down after a
 * price crash, units that fall below the anchor, and pixels short of water.
 */
class CropDecisionsGoldenTest {

	private static final String INPUTS = "crop_years_inputs";
	private static final String EXPECTED = "crop_years";
	private static final int FIRST_YEAR = 2020;
	private static final int LAST_YEAR = 2022;

	/** The AFTs, as make_golden_29b.R has them. Baselines are set in the context below. */
	private static final String[] ROWS = {
			"IntC3C_irrig,AFT,1,C3cereals,Prospect,,0,0.9,react_GDP_100,0.2,,",
			"IntC3C,AFT,1,C3cereals,Prospect,,0.5,,react_GDP_50,0.3,,",
			"ExtC3C,AFT,1,C3cereals,Capital,react_GDP_50,1.5,,,,," };

	/** What the Java decided: for each "year AFT unit", {N, O, level, water, yield, nCost, waterCost, intensityCost}. */
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

		ReactInputs inputs = ReactInputs.create(ReactConfig.defaults(), context.build());
		key = inputs.checked().cellKey();
		units = DecisionUnits.build(key, inputs.context().regions());
		CropDecisions crops = CropDecisions.create(inputs, units);
		for (int year = FIRST_YEAR; year <= LAST_YEAR; year++) {
			ReactYearData data = inputs.forYear(year);
			YearPrices yearPrices = YearPrices.forYear(year, crops.servicesNeedingPrices(), units,
					inputs.context().prices());
			for (CropManagement m : crops.decide(data, CropSurfaces.fit(data), yearPrices).values()) {
				for (int unit = 0; unit < units.size(); unit++) {
					DECIDED.put(year + " " + m.label() + " " + unit, new double[] { m.nitrogen()[unit],
							m.otherIntensity()[unit], m.irrigationLevel()[unit], m.waterApplied()[unit],
							m.yield()[unit], m.nitrogenCost()[unit],
							m.aft().isIrrigated() ? m.irrigationCost()[unit] : 0, m.intensityCost()[unit] });
				}
			}
		}
	}

	/**
	 * Writes the world: pixel i has cell (i, 1) in the North and, if it straddles the border, cell (i, 2)
	 * in the South. Yields go into the files in kg/m² and water in mm, the files' default units, so react
	 * converts them back to t/ha and m³/ha as it does in a run.
	 */
	private static ReactToyData.Context writeProject(Path dir, List<GoldenCsv.Row> world) {
		ReactToyData.services(dir);
		ReactToyData.globalCosts(dir);
		ReactToyData.parameters(dir, ROWS);
		ReactToyData.Context context = ReactToyData.context(dir).years(FIRST_YEAR, LAST_YEAR)
				.withoutAft("IntP").withoutAft("IntFodder").withoutAft("AF").withoutAft("Urban")
				.aft("IntC3C_irrig", 200, 0.75, true, false)
				.aft("IntC3C", 150, 0.6, false, false)
				.aft("ExtC3C", 100, 0.4, false, false);

		List<String> cellKey = new ArrayList<>(List.of("ID,X,Y,LPJ_cell_x,LPJ_cell_y,region"));
		List<String> costIndex = new ArrayList<>(List.of("Lon,Lat,irrigation_cost"));
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
			costIndex.add(lonLat(row) + "," + row.get("costIndex"));
		}
		ReactToyData.write(dir, "worlds/react/cell_key.csv", cellKey.toArray(new String[0]));
		ReactToyData.write(dir, "worlds/react/irrigation/Irrigation_cost.csv", costIndex.toArray(new String[0]));

		for (int year = FIRST_YEAR; year <= LAST_YEAR; year++) {
			List<String> crops = new ArrayList<>(List.of(
					"Lon,Lat,CerealsC30,CerealsC30200,CerealsC31000,CerealsC3i0,CerealsC3i0200,CerealsC3i1000"));
			List<String> capitals = new ArrayList<>(List.of("Lon,Lat,react_GDP_50,react_GDP_100"));
			List<String> demand = new ArrayList<>(List.of("Lon,Lat,CerealsC3i0,CerealsC3i0200,CerealsC3i1000"));
			List<String> runoff = new ArrayList<>(List.of("Lon,Lat,Total"));
			for (GoldenCsv.Row row : world) {
				if ((int) row.get("year") != year) {
					continue;
				}
				crops.add(lonLat(row) + "," + tenth(row, "y0") + "," + tenth(row, "y0200") + "," + tenth(row, "y1000")
						+ "," + tenth(row, "yi0") + "," + tenth(row, "yi0200") + "," + tenth(row, "yi1000"));
				capitals.add(lonLat(row) + "," + row.get("gdp50") + "," + row.get("gdp100"));
				demand.add(lonLat(row) + "," + tenth(row, "d0") + "," + tenth(row, "d0200") + "," + tenth(row, "d1000"));
				runoff.add(lonLat(row) + "," + tenth(row, "runoff"));
			}
			ReactToyData.write(dir, "worlds/react/suitabilities/ssp126/crops/Suit_Agri_Crops_" + year + ".csv",
					crops.toArray(new String[0]));
			ReactToyData.write(dir, "worlds/react/capitals/ssp126/EU_capitals_ssp126_" + year + ".csv",
					capitals.toArray(new String[0]));
			ReactToyData.write(dir, "worlds/react/irrigation/ssp126/Irrigation_demand_" + year + ".csv",
					demand.toArray(new String[0]));
			ReactToyData.write(dir, "worlds/react/irrigation/ssp126/Runoff_" + year + ".csv",
					runoff.toArray(new String[0]));
		}
		return context;
	}

	private static String lonLat(GoldenCsv.Row row) {
		return row.get("Lon") + "," + row.get("Lat");
	}

	/** A value in the file's units: t/ha ÷ 10 = kg/m², m³/ha ÷ 10 = mm. */
	private static String tenth(GoldenCsv.Row row, String column) {
		return Double.toString(row.get(column) / 10);
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
			rows.add((int) row.get("year") + " " + row.text("aft") + " " + row.text("region") + " " + row.get("Lon"));
		}
		assertEquals(16, units.size(), "12 pixels, 4 of them in both regions");
		assertEquals(3 * 3 * 16, rows.size());
		assertEquals(DECIDED.size(), rows.size());
	}

	@Test
	void nitrogen() {
		GoldenCsv.check(EXPECTED, "N", row -> decided(row, 0));
	}

	@Test
	void otherIntensity() {
		GoldenCsv.check(EXPECTED, "O", row -> decided(row, 1));
	}

	@Test
	void irrigationLevel() {
		GoldenCsv.check(EXPECTED, "level", row -> decided(row, 2));
	}

	@Test
	void waterApplied() {
		GoldenCsv.check(EXPECTED, "water", row -> decided(row, 3));
	}

	@Test
	void yield() {
		GoldenCsv.check(EXPECTED, "yield", row -> decided(row, 4));
	}

	@Test
	void nitrogenCost() {
		GoldenCsv.check(EXPECTED, "nCost", row -> decided(row, 5));
	}

	@Test
	void irrigationCost() {
		GoldenCsv.check(EXPECTED, "waterCost", row -> decided(row, 6));
	}

	@Test
	void intensityCost() {
		GoldenCsv.check(EXPECTED, "intensityCost", row -> decided(row, 7));
	}
}
