package de.cesr.crafty.landsymm;

import de.cesr.crafty.core.updaters.Timestep;

/**
 * The years a coupled run's CRAFTY covers. They are set on Timestep before anything loads, so CRAFTY's loaders and
 * outputs size themselves to them, as if the scenario ended there: the per-year output tables, the final-year map,
 * and the land-use event counter's last row.
 * <ul>
 * <li>A calibration run covers the start year only (48c Q1 a): it only builds the starting state.</li>
 * <li>A scenario run ends at coupled_end_year if that is set, otherwise at the scenario's end.</li>
 * </ul>
 */
final class RunYears {

	private RunYears() {
	}

	/** The run's last year. */
	static int endYear(boolean calibrationRun, int coupledEndYear, int startYear, int scenarioEndYear) {
		if (calibrationRun) {
			return startYear;
		}
		return coupledEndYear > 0 ? coupledEndYear : scenarioEndYear;
	}

	/** Sets Timestep's end year and its number of years. */
	static void apply(int startYear, int endYear) {
		Timestep.setEndtYear(endYear);
		Timestep.setSize(endYear - startYear + 1);
	}
}
