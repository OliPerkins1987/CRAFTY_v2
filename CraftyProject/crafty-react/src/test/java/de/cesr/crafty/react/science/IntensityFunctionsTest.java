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
		GoldenCsv.check("capital_nitrogen", "N",
				r -> IntensityFunctions.capitalNitrogen(r.get("baseline"), r.get("capital"), r.get("nPar")));
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
	void capitalNitrogenStaysBetweenZeroAndTheBaseline() {
		assertEquals(200, IntensityFunctions.capitalNitrogen(200, 0.9, 2));
		assertEquals(100, IntensityFunctions.capitalNitrogen(200, 0.25, 2), 1e-12);
		assertEquals(0, IntensityFunctions.capitalNitrogen(200, -0.1, 2), "a negative capital gives 0, not less");
	}
}
