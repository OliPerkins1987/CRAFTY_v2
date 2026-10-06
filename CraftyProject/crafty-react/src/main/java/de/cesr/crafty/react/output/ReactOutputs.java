package de.cesr.crafty.react.output;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;

import de.cesr.crafty.react.data.IrrigationCostGrid;
import de.cesr.crafty.react.data.LpjGrid;
import de.cesr.crafty.react.data.ReactConfig;
import de.cesr.crafty.react.data.ReactInputException;
import de.cesr.crafty.react.data.ReactInputs;
import de.cesr.crafty.react.data.ReactRunContext;
import de.cesr.crafty.react.data.ReactStartupCheck;
import de.cesr.crafty.react.data.ReactYearData;
import de.cesr.crafty.react.decisions.CropManagement;
import de.cesr.crafty.react.decisions.DecisionUnits;
import de.cesr.crafty.react.decisions.ForestryManagement;
import de.cesr.crafty.react.decisions.PastureManagement;
import de.cesr.crafty.react.decisions.YearPrices;
import de.cesr.crafty.react.science.CropSurfaces;

/**
 * CRAFTY-react's inspection files: what react read, fitted and decided, written to
 * {@code <output folder>/react/}. The model never reads them. They are for checking react's numbers,
 * reproducing them from the inputs, and downscaling react's values to cells in post-processing: join a
 * file to {@code cell_key.csv} on Lon, Lat and region.
 *
 * Each kind is switched on in the {@code outputs} section of {@code react_config.yaml}, and written in the
 * years core writes its cell maps ({@code map_output_years}), or every year with {@code every_year: true}.
 * Files are named {@code <scenario>-React-<name>-<year>.csv}:
 * <ul>
 * <li>{@code inputs}: {@code Inputs}, one row per pixel (Lon, Lat) and one column per input, in real units
 * as loaded: {@code yield_<crop><level>} (t/ha), {@code demand_<crop><level>} (m³/ha), {@code runoff}
 * (m³/ha), {@code npp_<pasture>} (t/ha), {@code yield_forestry_<rotation>} (m³/ha/yr),
 * {@code capital_<name>} and {@code irrigation_cost_index};</li>
 * <li>{@code coefficients}: {@code Coefficients}, one row per pixel and one column per crop and
 * coefficient: {@code <crop>_A}, {@code _B}, {@code _C}, {@code _D}, {@code _alpha}, {@code _beta}, and
 * {@code _D0}, {@code _D1000}, {@code _alphaD} for irrigated crops;</li>
 * <li>{@code crops}: one file per quantity, one row per decision unit (Lon, Lat, region) and one column per
 * crops AFT: {@code Crops-N}, {@code Crops-OtherIntensity}, {@code Crops-IrrigationLevel},
 * {@code Crops-WaterApplied}, {@code Crops-Yield} (the AFT's {@code _suit}), and the costs that were worked
 * out: {@code Crops-NCost}, {@code Crops-WaterCost}, {@code Crops-IntensityCost};</li>
 * <li>{@code pasture}: the same for pasture AFTs: {@code Pasture-Husbandry}, {@code Pasture-Stocking},
 * {@code Pasture-Production} (the AFT's {@code _suit}), {@code Pasture-IntensityCost},
 * {@code Pasture-StockingCost};</li>
 * <li>{@code forestry}: the same for forestry AFTs: {@code Forestry-Rotation} (years, whole numbers),
 * {@code Forestry-Yield} (the AFT's {@code _suit}, m³/ha/yr), {@code Forestry-IntensityCost} (the rotation
 * cost, $/ha/yr);</li>
 * <li>with {@code crops}, {@code pasture} or {@code forestry}: {@code Prices}, the price react used for each
 * service in each region ({@code service,region,price}).</li>
 * </ul>
 * A file only has the AFTs its quantity applies to (only irrigated AFTs in {@code Crops-WaterCost}, for
 * example), and a quantity that was not worked out has no file. Numbers are written in full, with a
 * {@code .} for the decimal point whatever the machine's locale.
 */
public final class ReactOutputs {

	/** The folder, inside the run's output folder, that holds the files. */
	public static final String FOLDER = "react";

	/** One column of a per-pixel file: its name, and its value for a pixel. */
	private record Column(String name, IntFunction<String> value) {
	}

	private final ReactConfig config;
	private final String scenario;
	private final Path folder;
	private final Set<Integer> mapYears;
	private final LpjGrid grid;
	private final DecisionUnits units;
	/** Null when no reactive AFT irrigates, so the index was not loaded. */
	private final IrrigationCostGrid irrigationCost;

	private ReactOutputs(ReactConfig config, String scenario, Path folder, Set<Integer> mapYears, LpjGrid grid,
			DecisionUnits units, IrrigationCostGrid irrigationCost) {
		this.config = config;
		this.scenario = scenario;
		this.folder = folder;
		this.mapYears = mapYears;
		this.grid = grid;
		this.units = units;
		this.irrigationCost = irrigationCost;
	}

