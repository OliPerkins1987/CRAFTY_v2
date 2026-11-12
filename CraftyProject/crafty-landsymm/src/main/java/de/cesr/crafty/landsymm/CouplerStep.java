package de.cesr.crafty.landsymm;

import de.cesr.crafty.core.modelRunner.AbstractModelRunner;
import de.cesr.crafty.core.modelRunner.ModelState;

/**
 * A coupler step in CRAFTY's yearly schedule. {@link StepInserter} places the steps; they are not year-zero
 * updaters, so they run in the first year too.
 */
abstract class CouplerStep implements ModelState {

	@Override
	public void setup(AbstractModelRunner modelRunner) {
		// nothing to set up: the coupler steps hold their own state
	}

	@Override
	public void toSchedule() {
		// placed by StepInserter, not scheduled by the runner
	}
}
