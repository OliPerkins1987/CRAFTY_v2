package de.cesr.crafty.react.data;

/**
 * The management elements that can be reactive, each switched on or off in core's config.yaml
 * ({@code reactive_fertilizer}, {@code reactive_irrigation}, {@code reactive_other_intensity},
 * {@code reactive_stocking}). Each one has a spatial cost file that react writes when the element is on.
 *
 * Forestry (rotation) is not listed: it is not implemented yet, and forestry services are refused at
 * startup.
 */
public enum ReactElement {
	FERTILISER("Nfert_costs"), IRRIGATION("Irrigation_costs"), OTHER_INTENSITY("Intensity_costs"),
	STOCKING("stocking_costs");

	private final String costFile;

	ReactElement(String costFile) {
		this.costFile = costFile;
	}

	/** The name core's spatial cost files for this element start with, for messages. */
	public String costFile() {
		return costFile;
	}

	@Override
	public String toString() {
		return name().toLowerCase().replace('_', ' ');
	}
}