	/** The inspection files for a run, as its settings ask. */
	public static ReactOutputs create(ReactInputs inputs, DecisionUnits units) {
		ReactRunContext context = inputs.context();
		return new ReactOutputs(inputs.config(), context.scenario(), context.outputFolder().resolve(FOLDER),
				context.mapYears(), inputs.checked().cellKey().grid(), units, inputs.checked().irrigationCost());
	}

	/** Where the files go: {@code react} in the run's output folder. */
	public Path folder() {
		return folder;
	}

	/** Whether any file is written for a year: something is switched on, and the year is one to write. */
	public boolean writes(int year) {
		boolean any = config.outputInputs() || config.outputCoefficients() || config.outputCrops()
				|| config.outputPasture() || config.outputForestry();
		return any && (config.outputEveryYear() || mapYears.contains(year));
	}

	/**
	 * Writes a year's files, if it is a year to write.
	 *
	 * @param year     the year's data
	 * @param surfaces the year's crop surfaces, or null when no crops AFT was decided
	 * @param prices   the prices react used this year
	 * @param crops    each crops AFT's management for the year (empty when none was decided)
	 * @param pasture  each pasture AFT's management for the year (empty when none was decided)
	 * @param forestry each forestry AFT's management for the year (empty when none was decided)
	 * @return the files written, in order
	 * @throws ReactInputException naming the file, if one cannot be written
	 */
	public List<Path> write(ReactYearData year, CropSurfaces surfaces, YearPrices prices,
			Collection<CropManagement> crops, Collection<PastureManagement> pasture,
			Collection<ForestryManagement> forestry) {
		int y = year.year();
		List<Path> written = new ArrayList<>();
		if (!writes(y)) {
			return written;
		}
		if (config.outputInputs()) {
			writePixels(written, "Inputs", y, inputColumns(year));
		}
		if (config.outputCoefficients() && surfaces != null) {
			writePixels(written, "Coefficients", y, coefficientColumns(surfaces));
		}
		if (config.outputCrops()) {
			List<CropManagement> all = List.copyOf(crops);
			writeUnits(written, "Crops-N", y, all, CropManagement::label, CropManagement::nitrogen);
			writeUnits(written, "Crops-OtherIntensity", y, all, CropManagement::label, CropManagement::otherIntensity);
			writeUnits(written, "Crops-IrrigationLevel", y, all, CropManagement::label, CropManagement::irrigationLevel);
			writeUnits(written, "Crops-WaterApplied", y, all, CropManagement::label, CropManagement::waterApplied);
			writeUnits(written, "Crops-Yield", y, all, CropManagement::label, CropManagement::yield);
			writeUnits(written, "Crops-NCost", y, only(all, CropManagement::hasNitrogenCost), CropManagement::label,
					CropManagement::nitrogenCost);
			writeUnits(written, "Crops-WaterCost", y, only(all, CropManagement::hasIrrigationCost),
					CropManagement::label, CropManagement::irrigationCost);
			writeUnits(written, "Crops-IntensityCost", y, only(all, CropManagement::hasIntensityCost),
					CropManagement::label, CropManagement::intensityCost);
		}
		if (config.outputPasture()) {
			List<PastureManagement> all = List.copyOf(pasture);
			List<PastureManagement> stocked = only(all, PastureManagement::decidesStocking);
			writeUnits(written, "Pasture-Husbandry", y, all, PastureManagement::label, PastureManagement::husbandry);
			writeUnits(written, "Pasture-Stocking", y, stocked, PastureManagement::label, PastureManagement::stocking);
			writeUnits(written, "Pasture-Production", y, all, PastureManagement::label, PastureManagement::production);
			writeUnits(written, "Pasture-IntensityCost", y, only(all, PastureManagement::hasIntensityCost),
					PastureManagement::label, PastureManagement::intensityCost);
			writeUnits(written, "Pasture-StockingCost", y, stocked, PastureManagement::label,
					PastureManagement::stockingCost);
		}
		if (config.outputForestry()) {
			List<ForestryManagement> all = List.copyOf(forestry);
			writeUnitValues(written, "Forestry-Rotation", y, all, ForestryManagement::label, m -> {
				int[] rotation = m.rotation();
				return unit -> String.valueOf(rotation[unit]);
			});
			writeUnits(written, "Forestry-Yield", y, all, ForestryManagement::label, ForestryManagement::yield);
			writeUnits(written, "Forestry-IntensityCost", y, all, ForestryManagement::label, ForestryManagement::cost);
		}
		if ((config.outputCrops() || config.outputPasture() || config.outputForestry())
				&& !prices.services().isEmpty()) {
			writePrices(written, y, prices);
		}
		return written;
	}

	private static <M> List<M> only(List<M> managements, Predicate<M> test) {
		return managements.stream().filter(test).toList();
	}

	// ---- per pixel ----

