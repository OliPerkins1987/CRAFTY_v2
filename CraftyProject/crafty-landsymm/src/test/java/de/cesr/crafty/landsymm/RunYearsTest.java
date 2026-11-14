package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import de.cesr.crafty.core.updaters.Timestep;

class RunYearsTest {

	@Test
	void aCalibrationRunCoversTheStartYearOnly() {
		assertEquals(1990, RunYears.endYear(true, 0, 1990, 2020));
		assertEquals(1990, RunYears.endYear(true, 2000, 1990, 2020), "coupled_end_year is for scenario runs");
	}

	@Test
	void aScenarioRunEndsAtCoupledEndYearOrTheScenariosEnd() {
		assertEquals(2000, RunYears.endYear(false, 2000, 1990, 2020));
		assertEquals(2020, RunYears.endYear(false, 0, 1990, 2020));
	}

	@Test
	void theEndYearAndTheNumberOfYearsAreSetTogether() {
		int endBefore = Timestep.getEndtYear();
		int sizeBefore = Timestep.getSize();
		try {
			Timestep.setEndtYear(2020);
			Timestep.setSize(31);

			RunYears.apply(1990, 2000);

			assertEquals(2000, Timestep.getEndtYear());
			assertEquals(11, Timestep.getSize());
		} finally {
			Timestep.setEndtYear(endBefore);
			Timestep.setSize(sizeBefore);
		}
	}
}
