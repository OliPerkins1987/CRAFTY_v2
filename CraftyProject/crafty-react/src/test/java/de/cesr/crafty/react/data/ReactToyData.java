package de.cesr.crafty.react.data;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.cesr.crafty.react.data.ReactRunContext.AftBaseline;

/**
 * Tiny react input files for tests, written into a test's temporary folder. The pixels and values
 * mirror the sandbox project's, so failures are easy to relate to real data.
 */
final class ReactToyData {

	/** Two pixels from the sandbox, and one pixel with no CRAFTY cells. */
	static final String PIXEL_A = "-91.25,17.75";
	static final String PIXEL_B = "-121.75,37.25";
	static final String PIXEL_NO_CELLS = "5.25,52.25";

	/** The years of the toy project. */
	static final int FIRST_YEAR = 2020;
	static final int LAST_YEAR = 2021;

	/** The parameters sheet header, in the 27b layout (Type is kept, and ignored). */
	static final String PARAMETERS_HEADER = "Label,Type,react_isReactive,react_service,react_N_type,react_N_capital,"
			+ "react_N_par,react_I_eff,react_O_capital,react_O_par,react_S_par,react_R_par";

	/**
	 * One AFT of each kind: an irrigated Prospect crop, an extensive Capital crop whose other intensity
	 * doesn't react, a pasture AFT, a non-reactive crop, a non-reactive forest AFT that still names its
	 * service, and a mask.
	 */
	static final String[] STANDARD_ROWS = {
			"IntC3C_irrig,AFT,1,C3cereals,Prospect,,0,0.9,react_GDP_100,0.2,,",
			"ExtC3C,AFT,1,C3cereals,Capital,react_GDP_50,12,,,,,",
			"IntP,AFT,1,Pasture,,,,,react_GDP_50,1,0,",
			"IntFodder,AFT,0,,,,,,,,,",
			"AF,AFT,0,Hardwood,,,,,,,,0.1",
			"Urban,Mask,0,,,,,,,,," };

	private ReactToyData() {
	}

