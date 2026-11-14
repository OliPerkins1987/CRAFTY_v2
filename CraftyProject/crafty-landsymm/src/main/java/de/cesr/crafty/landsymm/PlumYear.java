package de.cesr.crafty.landsymm;

/**
 * The PLUM rows for the current CRAFTY year: the calibration's final year in the start year, PLUM's year t - 1
 * after it. PlumInputStep sets them first thing in the year; TradeCapitalStep and ConsumptionStep read them later in
 * the same year, so each year's files are read once.
 */
final class PlumYear {

	private int craftyYear = Integer.MIN_VALUE;
	private PlumYearOutputs rows;

	/** The rows for a CRAFTY year. */
	void set(int craftyYear, PlumYearOutputs rows) {
		this.craftyYear = craftyYear;
		this.rows = rows;
	}

	/** The rows for a CRAFTY year. Stops if PlumInputStep hasn't set them for that year. */
	PlumYearOutputs rows(int craftyYear) {
		if (rows == null || this.craftyYear != craftyYear) {
			throw new CouplingInputException("No PLUM rows for CRAFTY's " + craftyYear
					+ ": PlumInputStep, first in the year, should have read them");
		}
		return rows;
	}
}
