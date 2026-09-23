package de.cesr.crafty.react.science;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import de.cesr.crafty.react.data.ReactInputException;
import de.cesr.crafty.react.data.ReactYearData;

/**
 * One year's fitted yield surfaces and irrigation demand curves, for every crop and pixel.
 *
 * The coefficients are fitted once a year, when the year's LPJ-GUESS data is loaded, rather than inside
 * the decision loop, where they would be refitted at every step (D3 in the master plan). The fitting
 * itself is done by {@link YieldResponse} and {@link Irrigation}; this class only runs it over every pixel
 * and keeps the results.
 *
 * Arrays are indexed by pixel, in the same order as every {@link ReactYearData} array. The loaded values
 * are {@code float}; each is widened to {@code double} before it is fitted, and the results are held as
 * {@code double}.
 *
 * All six yield coefficients are fitted for every crop loaded, irrigated or not: every reactive crop's six
 * yield levels are loaded, and a rainfed AFT simply uses irrigation level 0, where {@code c} and
 * {@code d} drop out. Water demand curves are fitted only for the crops whose irrigation demand was loaded.
 *
 * Nothing changes after {@link #fit}, so it can be read from several threads. The arrays are handed out
 * as they are held: callers must not change them.
 */
public final class CropSurfaces {

	/** One crop's yield coefficients, one value per pixel. */
	public record Surface(double[] a, double[] b, double[] c, double[] d, double[] alpha, double[] beta) {

		/**
		 * {@link YieldResponse#yield} with this pixel's coefficients, t/ha.
		 *
		 * @see YieldResponse#yield for what each argument means
		 */
		public double yield(int pixel, double nitrogen, double irrigationLevel, double otherIntensity,
				double techChange, double yearsSinceStart) {
			return YieldResponse.yield(a[pixel], b[pixel], c[pixel], d[pixel], alpha[pixel], beta[pixel], nitrogen,
					irrigationLevel, otherIntensity, techChange, yearsSinceStart);
		}
	}

	/** One crop's irrigation demand curve, one value per pixel, m³/ha. */
	public record WaterDemand(double[] d0, double[] d1000, double[] alphaD) {

		/** {@link Irrigation#waterDemand} with this pixel's curve: water the crop needs at this N, m³/ha. */
		public double waterDemand(int pixel, double nitrogen) {
			return Irrigation.waterDemand(d0[pixel], d1000[pixel], alphaD[pixel], nitrogen);
		}
	}

	private final int year;
	private final Map<String, Surface> surfaces;
	private final Map<String, WaterDemand> waterDemands;

	private CropSurfaces(int year, Map<String, Surface> surfaces, Map<String, WaterDemand> waterDemands) {
		this.year = year;
		this.surfaces = surfaces;
		this.waterDemands = waterDemands;
	}

	/** Fits every crop's surface, and every irrigated crop's water demand curve, for one year. */
	public static CropSurfaces fit(ReactYearData year) {
		Map<String, Surface> surfaces = new LinkedHashMap<>();
		for (String crop : year.crops()) {
			surfaces.put(crop, fitSurface(year, crop));
		}
		Map<String, WaterDemand> waterDemands = new LinkedHashMap<>();
		for (String crop : year.irrigatedCrops()) {
			waterDemands.put(crop, fitWaterDemand(year, crop));
		}
		return new CropSurfaces(year.year(), Collections.unmodifiableMap(surfaces),
				Collections.unmodifiableMap(waterDemands));
	}

	private static Surface fitSurface(ReactYearData year, String crop) {
		float[] y0 = year.crop(crop, "0");
		float[] y0200 = year.crop(crop, "0200");
		float[] y1000 = year.crop(crop, "1000");
		float[] yi0 = year.crop(crop, "i0");
		float[] yi1000 = year.crop(crop, "i1000");

		int pixels = y0.length;
		double[] a = new double[pixels];
		double[] b = new double[pixels];
		double[] c = new double[pixels];
		double[] d = new double[pixels];
		double[] alpha = new double[pixels];
		double[] beta = new double[pixels];
		for (int p = 0; p < pixels; p++) {
			a[p] = YieldResponse.a(y0[p]);
			b[p] = YieldResponse.b(y0[p], y1000[p]);
			c[p] = YieldResponse.c(y0[p], yi0[p]);
			d[p] = YieldResponse.d(y0[p], y1000[p], yi0[p], yi1000[p]);
			alpha[p] = YieldResponse.alpha(y0[p], y0200[p], y1000[p]);
			beta[p] = YieldResponse.beta(y0[p], yi0[p]);
		}
		return new Surface(a, b, c, d, alpha, beta);
	}

	private static WaterDemand fitWaterDemand(ReactYearData year, String crop) {
		float[] i0 = year.waterDemand(crop, "i0");
		float[] i0200 = year.waterDemand(crop, "i0200");
		float[] i1000 = year.waterDemand(crop, "i1000");

		int pixels = i0.length;
		double[] d0 = new double[pixels];
		double[] d1000 = new double[pixels];
		double[] alphaD = new double[pixels];
		for (int p = 0; p < pixels; p++) {
			d0[p] = i0[p];
			d1000[p] = i1000[p];
			alphaD[p] = Irrigation.waterDemandAlpha(i0[p], i0200[p], i1000[p]);
		}
		return new WaterDemand(d0, d1000, alphaD);
	}

	public int year() {
		return year;
	}

	/** The crops fitted, by LPJ-GUESS name: the same as the year's {@link ReactYearData#crops()}. */
	public Set<String> crops() {
		return surfaces.keySet();
	}

	/** The crops with a water demand curve: the same as the year's {@link ReactYearData#irrigatedCrops()}. */
	public Set<String> irrigatedCrops() {
		return waterDemands.keySet();
	}

	/** A crop's yield coefficients, by LPJ-GUESS name. */
	public Surface crop(String lpjgName) {
		Surface surface = surfaces.get(lpjgName);
		if (surface == null) {
			throw new ReactInputException("Year " + year + " holds no yield surface for " + lpjgName);
		}
		return surface;
	}

	/** A crop's irrigation demand curve, by LPJ-GUESS name. Only crops a reactive AFT irrigates have one. */
	public WaterDemand waterDemand(String lpjgName) {
		WaterDemand waterDemand = waterDemands.get(lpjgName);
		if (waterDemand == null) {
			throw new ReactInputException("Year " + year + " holds no irrigation demand curve for " + lpjgName);
		}
		return waterDemand;
	}
}
