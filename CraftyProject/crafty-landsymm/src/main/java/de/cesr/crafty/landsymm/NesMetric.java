package de.cesr.crafty.landsymm;

import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.crafty.Cell;

/**
 * A non-economic service's metric: how a region's spend is spread over its cells (50a PLAN section 1). Counted land
 * is weighted by it, and the NES step writes it into the service's allocation capital on every cell each year, so an
 * AFT's production (and so its pay) follows it too.
 *
 * Only {@link #AREA} exists so far. BII or MSA, from a capital or from CRAFTY's own land use, would be further
 * implementations (50a PLAN section 3.7).
 */
interface NesMetric {

	/** The metric on a cell. */
	double on(Cell c);

	/** "area": 1 on every cell, so counted land is in ha and the price is per ha. */
	NesMetric AREA = c -> 1.0;

	/** The metric for a config value, one of ConfigLoader.NES_METRICS. */
	static NesMetric of(String name) {
		if ("area".equals(name)) {
			return AREA;
		}
		throw new CouplingInputException("biodiversity_metric must be one of " + ConfigLoader.NES_METRICS + ", got: "
				+ name);
	}
}
