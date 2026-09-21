package de.cesr.crafty.react.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReactYearDataTest {

	@TempDir
	Path dir;

	private final ReactConfig config = ReactConfig.defaults();

	@BeforeEach
	void setUp() {
		ReactToyData.project(dir);
	}

	private ReactYearData load(ReactToyData.Context context, int year) {
		ReactRunContext built = context.build();
		return ReactYearData.load(config, built, ReactStartupCheck.run(config, built), year);
	}

	private int pixelA(ReactToyData.Context context) {
		return ReactStartupCheck.run(config, context.build()).cellKey().grid().indexOf(-91.25, 17.75);
	}

	@Test
	void cropYieldsAreLoadedPerCropAndLevelInTonnesPerHectare() {
		ReactYearData year = load(ReactToyData.context(dir), 2020);
		int a = pixelA(ReactToyData.context(dir));

		assertEquals(Set.of("CerealsC3"), year.crops(), "IntC3C_irrig and ExtC3C share one crop, held once");
		assertEquals(1.82, year.crop("CerealsC3", "0")[a], 1e-5, "0.182 kg/m2 = 1.82 t/ha");
		assertEquals(5.06, year.crop("CerealsC3", "0200")[a], 1e-5);
		assertEquals(8.51, year.crop("CerealsC3", "1000")[a], 1e-5);
		assertEquals(2.14, year.crop("CerealsC3", "i0")[a], 1e-5);
		assertEquals(4.95, year.crop("CerealsC3", "i0200")[a], 1e-5);
		assertEquals(10.36, year.crop("CerealsC3", "i1000")[a], 1e-5);
	}

	@Test
	void pastureIrrigationAndCapitalsAreLoadedInRealUnits() {
		ReactYearData year = load(ReactToyData.context(dir), 2020);
		int a = pixelA(ReactToyData.context(dir));

		assertEquals(12.32, year.pasture("Pasture_sum")[a], 1e-4, "1.232 kg/m2 = 12.32 t/ha");
		assertEquals(139.11, year.demand("CerealsC3", "i0")[a], 1e-2, "13.911 mm = 139.11 m3/ha");
		assertEquals(606.51, year.demand("CerealsC3", "i1000")[a], 1e-2);
		assertEquals(7199.0, year.runoff()[a], 1e-1, "719.9 mm = 7199 m3/ha");
		assertEquals(0.893, year.capital("react_GDP_50")[a], 1e-4, "Capitals are read as written");
		assertEquals(Set.of("react_GDP_100", "react_GDP_50"), year.capitals());
	}

	@Test
	void irrigationDataIsLoadedEvenWhenIrrigationIsNotReactive() {
		// The switch decides whether react changes the water applied, not whether the AFT irrigates:
		// water applied is still min(runoff, demand), which sets the irrigation level in the yield.
		ReactYearData year = load(ReactToyData.context(dir).off(ReactElement.IRRIGATION), 2020);

		assertEquals(Set.of("CerealsC3"), year.irrigatedCrops());
		assertTrue(year.runoff().length > 0);
	}

	@Test
	void nothingIsLoadedForAnElementWithNoReactiveAft() {
		// Only the capitals of switched-on elements are read.
		ReactYearData year = load(ReactToyData.context(dir).off(ReactElement.OTHER_INTENSITY), 2020);

		assertEquals(Set.of("react_GDP_50"), year.capitals(), "react_GDP_100 is only read for other intensity");
		assertThrows(ReactInputException.class, () -> year.capital("react_GDP_100"));
	}

	@Test
	void anEmptyYearHoldsNothing() {
		ReactYearData year = ReactYearData.empty(2020);

		assertEquals(2020, year.year());
		assertTrue(year.crops().isEmpty());
		assertThrows(ReactInputException.class, year::runoff);
	}

	@Test
	void aBadValueForAKeptPixelNamesTheFileAndLine() {
		ReactToyData.write(dir, "worlds/react/suitabilities/ssp126/crops/Suit_Agri_Crops_2020.csv",
				"Lon,Lat,CerealsC30,CerealsC30200,CerealsC31000,CerealsC3i0,CerealsC3i0200,CerealsC3i1000",
				ReactToyData.PIXEL_A + ",0.182,0.506,0.851,0.214,0.495,1.036",
				ReactToyData.PIXEL_B + ",NA,0.084,0.077,0.481,0.72,1.825");

		ReactInputException e = assertThrows(ReactInputException.class, () -> load(ReactToyData.context(dir), 2020));

		assertTrue(e.getMessage().contains("Suit_Agri_Crops_2020.csv") && e.getMessage().contains("line 3"),
				e.getMessage());
	}

	@Test
	void aMissingPixelNamesTheFile() {
		ReactToyData.write(dir, "worlds/react/suitabilities/ssp126/pasture/Suit_Agri_pastoral_2020.csv",
				"Lon,Lat,Pasture_sum", ReactToyData.PIXEL_A + ",1.232");

		ReactInputException e = assertThrows(ReactInputException.class, () -> load(ReactToyData.context(dir), 2020));

		assertTrue(e.getMessage().contains("Suit_Agri_pastoral_2020.csv") && e.getMessage().contains("1 pixel(s)"),
				e.getMessage());
	}

	@Test
	void anUnknownCropOrLevelIsAnError() {
		ReactYearData year = load(ReactToyData.context(dir), 2020);

		assertThrows(ReactInputException.class, () -> year.crop("CerealsC4", "0"));
		assertThrows(ReactInputException.class, () -> year.crop("CerealsC3", "0060"));
	}
}
