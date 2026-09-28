package de.cesr.crafty.react.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.react.data.LpjGrid;
import de.cesr.crafty.react.data.ReactConfigLoader;
import de.cesr.crafty.react.data.ReactElement;
import de.cesr.crafty.react.data.ReactInputs;
import de.cesr.crafty.react.data.ReactToyData;
import de.cesr.crafty.react.data.ReactYearData;
import de.cesr.crafty.react.decisions.CropDecisions;
import de.cesr.crafty.react.decisions.CropManagement;
import de.cesr.crafty.react.decisions.DecisionUnits;
import de.cesr.crafty.react.decisions.PastureDecisions;
import de.cesr.crafty.react.decisions.PastureManagement;
import de.cesr.crafty.react.decisions.YearPrices;
import de.cesr.crafty.react.science.CropSurfaces;

/**
 * The inspection files, on the toy project: two pixels, A (in the North and the South) and B (North), so
 * three decision units; crops AFTs IntC3C_irrig (irrigated) and ExtC3C; pasture AFT IntP. Prices are 300 $/t
 * in the North and 150 in the South, and core writes its maps in 2020.
 */
class ReactOutputsTest {

	@TempDir
	Path dir;

	@BeforeEach
	void setUp() {
		ReactToyData.project(dir);
	}

	private void settings(String... lines) {
		ReactToyData.write(dir, ReactConfigLoader.LOCATION.toString(), lines);
	}

	/** Decides a year on the toy project and writes its inspection files. */
	private final class Run {
		final ReactInputs inputs;
		final DecisionUnits units;
		final CropDecisions crops;
		final PastureDecisions pasture;
		final ReactOutputs outputs;
		ReactYearData data;
		CropSurfaces surfaces;
		YearPrices prices;

		Run(ReactToyData.Context context) {
			inputs = ReactInputs.create(ReactConfigLoader.load(dir), context.build());
			units = DecisionUnits.build(inputs.checked().cellKey(), inputs.context().regions());
			crops = CropDecisions.create(inputs, units);
			pasture = PastureDecisions.create(inputs, units);
			outputs = ReactOutputs.create(inputs, units);
		}

		List<Path> decideAndWrite(int year) {
			data = inputs.forYear(year);
			surfaces = CropSurfaces.fit(data);
			Set<String> services = new LinkedHashSet<>(crops.servicesNeedingPrices());
			services.addAll(pasture.servicesNeedingPrices());
			prices = YearPrices.forYear(year, services, units, inputs.context().prices());
			crops.decide(data, surfaces, prices);
			pasture.decide(data, prices);
			return outputs.write(data, surfaces, prices, crops.managements().values(), pasture.managements().values());
		}

		CropManagement crop(String label) {
			return crops.managements().get(label);
		}

		PastureManagement intP() {
			return pasture.managements().get("IntP");
		}

		LpjGrid grid() {
			return inputs.checked().cellKey().grid();
		}
	}

	private Run run(ReactToyData.Context context) {
		return new Run(context);
	}

	private Run run() {
		return run(context());
	}

	private ReactToyData.Context context() {
		return ReactToyData.context(dir).outputFolder(dir.resolve("out")).mapYears(2020)
				.prices((service, region, year) -> region.equals("North") ? 300 : 150);
	}

	private Path file(String name, int year) {
		return dir.resolve("out").resolve("react").resolve("ssp126-React-" + name + "-" + year + ".csv");
	}

	private static List<String> names(List<Path> files) {
		return files.stream().map(f -> f.getFileName().toString()).toList();
	}

	private static List<String[]> rows(Path file) throws IOException {
		List<String[]> rows = new ArrayList<>();
		for (String line : Files.readAllLines(file)) {
			rows.add(line.split(",", -1));
		}
		return rows;
	}

	/** Checks a per-unit file: its header, and each row's place and values against react's arrays. */
	private void assertUnitFile(Run run, Path file, List<String> afts, List<double[]> values) throws IOException {
		List<String[]> rows = rows(file);
		List<String> header = new ArrayList<>(List.of("Lon", "Lat", "region"));
		header.addAll(afts);
		assertEquals(header, Arrays.asList(rows.get(0)), file.getFileName().toString());
		assertEquals(run.units.size() + 1, rows.size(), "one row per decision unit");
		for (int unit = 0; unit < run.units.size(); unit++) {
			String[] row = rows.get(unit + 1);
			int pixel = run.units.pixel(unit);
			assertEquals(run.grid().lon(pixel), Double.parseDouble(row[0]));
			assertEquals(run.grid().lat(pixel), Double.parseDouble(row[1]));
			assertEquals(run.units.region(unit), row[2]);
			for (int aft = 0; aft < afts.size(); aft++) {
				assertEquals(values.get(aft)[unit], Double.parseDouble(row[aft + 3]), 0, afts.get(aft) + " unit " + unit);
			}
		}
	}

	// ---- which years, and whether anything ----

