package de.cesr.crafty.landsymm;

/**
 * How a region's trade capital follows PLUM each year (48d PLAN section 5). TradeCapitalStep gives it the region's
 * figures for the capital's crop and writes the value it returns over every cell of the region.
 */
interface TradeCapitalRule {

	/**
	 * What the rule sees for one region and trade capital.
	 *
	 * @param production  PLUM's production of the crop (Mt)
	 * @param domesticUse PLUM's domestic use of it, Production + Net_imports (Mt)
	 * @param lastValue   the region's value of the capital last year (the file value in the start year)
	 * @param fileValue   this year's value in the capitals file (the median over the region's cells)
	 */
	record Inputs(String region, String country, String capital, String crop, double production, double domesticUse,
			double lastValue, double fileValue) {

		/**
		 * Production / domestic use: infinite when the region produces but uses none (everything exported), 0 when it
		 * produces none.
		 */
		double selfSufficiency() {
			if (production <= 0) {
				return 0;
			}
			if (domesticUse <= 0) {
				return Double.POSITIVE_INFINITY;
			}
			return production / domesticUse;
		}
	}

	/** The region's new value of the capital, from 0 to 1. */
	double next(Inputs in);
}
