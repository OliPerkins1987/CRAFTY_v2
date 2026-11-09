package de.cesr.crafty.react.science;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class IntensityFunctionsTest {

	// ---- against R ----

	@Test
	void effFuncMatchesR() {
		GoldenCsv.check("eff_func", "intensity",
				r -> IntensityFunctions.effFunc(r.get("capital"), r.get("floor"), r.get("threshold")));
	}

	@Test
	void husbandryMatchesR() {
		GoldenCsv.check("husbandry", "husbandry", r -> IntensityFunctions.animalHusbandry(r.get("x")));
	}

	@Test
	void capitalNitrogenMatchesR() {
		// R has no minimum: its rule is the minimum-0 case.
		GoldenCsv.check("capital_nitrogen", "N",
				r -> IntensityFunctions.capitalNitrogen(0, r.get("baseline"), r.get("capital"), r.get("nPar")));
	}

	// ---- the rules, stated directly ----

	@Test
	void effFuncRunsFromTheFloorAtTheThresholdToOneAtCapitalOne() {
		assertEquals(0.6, IntensityFunctions.effFunc(0.1, 0.6, 0.2));
		assertEquals(0.6, IntensityFunctions.effFunc(0.2, 0.6, 0.2));
		assertEquals(0.8, IntensityFunctions.effFunc(0.6, 0.6, 0.2), 1e-12);
		assertEquals(1, IntensityFunctions.effFunc(1, 0.6, 0.2), 1e-12);
		assertEquals(1, IntensityFunctions.effFunc(1.5, 0.6, 0.2));
	}

	@Test
	void husbandryStaysBetweenHalfAndOneAndThreeQuarters() {
		assertEquals(0.5, IntensityFunctions.animalHusbandry(0));
		assertEquals(0.5, IntensityFunctions.animalHusbandry(-0.3), "a negative argument is 0");
		assertEquals(1.125, IntensityFunctions.animalHusbandry(0.25), 1e-12);
		assertEquals(1.75, IntensityFunctions.animalHusbandry(1));
		assertEquals(1.75, IntensityFunctions.animalHusbandry(1.6), "an argument above 1 is 1");
	}

	@Test
	void capitalNitrogenWithNoMinimumStaysBetweenZeroAndTheMaximum() {
		assertEquals(200, IntensityFunctions.capitalNitrogen(0, 200, 0.9, 2));
		assertEquals(100, IntensityFunctions.capitalNitrogen(0, 200, 0.25, 2), 1e-12);
		assertEquals(0, IntensityFunctions.capitalNitrogen(0, 200, -0.1, 2), "a negative capital gives 0, not less");
	}

	@Test
	void capitalNitrogenRisesInAStraightLineFromTheMinimumToTheMaximum() {
		assertEquals(20, IntensityFunctions.capitalNitrogen(20, 50, 0, 30), "no capital: the minimum");
		assertEquals(27.5, IntensityFunctions.capitalNitrogen(20, 50, 0.25 / 30, 30), 1e-12,
				"capital x nPar = 0.25: a quarter of the way from 20 to 50");
		assertEquals(35, IntensityFunctions.capitalNitrogen(20, 50, 0.5 / 30, 30), 1e-12,
				"capital x nPar = 0.5: half way from 20 to 50");
		assertEquals(50, IntensityFunctions.capitalNitrogen(20, 50, 1.0 / 30, 30), 1e-12,
				"capital x nPar = 1: the maximum, as with no minimum");
		assertEquals(50, IntensityFunctions.capitalNitrogen(20, 50, 0.9, 30), "held at the maximum above that");
		assertEquals(20, IntensityFunctions.capitalNitrogen(20, 50, -0.1, 30), "a negative capital gives the minimum");
		assertEquals(50, IntensityFunctions.capitalNitrogen(50, 50, 0, 30), "a minimum at the maximum holds N there");
	}
}