	private List<Column> inputColumns(ReactYearData year) {
		List<Column> columns = new ArrayList<>();
		for (String crop : year.crops()) {
			for (String level : ReactStartupCheck.CROP_LEVELS) {
				columns.add(floats("yield_" + crop + level, year.crop(crop, level)));
			}
		}
		for (String crop : year.irrigatedCrops()) {
			for (String level : ReactStartupCheck.WATER_DEMAND_LEVELS) {
				columns.add(floats("demand_" + crop + level, year.waterDemand(crop, level)));
			}
		}
		if (!year.irrigatedCrops().isEmpty()) {
			columns.add(floats("runoff", year.runoff()));
		}
		for (String pasture : year.pastures()) {
			columns.add(floats("npp_" + pasture, year.pasture(pasture)));
		}
		for (int rotation : year.forestryRotations()) {
			columns.add(floats("yield_forestry_" + rotation, year.forestryYield(rotation)));
		}
		for (String capital : year.capitals()) {
			columns.add(floats("capital_" + capital, year.capital(capital)));
		}
		if (irrigationCost != null) {
			columns.add(new Column("irrigation_cost_index", pixel -> String.valueOf(irrigationCost.at(pixel))));
		}
		return columns;
	}

	private static List<Column> coefficientColumns(CropSurfaces surfaces) {
		List<Column> columns = new ArrayList<>();
		for (String crop : surfaces.crops()) {
			CropSurfaces.Surface surface = surfaces.crop(crop);
			columns.add(doubles(crop + "_A", surface.a()));
			columns.add(doubles(crop + "_B", surface.b()));
			columns.add(doubles(crop + "_C", surface.c()));
			columns.add(doubles(crop + "_D", surface.d()));
			columns.add(doubles(crop + "_alpha", surface.alpha()));
			columns.add(doubles(crop + "_beta", surface.beta()));
		}
		for (String crop : surfaces.irrigatedCrops()) {
			CropSurfaces.WaterDemand waterDemand = surfaces.waterDemand(crop);
			columns.add(doubles(crop + "_D0", waterDemand.d0()));
			columns.add(doubles(crop + "_D1000", waterDemand.d1000()));
			columns.add(doubles(crop + "_alphaD", waterDemand.alphaD()));
		}
		return columns;
	}

	private static Column floats(String name, float[] values) {
		return new Column(name, pixel -> Float.toString(values[pixel]));
	}

	private static Column doubles(String name, double[] values) {
		return new Column(name, pixel -> Double.toString(values[pixel]));
	}

	private void writePixels(List<Path> written, String name, int year, List<Column> columns) {
		StringBuilder header = new StringBuilder("Lon,Lat");
		columns.forEach(column -> header.append(',').append(column.name()));
		write(written, name, year, header.toString(), grid.size(), pixel -> {
			StringBuilder row = new StringBuilder();
			row.append(grid.lon(pixel)).append(',').append(grid.lat(pixel));
			columns.forEach(column -> row.append(',').append(column.value().apply(pixel)));
			return row.toString();
		});
	}

	// ---- per decision unit ----

	private <M> void writeUnits(List<Path> written, String name, int year, List<M> afts, Function<M, String> label,
			Function<M, double[]> quantity) {
		writeUnitValues(written, name, year, afts, label, aft -> {
			double[] values = quantity.apply(aft);
			return unit -> String.valueOf(values[unit]);
		});
	}

	/** One file per quantity: one row per decision unit, one column per AFT, each value as {@code value} writes it. */
	private <M> void writeUnitValues(List<Path> written, String name, int year, List<M> afts, Function<M, String> label,
			Function<M, IntFunction<String>> value) {
		if (afts.isEmpty()) {
			return;
		}
		StringBuilder header = new StringBuilder("Lon,Lat,region");
		List<IntFunction<String>> columns = new ArrayList<>();
		for (M aft : afts) {
			header.append(',').append(label.apply(aft));
			columns.add(value.apply(aft));
		}
		write(written, name, year, header.toString(), units.size(), unit -> {
			int pixel = units.pixel(unit);
			StringBuilder row = new StringBuilder();
			row.append(grid.lon(pixel)).append(',').append(grid.lat(pixel)).append(',').append(units.region(unit));
			for (IntFunction<String> column : columns) {
				row.append(',').append(column.apply(unit));
			}
			return row.toString();
		});
	}

	private void writePrices(List<Path> written, int year, YearPrices prices) {
		List<String> rows = new ArrayList<>();
		for (String service : prices.services()) {
			double[] price = prices.price(service);
			Map<String, Double> byRegion = new LinkedHashMap<>();
			for (int unit = 0; unit < units.size(); unit++) {
				byRegion.putIfAbsent(units.region(unit), price[unit]);
			}
			byRegion.forEach((region, value) -> rows.add(service + "," + region + "," + value));
		}
		write(written, "Prices", year, "service,region,price", rows.size(), rows::get);
	}

	// ---- files ----

	private void write(List<Path> written, String name, int year, String header, int rows, IntFunction<String> row) {
		Path file = folder.resolve(scenario + "-React-" + name + "-" + year + ".csv");
		try {
			Files.createDirectories(folder);
			try (BufferedWriter out = Files.newBufferedWriter(file)) {
				out.write(header);
				out.write('\n');
				for (int r = 0; r < rows; r++) {
					out.write(row.apply(r));
					out.write('\n');
				}
			}
		} catch (IOException e) {
			throw new ReactInputException("CRAFTY-react could not write " + file + ": " + e.getMessage(), e);
		}
		written.add(file);
	}
}
