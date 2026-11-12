package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.cesr.crafty.landsymm.PriceScaling.Basis;

/**
 * The PLUM reader and the price bases on a whole PLUM output folder, such as all1990's. Skipped unless the folder
 * is given: {@code -Dlandsymm.plum.output=C:/.../PLUM/output/all1990}. It reads the folder's last two calibration
 * years, 2029 and 2030.
 */
class FullPlumOutputsCheckTest {

	private static final String PROPERTY = "landsymm.plum.output";

	@Test
	void all1990sOutputsReadAndDomesticMatchesPlumsOwnCountryPrices() {
		String folder = System.getProperty(PROPERTY);
		assumeTrue(folder != null && !folder.isBlank(), "-D" + PROPERTY + " not given");
		Path plum = Paths.get(folder);

		PlumYearOutputs y2029 = PlumYearOutputs.read(plum, 2029);
		PlumYearOutputs y2030 = PlumYearOutputs.read(plum, 2030);
		assertEquals(158, y2030.countries().size());
		assertEquals(0.1009, y2030.price("wheat"), 0.00005);

		// PLUM's Export_price in year t is the world price written in year t - 1
		for (String country : y2030.countries()) {
			assertEquals(y2029.price("wheat"), y2030.trade(country, "wheat").exportPrice(), 0.00005, country);
		}

		// The coupler's domestic price from 2029 is PLUM's own country price in 2030, within the rounding of
		// domestic.txt's 4 decimals
		List<String> worst = new ArrayList<>();
		double[] maxRelative = { 0 };
		int[] compared = { 0 };
		CsvReader.forEachRow(plum.resolve(PlumYearOutputs.DOMESTIC_FILE),
				List.of("Year", "Country", "Crop", "Consumer_price"), (line, v) -> {
					if (!v[0].equals("2030") || !PlumCommodities.PRICE_NAMES.contains(v[2])
							|| v[2].equals(PlumCommodities.ENERGY_CROPS)) {
						return;
					}
					double plumPrice = Double.parseDouble(v[3]);
					double coupler = PriceScaling.plumPrice(Basis.DOMESTIC, y2029, v[1], v[2]);
					double relative = Math.abs(coupler - plumPrice) / plumPrice;
					if (relative > maxRelative[0]) {
						maxRelative[0] = relative;
						worst.add(0, v[1] + ", " + v[2] + ": coupler " + coupler + ", PLUM " + plumPrice);
					}
					compared[0]++;
				});
		assertEquals(158 * 12, compared[0], "every country x 9 crops, ruminants, monogastrics, fish");
		assertTrue(maxRelative[0] < 0.01, "worst: " + (worst.isEmpty() ? "" : worst.get(0)));
		System.out.println("Domestic vs PLUM's Consumer_price, 2030: " + compared[0] + " compared, largest difference "
				+ String.format("%.3f%%", 100 * maxRelative[0]) + (worst.isEmpty() ? "" : " (" + worst.get(0) + ")"));
	}
}
