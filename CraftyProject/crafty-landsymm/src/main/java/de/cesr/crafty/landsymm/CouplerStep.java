package de.cesr.crafty.landsymm;

import de.cesr.crafty.core.modelRunner.AbstractModelRunner;
import de.cesr.crafty.core.modelRunner.ModelState;
import de.cesr.crafty.core.updaters.Timestep;

/**
 * A coupler step in CRAFTY's yearly schedule. {@link StepInserter} places the steps; they are not year-zero
 * updaters, so they run in the first year too.
 *
 * Each step does its work in {@link #yearStep(int)}. A {@link CouplingInputException} from it stops the run with one
 * fatal message, as a start-up problem does.
 */
abstract class CouplerStep implements ModelState {

	@Override
	public final void step() {
		int year = Timestep.getCurrentYear();
		try {
			yearStep(year);
		} catch (CouplingInputException e) {
			MainLandSymm.stopIf(getClass().getSimpleName() + " in " + year, e.getProblems());
		}
	}

	/** The step's work in CRAFTY's year. */
	protected abstract void yearStep(int year);

	@Override
	public void setup(AbstractModelRunner modelRunner) {
		// nothing to set up: the coupler steps hold their own state
	}

	@Override
	public void toSchedule() {
		// placed by StepInserter, not scheduled by the runner
	}
}
