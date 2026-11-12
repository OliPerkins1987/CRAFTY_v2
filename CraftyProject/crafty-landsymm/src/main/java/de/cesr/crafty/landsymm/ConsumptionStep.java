package de.cesr.crafty.landsymm;

/**
 * After SupplyUpdater: with plum_consumption, sets this year's consumption for transport export shares from PLUM (48d).
 *
 * In 48b the step is in place but does nothing yet; 48d fills it in.
 */
final class ConsumptionStep extends CouplerStep {

	@Override
	public void step() {
		// filled in by 48d
	}
}