	/** Writes lines to a file under {@code dir}, creating folders as needed. */
	static Path write(Path dir, String relativePath, String... lines) {
		Path file = dir.resolve(relativePath);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, String.join("\n", lines) + "\n");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return file;
	}

	/** A cell key: cells (1,1) and (1,2) in pixel A, cell (2,1) in pixel B. */
	static Path cellKey(Path dir) {
		return write(dir, "worlds/react/cell_key.csv",
				"ID,X,Y,LPJ_cell_x,LPJ_cell_y",
				"100,1,1," + PIXEL_A,
				"101,1,2," + PIXEL_A,
				"102,2,1," + PIXEL_B);
	}

	/** Services.csv as in the sandbox, cut down. */
	static Path services(Path dir) {
		return write(dir, "csv/Services.csv",
				"Name,LPJG_name,LPJG_type,Description",
				"C3cereals,CerealsC3,crops,C3 cereal crops (Wheat; barley; rye) for food",
				"Pasture,Pasture_sum,pasture,Pasture",
				"Hardwood,,forestry,Hardwood (broadleaf) timber",
				"Carbon,,,Quantity of carbon sequestered (above & below ground)");
	}

	/** global_costs.csv as in the sandbox, cut down. */
	static Path globalCosts(Path dir) {
		return write(dir, "costs/global/global_costs.csv",
				"Item,Cost,Notes",
				"Nfert,1.08,PLUM value in 2017US$ kg-1",
				"Water,0.5,Default PLUM value in 2017 US$",
				"Stocking,500,",
				"C3cereals,50,",
				"Pasture,50,",
				"Carbon,0,");
	}

	/** The parameters sheet, with {@link #PARAMETERS_HEADER} and the given rows. */
	static Path parameters(Path dir, String... rows) {
		String[] lines = new String[rows.length + 1];
		lines[0] = PARAMETERS_HEADER;
		System.arraycopy(rows, 0, lines, 1, rows.length);
		return write(dir, "AFTs/react/Reactive_parameters.csv", lines);
	}

	/** A complete small project for 2020–2021, with everything the startup checks read. */
	static void project(Path dir) {
		cellKey(dir);
		services(dir);
		globalCosts(dir);
		parameters(dir, STANDARD_ROWS);
		for (int year = FIRST_YEAR; year <= LAST_YEAR; year++) {
			write(dir, "worlds/react/suitabilities/ssp126/crops/Suit_Agri_Crops_" + year + ".csv",
					"Lon,Lat,CerealsC30,CerealsC30060,CerealsC30200,CerealsC31000,CerealsC3i0,CerealsC3i0060,CerealsC3i0200,CerealsC3i1000",
					PIXEL_A + ",0.182,0.3,0.506,0.851,0.214,0.3,0.495,1.036",
					PIXEL_B + ",0.135,0.1,0.084,0.077,0.481,0.5,0.72,1.825");
			write(dir, "worlds/react/suitabilities/ssp126/pasture/Suit_Agri_pastoral_" + year + ".csv",
					"Lon,Lat,Pasture_sum", PIXEL_A + ",1.232", PIXEL_B + ",0.336");
			write(dir, "worlds/react/capitals/ssp126/EU_capitals_ssp126_" + year + ".csv",
					"Lon,Lat,react_GDP_50,react_GDP_100", PIXEL_A + ",0.893,0.447", PIXEL_B + ",0.696,0.348");
			write(dir, "worlds/react/irrigation/ssp126/Irrigation_demand_" + year + ".csv",
					"\"Lon\",\"Lat\",\"CerealsC3i0\",\"CerealsC3i0060\",\"CerealsC3i0200\",\"CerealsC3i1000\"",
					PIXEL_A + ",13.911,20,41.879,60.651", PIXEL_B + ",364.677,400,461.521,551.665");
			write(dir, "worlds/react/irrigation/ssp126/Runoff_" + year + ".csv",
					"\"Lon\",\"Lat\",\"Total\"", PIXEL_A + ",719.9", PIXEL_B + ",109.6");
		}
		write(dir, "worlds/react/irrigation/Irrigation_cost.csv",
				"\"Lon\",\"Lat\",\"irrigation_cost\"", PIXEL_A + ",0.49", PIXEL_B + ",0.79");
	}

	/** A context matching {@link #project}; change it with the builder's methods. */
	static Context context(Path dir) {
		return new Context(dir);
	}

	/**
	 * Builds a {@link ReactRunContext} for the toy project: the AFTs of {@link #STANDARD_ROWS} with
	 * sensible baselines, every element reactive, and every core cost file found for every year.
	 */
	static final class Context {
		private final Path project;
		private final Map<String, AftBaseline> afts = new LinkedHashMap<>();
		private final Set<String> services = new LinkedHashSet<>(List.of("C3cereals", "Pasture", "Hardwood", "Carbon"));
		private final Set<String> cells = new LinkedHashSet<>(List.of("1,1", "1,2", "2,1"));
		private final Set<ReactElement> reactive = EnumSet.allOf(ReactElement.class);
		private final Map<Integer, Set<ReactElement>> costFiles = new LinkedHashMap<>();

		private Context(Path project) {
			this.project = project;
			aft("IntC3C_irrig", 200, 0.75, true, false);
			aft("ExtC3C", 100, 0.4, false, false);
			aft("IntP", 0, 1.5, false, true);
			aft("IntFodder", 200, 0.75, false, false);
			aft("AF", 0, 1.0, false, false);
			aft("Urban", 0, 1.0, false, false);
			for (int year = FIRST_YEAR; year <= LAST_YEAR; year++) {
				costFiles.put(year, EnumSet.allOf(ReactElement.class));
			}
		}

		/** Adds or replaces an AFT's baselines. */
		Context aft(String label, double nfertRate, double otherIntensity, boolean irrigated, boolean producesPasture) {
			afts.put(label, new AftBaseline(nfertRate, otherIntensity, irrigated, producesPasture));
			return this;
		}

		Context withoutAft(String label) {
			afts.remove(label);
			return this;
		}

		Context withoutService(String service) {
			services.remove(service);
			return this;
		}

		Context cell(String cellId) {
			cells.add(cellId);
			return this;
		}

		/** Switches elements off. */
		Context off(ReactElement... elements) {
			for (ReactElement element : elements) {
				reactive.remove(element);
			}
			return this;
		}

		/** Pretends core found no cost file for an element in one year. */
		Context noCostFile(int year, ReactElement element) {
			costFiles.get(year).remove(element);
			return this;
		}

		ReactRunContext build() {
			Map<Integer, Set<ReactElement>> costFilesCopy = new LinkedHashMap<>();
			costFiles.forEach((year, elements) -> costFilesCopy.put(year, EnumSet.copyOf(elements)));
			return new ReactRunContext(project, "ssp126", FIRST_YEAR, LAST_YEAR, project.resolve("csv/Services.csv"),
					new LinkedHashMap<>(afts), new LinkedHashSet<>(services), new LinkedHashSet<>(cells),
					reactive.isEmpty() ? EnumSet.noneOf(ReactElement.class) : EnumSet.copyOf(reactive), costFilesCopy);
		}
	}
}