	@Test
	void nothingIsWrittenWhenEverySwitchIsOff() {
		Run run = run();

		assertFalse(run.outputs.writes(2020));
		assertTrue(run.decideAndWrite(2020).isEmpty());
		assertFalse(Files.exists(dir.resolve("out").resolve("react")));
	}

	@Test
	void filesAreWrittenInCoresMapYearsUnlessAskedForEveryYear() {
		settings("outputs:", "  crops: true");
		Run maps = run();
		assertTrue(maps.outputs.writes(2020), "core writes its maps in 2020");
		assertFalse(maps.outputs.writes(2021));

		settings("outputs:", "  crops: true", "  every_year: true");
		Run everyYear = run();
		assertTrue(everyYear.outputs.writes(2021));
		everyYear.decideAndWrite(2020);
		everyYear.decideAndWrite(2021);
		assertTrue(Files.exists(file("Crops-N", 2021)));
	}

	// ---- crops and pasture: one row per unit, one column per AFT ----

	@Test
	void theCropsFilesHoldEachQuantityOfEachCropsAft() throws IOException {
		settings("outputs:", "  crops: true");
		Run run = run();

		List<Path> written = run.decideAndWrite(2020);

		assertEquals(List.of("ssp126-React-Crops-N-2020.csv", "ssp126-React-Crops-OtherIntensity-2020.csv",
				"ssp126-React-Crops-IrrigationLevel-2020.csv", "ssp126-React-Crops-WaterApplied-2020.csv",
				"ssp126-React-Crops-Yield-2020.csv", "ssp126-React-Crops-NCost-2020.csv",
				"ssp126-React-Crops-WaterCost-2020.csv", "ssp126-React-Crops-IntensityCost-2020.csv",
				"ssp126-React-Prices-2020.csv"), names(written));
		CropManagement irrigated = run.crop("IntC3C_irrig");
		CropManagement capital = run.crop("ExtC3C");
		List<String> both = List.of("IntC3C_irrig", "ExtC3C");
		assertUnitFile(run, file("Crops-N", 2020), both, List.of(irrigated.nitrogen(), capital.nitrogen()));
		assertUnitFile(run, file("Crops-OtherIntensity", 2020), both,
				List.of(irrigated.otherIntensity(), capital.otherIntensity()));
		assertUnitFile(run, file("Crops-IrrigationLevel", 2020), both,
				List.of(irrigated.irrigationLevel(), capital.irrigationLevel()));
		assertUnitFile(run, file("Crops-WaterApplied", 2020), both,
				List.of(irrigated.waterApplied(), capital.waterApplied()));
		assertUnitFile(run, file("Crops-Yield", 2020), both, List.of(irrigated.yield(), capital.yield()));
		assertUnitFile(run, file("Crops-NCost", 2020), both, List.of(irrigated.nitrogenCost(), capital.nitrogenCost()));
		assertUnitFile(run, file("Crops-IntensityCost", 2020), both,
				List.of(irrigated.intensityCost(), capital.intensityCost()));
		assertUnitFile(run, file("Crops-WaterCost", 2020), List.of("IntC3C_irrig"), List.of(irrigated.irrigationCost()));
	}

	@Test
	void aCostThatWasNotWorkedOutHasNoFile() {
		settings("outputs:", "  crops: true");
		Run run = run(context().off(ReactElement.FERTILISER, ReactElement.IRRIGATION));

		List<String> written = names(run.decideAndWrite(2020));

		assertFalse(written.contains("ssp126-React-Crops-NCost-2020.csv"), "fertiliser is off");
		assertFalse(written.contains("ssp126-React-Crops-WaterCost-2020.csv"), "irrigation is off");
		assertTrue(written.contains("ssp126-React-Crops-IntensityCost-2020.csv"));
	}

	@Test
	void thePastureFilesHoldEachQuantityOfEachPastureAft() throws IOException {
		settings("outputs:", "  pasture: true");
		Run run = run();

		List<Path> written = run.decideAndWrite(2020);

		assertEquals(List.of("ssp126-React-Pasture-Husbandry-2020.csv", "ssp126-React-Pasture-Stocking-2020.csv",
				"ssp126-React-Pasture-Production-2020.csv", "ssp126-React-Pasture-IntensityCost-2020.csv",
				"ssp126-React-Pasture-StockingCost-2020.csv", "ssp126-React-Prices-2020.csv"), names(written));
		PastureManagement intP = run.intP();
		assertUnitFile(run, file("Pasture-Husbandry", 2020), List.of("IntP"), List.of(intP.husbandry()));
		assertUnitFile(run, file("Pasture-Stocking", 2020), List.of("IntP"), List.of(intP.stocking()));
		assertUnitFile(run, file("Pasture-Production", 2020), List.of("IntP"), List.of(intP.production()));
		assertUnitFile(run, file("Pasture-IntensityCost", 2020), List.of("IntP"), List.of(intP.intensityCost()));
		assertUnitFile(run, file("Pasture-StockingCost", 2020), List.of("IntP"), List.of(intP.stockingCost()));
	}

