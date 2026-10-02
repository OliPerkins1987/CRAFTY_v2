package de.cesr.crafty.react.data;

/**
 * The management elements that can be reactive, each switched on or off in core's config.yaml
 * ({@code reactive_fertilizer}, {@code reactive_irrigation}, {@code reactive_other_intensity},
 * {@code reactive_stocking}, {@code reactive_forestry}). Each one has a cost that react hands to the model
 * when the element is on, in place of the reactive AFTs' values in core's spatial cost files.
 *
 * Forestry's cost (the rotation cost) is the forestry AFTs' intensity cost, so it has no cost file of its
 * own: its {@link #costFile()} is the intensity costs', and core's intensity list already holds the
 * forestry AFTs.
 */
public enum ReactElement {
	FERTILISER("Nfert_costs"), IRRIGATION("Irrigation_costs"), OTHER_INTENSITY("Intensity_costs"),
	STOCKING("stocking_costs"), FORESTRY("Intensity_costs");

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
