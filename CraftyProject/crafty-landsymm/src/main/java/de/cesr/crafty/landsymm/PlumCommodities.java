package de.cesr.crafty.landsymm;

import java.util.List;

/**
 * PLUM's commodity names, as the coupler exchanges them. They mirror the GAMS names in PLUM's {@code CropType}
 * (plus {@code wood}); if PLUM adds or renames a commodity, these lists must follow.
 */
public final class PlumCommodities {

	public static final String ENERGY_CROPS = "energycrops";
	public static final String PASTURE = "pasture";
	public static final String RUMINANTS = "ruminants";
	public static final String WOOD = "wood";

	/**
	 * The commodities CRAFTY sends PLUM in production.csv, in PLUM's order: every crop PLUM takes from CRAFTY, then
	 * pasture (Mt dry matter) and wood (million m3). Livestock and set-aside are never sent.
	 */
	public static final List<String> PRODUCTION = List.of("wheat", "maize", "rice", "oilcropsNFix", "oilcropsOther",
			"pulses", "starchyRoots", "fruitveg", "sugar", ENERGY_CROPS, PASTURE, WOOD);

	/**
	 * The names a CRAFTY service can take its price from: PLUM's traded crops, livestock and fish (rows of PLUM's
	 * prices.txt and domestic.txt), and wood (prices.txt and wood.txt). Pasture has no PLUM price of its own; a
	 * pasture service is priced on {@link #RUMINANTS}.
	 */
	public static final List<String> PRICE_NAMES = List.of("wheat", "maize", "rice", "oilcropsNFix", "oilcropsOther",
			"pulses", "starchyRoots", "fruitveg", "sugar", ENERGY_CROPS, RUMINANTS, "monogastrics", "fishSeafood",
			WOOD);

	/** The production commodities a market service doesn't have to supply: they are then sent as 0. */
	public static final List<String> OPTIONAL_PRODUCTION = List.of(ENERGY_CROPS);

	private PlumCommodities() {
	}
}