	@Test
	void withStockingOffThereIsNoStockingFile() {
		settings("outputs:", "  pasture: true");
		Run run = run(context().off(ReactElement.STOCKING));

		List<String> written = names(run.decideAndWrite(2020));

		assertFalse(written.contains("ssp126-React-Pasture-Stocking-2020.csv"), "the model's production level is it");
		assertFalse(written.contains("ssp126-React-Pasture-StockingCost-2020.csv"));
		assertTrue(written.contains("ssp126-React-Pasture-Production-2020.csv"));
	}

	@Test
	void thePricesFileHoldsThePricesReactUsed() throws IOException {
		settings("outputs:", "  crops: true");
		run().decideAndWrite(2020);

		assertEquals(List.of("service,region,price", "C3cereals,North,300.0", "C3cereals,South,150.0",
				"Pasture,North,300.0", "Pasture,South,150.0"), Files.readAllLines(file("Prices", 2020)));
	}

	// ---- inputs and coefficients: one row per pixel ----

	@Test
	void theInputsFileHoldsTheYearsInputsPerPixel() throws IOException {
		settings("outputs:", "  inputs: true");
		Run run = run();
		run.decideAndWrite(2020);

		List<String[]> rows = rows(file("Inputs", 2020));
		List<String> header = Arrays.asList(rows.get(0));
		assertEquals(List.of("Lon", "Lat", "yield_CerealsC30", "yield_CerealsC30200", "yield_CerealsC31000",
				"yield_CerealsC3i0", "yield_CerealsC3i0200", "yield_CerealsC3i1000", "demand_CerealsC3i0",
				"demand_CerealsC3i0200", "demand_CerealsC3i1000", "runoff", "npp_Pasture_sum", "capital_react_GDP_100",
				"capital_react_GDP_50", "irrigation_cost_index"), header);
		assertEquals(run.grid().size() + 1, rows.size(), "one row per pixel");
		for (int pixel = 0; pixel < run.grid().size(); pixel++) {
			String[] row = rows.get(pixel + 1);
			assertEquals(run.data.crop("CerealsC3", "0200")[pixel], Float.parseFloat(row[header.indexOf("yield_CerealsC30200")]));
			assertEquals(run.data.waterDemand("CerealsC3", "i1000")[pixel],
					Float.parseFloat(row[header.indexOf("demand_CerealsC3i1000")]));
			assertEquals(run.data.runoff()[pixel], Float.parseFloat(row[header.indexOf("runoff")]));
			assertEquals(run.data.pasture("Pasture_sum")[pixel], Float.parseFloat(row[header.indexOf("npp_Pasture_sum")]));
			assertEquals(run.data.capital("react_GDP_50")[pixel],
					Float.parseFloat(row[header.indexOf("capital_react_GDP_50")]));
			assertEquals(run.inputs.checked().irrigationCost().at(pixel),
					Double.parseDouble(row[header.indexOf("irrigation_cost_index")]), 1e-6);
		}
	}

	@Test
	void theCoefficientsFileHoldsTheFittedSurfacesPerPixel() throws IOException {
		settings("outputs:", "  coefficients: true");
		Run run = run();
		run.decideAndWrite(2020);

		List<String[]> rows = rows(file("Coefficients", 2020));
		List<String> header = Arrays.asList(rows.get(0));
		assertEquals(List.of("Lon", "Lat", "CerealsC3_A", "CerealsC3_B", "CerealsC3_C", "CerealsC3_D", "CerealsC3_alpha",
				"CerealsC3_beta", "CerealsC3_D0", "CerealsC3_D1000", "CerealsC3_alphaD"), header);
		CropSurfaces.Surface surface = run.surfaces.crop("CerealsC3");
		CropSurfaces.WaterDemand demand = run.surfaces.waterDemand("CerealsC3");
		for (int pixel = 0; pixel < run.grid().size(); pixel++) {
			String[] row = rows.get(pixel + 1);
			assertEquals(surface.a()[pixel], Double.parseDouble(row[2]), 0);
			assertEquals(surface.alpha()[pixel], Double.parseDouble(row[6]), 0);
			assertEquals(surface.beta()[pixel], Double.parseDouble(row[7]), 0);
			assertEquals(demand.alphaD()[pixel], Double.parseDouble(row[10]), 0);
		}
	}

	@Test
	void numbersUseADotWhateverTheLocale() throws IOException {
		settings("outputs:", "  crops: true", "  inputs: true");
		Locale original = Locale.getDefault();
		try {
			Locale.setDefault(Locale.GERMANY);
			run().decideAndWrite(2020);
		} finally {
			Locale.setDefault(original);
		}

		for (String name : List.of("Crops-Yield", "Inputs")) {
			List<String[]> rows = rows(file(name, 2020));
			for (String[] row : rows) {
				assertEquals(rows.get(0).length, row.length, name + ": a decimal comma would add a column");
			}
			assertTrue(rows.get(1)[3].contains("."), name + ": " + rows.get(1)[3]);
		}
	}
}
