package de.cesr.crafty.react.science;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.react.data.LpjGrid;
import de.cesr.crafty.react.data.ReactConfig;
import de.cesr.crafty.react.data.ReactElement;
import de.cesr.crafty.react.data.ReactInputException;
import de.cesr.crafty.react.data.ReactInputs;
import de.cesr.crafty.react.data.ReactToyData;
import de.cesr.crafty.react.data.ReactYearData;

/**
 * The fitting itself is checked against R in {@link YieldResponseTest} and {@link IrrigationTest}. These
 * tests check that {@link CropSurfaces} feeds each fit the right columns for the right pixel.
 */
class CropSurfacesTest {

	private static final String CROP = "CerealsC3";

	@TempDir
	Path dir;

	private ReactYearData year;
	private CropSurfaces surfaces;
	private List<Integer> pixels;

	@BeforeEach
	void setUp() {
		ReactToyData.project(dir);
		ReactInputs inputs = ReactInputs.create(ReactConfig.defaults(), ReactToyData.context(dir).build());
		year = inputs.forYear(2020);
		surfaces = CropSurfaces.fit(year);
		LpjGrid grid = inputs.checked().cellKey().grid();
		pixels = List.of(grid.indexOf(-91.25, 17.75), grid.indexOf(-121.75, 37.25));
	}

	private int pixelB() {
		return pixels.get(1);
	}

	/** A loaded value, widened as {@link CropSurfaces} widens it. */
	private double y(String level, int p) {
		return year.crop(CROP, level)[p];
	}

	private double waterDemandColumn(String level, int p) {
		return year.waterDemand(CROP, level)[p];
	}

	@Test
	void coefficientsMatchTheFunctionsPixelByPixel() {
		CropSurfaces.Surface s = surfaces.crop(CROP);
		for (int p : pixels) {
			assertEquals(YieldResponse.a(y("0", p)), s.a()[p], 0);
			assertEquals(YieldResponse.b(y("0", p), y("1000", p)), s.b()[p], 0);
			assertEquals(YieldResponse.c(y("0", p), y("i0", p)), s.c()[p], 0);
			assertEquals(YieldResponse.d(y("0", p), y("1000", p), y("i0", p), y("i1000", p)), s.d()[p], 0);
			assertEquals(YieldResponse.alpha(y("0", p), y("0200", p), y("1000", p)), s.alpha()[p], 0);
			assertEquals(YieldResponse.beta(y("0", p), y("i0", p)), s.beta()[p], 0);
		}
	}

	@Test
	void waterDemandCurveMatchesTheFunctionsPixelByPixel() {
		CropSurfaces.WaterDemand waterDemand = surfaces.waterDemand(CROP);
		for (int p : pixels) {
			double d0 = waterDemandColumn("i0", p);
			double d1000 = waterDemandColumn("i1000", p);
			double alphaD = Irrigation.waterDemandAlpha(d0, waterDemandColumn("i0200", p), d1000);
			assertEquals(d0, waterDemand.d0()[p], 0);
			assertEquals(d1000, waterDemand.d1000()[p], 0);
			assertEquals(alphaD, waterDemand.alphaD()[p], 0);
			for (double n : new double[] { 0, 150, 1200 }) {
				assertEquals(Irrigation.waterDemand(d0, d1000, alphaD, n), waterDemand.waterDemand(p, n), 0,
						"N = " + n);
			}
		}
	}

	@Test
	void surfaceYieldIsYieldResponseAtThatPixel() {
		CropSurfaces.Surface s = surfaces.crop(CROP);
		for (int p : pixels) {
			assertEquals(YieldResponse.yield(s.a()[p], s.b()[p], s.c()[p], s.d()[p], s.alpha()[p], s.beta()[p], 180, 0.6,
					0.75, 0.01, 3), s.yield(p, 180, 0.6, 0.75, 0.01, 3), 0);
		}
	}

	@Test
	void aHandWorkedPixel() {
		// Pixel B in t/ha: y0 1.35, y0200 0.84, y1000 0.77, yi0 4.81, yi1000 18.25. The 1000 kg N yield is
		// below the 0 N yield, so b is clamped to 0 while alpha is still fitted from the ratio 0.51 / 0.58.
		CropSurfaces.Surface s = surfaces.crop(CROP);
		int p = pixelB();
		assertEquals(1.35, s.a()[p], 1e-4);
		assertEquals(0, s.b()[p], 0);
		assertEquals(3.46, s.c()[p], 1e-4);
		assertEquals(14.02, s.d()[p], 1e-4, "18.25 + 1.35 - 0.77 - 4.81");
		assertEquals(-Math.log(1 - 0.51 / 0.58) * 5, s.alpha()[p], 1e-4, "about 10.57");
		assertEquals(3.2189, s.beta()[p], 1e-4);
	}

	@Test
	void cropsAndIrrigatedCropsFollowTheYear() {
		assertEquals(year.crops(), surfaces.crops());
		assertEquals(year.irrigatedCrops(), surfaces.irrigatedCrops());
		assertEquals(2020, surfaces.year());
	}

	@Test
	void aCropNoAftIrrigatesHasASurfaceButNoWaterDemandCurve() {
		// IntC3C_irrig made rainfed, so it may no longer have an irrigation efficiency.
		String[] rows = ReactToyData.STANDARD_ROWS.clone();
		rows[0] = "IntC3C_irrig,AFT,1,C3cereals,Prospect,,0,,react_GDP_100,0.2,,";
		ReactToyData.parameters(dir, rows);
		ReactInputs rainfed = ReactInputs.create(ReactConfig.defaults(),
				ReactToyData.context(dir).aft("IntC3C_irrig", 200, 0.75, false, false).build());
		CropSurfaces fitted = CropSurfaces.fit(rainfed.forYear(2020));

		assertEquals(Set.of(CROP), fitted.crops());
		assertTrue(fitted.irrigatedCrops().isEmpty());
		assertThrows(ReactInputException.class, () -> fitted.waterDemand(CROP));
	}

	@Test
	void anUnknownCropIsAnError() {
		assertThrows(ReactInputException.class, () -> surfaces.crop("CerealsC4"));
		assertThrows(ReactInputException.class, () -> surfaces.waterDemand("CerealsC4"));
	}

	@Test
	void anEmptyYearGivesEmptySurfaces() {
		ReactInputs nothingReactive = ReactInputs.create(ReactConfig.defaults(),
				ReactToyData.context(dir).off(ReactElement.values()).build());
		CropSurfaces fitted = CropSurfaces.fit(nothingReactive.forYear(2020));

		assertTrue(fitted.crops().isEmpty());
		assertTrue(fitted.irrigatedCrops().isEmpty());
	}

	@Test
	void noCoefficientIsNaNOrInfinite() {
		CropSurfaces.Surface s = surfaces.crop(CROP);
		CropSurfaces.WaterDemand waterDemand = surfaces.waterDemand(CROP);
		for (double[] values : List.of(s.a(), s.b(), s.c(), s.d(), s.alpha(), s.beta(), waterDemand.d0(),
				waterDemand.d1000(), waterDemand.alphaD())) {
			for (double value : values) {
				assertTrue(Double.isFinite(value), "" + value);
			}
		}
	}
}
