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
import de.cesr.crafty.react.data.ReactElement;
import de.cesr.crafty.react.data.ReactInputs;
import de.cesr.crafty.react.data.ReactStartupCheck;
import de.cesr.crafty.react.data.ReactToyData;
import de.cesr.crafty.react.science.GoldenCsv;

/**
 * The forestry stage against R. {@code make_golden_32d.R} (kept outside the repo, in
 * {@code CRAFTY_PLUM/CRAFTY_dev/debugging/golden}) runs a small world of 12 pixels for three years through an R
 * version of the loop, built from {@code forestry_rules.R} (the 32c rules, checked against the calibration
 * code). It writes the world to {@code forestry_years_inputs.csv} and what each AFT decides in each unit and
 * year to {@code forestry_years.csv}.
 *
 * This test writes that world as a toy project with only forestry switched on, runs {@link ForestryDecisions}
 * over the same three years, with 10 spin-up steps (set in the project's react_config.yaml, as the R script has
 * them) then 1 a year, and checks every row, one test per quantity. The world has pixels in two regions with
 * different prices, a price crash and a price rise, a pixel with no forest, one with negative values at the long
 * rotations, and four AFTs: two Prospect (thresholds 0 and 0.3) and two Capital (sensitivities 0.05 and −0.02).
 */
class ForestryDecisionsGoldenTest {

	private static final String INPUTS = "forestry_years_inputs";
	private static final String EXPECTED = "forestry_years";
	private static final int FIRST_YEAR = 2020;
	private static final int LAST_YEAR = 2022;

	/** The spin-up make_golden_32d.R uses, set in the project's react_config.yaml. */
	private static final int SPINUP_STEPS = 10;

	/** The default rotations, which make_golden_32d.R uses. */
	private static final List<Integer> GRID = List.of(10, 20, 30, 40, 50, 60, 70, 80, 90, 100);

	/** The AFTs, as make_golden_32d.R has them. Initial rotations are set in the context below. */
	private static final String[] ROWS = {
			"IntBF,AFT,1,Hardwood,,,,,,,,0,Prospect,",
			"MixBF,AFT,1,Hardwood,,,,,,,,0.3,Prospect,",
			"ExtBF,AFT,1,Hardwood,,,,,,,,0.05,Capital,react_pop",
			"LogBF,AFT,1,Hardwood,,,,,,,,-0.02,Capital,react_pop" };

	/** What the Java decided: for each "year AFT unit", {rotation, yield, cost}. */
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
		ForestryDecisions forestry = ForestryDecisions.create(inputs, units);
		for (int year = FIRST_YEAR; year <= LAST_YEAR; year++) {
			YearPrices yearPrices = YearPrices.forYear(year, forestry.servicesNeedingPrices(), units,
					inputs.context().prices());
			for (ForestryManagement m : forestry.decide(inputs.forYear(year), yearPrices).values()) {
				for (int unit = 0; unit < units.size(); unit++) {
					DECIDED.put(year + " " + m.label() + " " + unit,
							new double[] { m.rotation()[unit], m.yield()[unit], m.cost()[unit] });
				}
			}
		}
	}

	/**
	 * Writes the world: pixel i has cell (i, 1) in the North and, if it straddles the border, cell (i, 2) in the
	 * South. Yields go into the forestry files as they are, in m³/ha/yr, as the real files hold them, so react
	 * reads them as it does in a run.
	 */
	private static ReactToyData.Context writeProject(Path dir, List<GoldenCsv.Row> world) {
		ReactToyData.services(dir);
		ReactToyData.write(dir, "costs/global/global_costs.csv", "Item,Cost,Notes", "Nfert,1.08,", "Water,0.5,",
				"Stocking,500,", "Hardwood,1750,");
		ReactToyData.parameters(dir, ROWS);
		ReactToyData.write(dir, ReactConfigLoader.LOCATION.toString(), "spinup_iterations: " + SPINUP_STEPS);
		ReactToyData.Context context = ReactToyData.context(dir).years(FIRST_YEAR, LAST_YEAR)
				.withoutAft("IntC3C_irrig").withoutAft("ExtC3C").withoutAft("IntP").withoutAft("IntFodder")
				.withoutAft("AF").withoutAft("Urban")
				.aft("IntBF", 0, 30, false, false)
				.aft("MixBF", 0, 50, false, false)
				.aft("ExtBF", 0, 100, false, false)
				.aft("LogBF", 0, 40, false, false)
				.off(ReactElement.FERTILISER, ReactElement.IRRIGATION, ReactElement.OTHER_INTENSITY, ReactElement.STOCKING)
				.on(ReactElement.FORESTRY);

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

		StringBuilder header = new StringBuilder("\"x\",\"y\"");
		for (int rotation : GRID) {
			header.append(",\"").append(ReactStartupCheck.forestryColumn(rotation)).append('"');
		}
		for (int year = FIRST_YEAR; year <= LAST_YEAR; year++) {
			List<String> forestry = new ArrayList<>(List.of(header.toString()));
			List<String> capitals = new ArrayList<>(List.of("Lon,Lat,react_pop"));
			for (GoldenCsv.Row row : world) {
				if ((int) row.get("year") != year) {
					continue;
				}
				StringBuilder line = new StringBuilder(lonLat(row));
				for (int rotation : GRID) {
					line.append(',').append(Double.toString(row.get("yield_" + rotation)));
				}
				forestry.add(line.toString());
				capitals.add(lonLat(row) + "," + row.get("pop"));
			}
			ReactToyData.write(dir, "worlds/react/suitabilities/ssp126/forestry/Suit_Forestry_" + year + ".csv",
					forestry.toArray(new String[0]));
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
		assertEquals(3 * 4 * 16, rows.size());
		assertEquals(DECIDED.size(), rows.size());
	}

	@Test
	void rotation() {
		GoldenCsv.check(EXPECTED, "rotation", row -> decided(row, 0));
	}

	@Test
	void yield() {
		GoldenCsv.check(EXPECTED, "yield", row -> decided(row, 1));
	}

	@Test
	void cost() {
		GoldenCsv.check(EXPECTED, "cost", row -> decided(row, 2));
	}
}
