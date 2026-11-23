package de.cesr.crafty.landsymm;

/**
 * How a region's price of a non-economic service is set each year (50a PLAN section 1). The NES step gives it the
 * region's figures and sets the price it returns as the region's weight of the service for the year.
 *
 * Today's rule is {@link #BUDGET_PER_AREA}. A policy or institutional agent that sets the price itself would be
 * another implementation; {@link Inputs#lastPrice()} is there for rules with memory (50a PLAN section 3.7).
 */
interface NesPriceRule {

	/**
	 * What the rule sees for one region and year.
	 *
	 * @param budget          the spend, $ (the weight file's million US$ x 1e6)
	 * @param countedArea     A = Σ area x the owner's counted fraction, ha
	 * @param metricArea      D = Σ area x the owner's counted fraction x the metric
	 * @param meanPixelMetric M = the mean over the region's cells of area x the metric
	 * @param lastPrice       the region's price last year; NaN in the first year
	 */
	record Inputs(String service, String region, int year, double budget, double countedArea, double metricArea,
			double meanPixelMetric, double lastPrice) {

		/** Whether the floor sets the price: less counted land than one average pixel (D < M). */
		boolean belowFloor() {
			return metricArea < meanPixelMetric;
		}
	}

	/** The region's price for the year, $ per ha of metric. */
	double price(Inputs in);

	/**
	 * B / max(D, M), and 0 when there's no spend. A region with no counted land is priced as if its spend were on one
	 * average pixel, and the floor keeps a sliver of counted land from pricing above that (50a Q1).
	 */
	NesPriceRule BUDGET_PER_AREA = in -> in.budget() <= 0 ? 0
			: in.budget() / Math.max(in.metricArea(), in.meanPixelMetric());
}
