package de.cesr.crafty.react.science;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class IrrigationTest {

	// ---- against the R written from the plan (golden/irrigation.csv) ----

	@Test
	void demandAlphaMatchesR() {
		GoldenCsv.check("irrigation", "alphaD", r -> Irrigation.demandAlpha(r.get("d0"), r.get("d0200"), r.get("d1000")));
	}

	@Test
	void demandMatchesR() {
		GoldenCsv.check("irrigation", "demand",
				r -> Irrigation.demand(r.get("d0"), r.get("d1000"), r.get("alphaD"), r.get("N")));
	}

	@Test
	void requiredMatchesR() {
		GoldenCsv.check("irrigation", "required", r -> Irrigation.required(r.get("demand"), r.get("efficiency")));
	}

	@Test
	void appliedMatchesR() {
		GoldenCsv.check("irrigation", "applied", r -> Irrigation.applied(r.get("required"), r.get("runoff")));
	}

	@Test
	void levelMatchesR() {
		GoldenCsv.check("irrigation", "level", r -> Irrigation.level(r.get("applied"), r.get("required")));
	}

	@Test
	void costMatchesR() {
		GoldenCsv.check("irrigation", "cost", r -> Irrigation.cost(r.get("applied"), r.get("index"), r.get("water")));
	}

	@Test
	void theWholeChainMatchesR() {
		// The same rows, each step fed from the Java's own previous step rather than from R's.
		GoldenCsv.check("irrigation", "cost", r -> {
			double alphaD = Irrigation.demandAlpha(r.get("d0"), r.get("d0200"), r.get("d1000"));
			double demand = Irrigation.demand(r.get("d0"), r.get("d1000"), alphaD, r.get("N"));
			double applied = Irrigation.applied(Irrigation.required(demand, r.get("efficiency")), r.get("runoff"));
			return Irrigation.cost(applied, r.get("index"), r.get("water"));
		});
	}

	// ---- the rules, stated directly ----

	@Test
	void demandFollowsTheSameCurveAsTheYieldNResponse() {
		// Halfway at 200 kg N, so demand at 200 kg N is the 200 kg N column.
		double alphaD = Irrigation.demandAlpha(1000, 1500, 2000);
		assertEquals(1000, Irrigation.demand(1000, 2000, alphaD, 0), 1e-9);
		assertEquals(1500, Irrigation.demand(1000, 2000, alphaD, 200), 1e-9);
		assertEquals(Irrigation.demand(1000, 2000, alphaD, 1000), Irrigation.demand(1000, 2000, alphaD, 1500),
				"N above 1000 kg adds no demand");
	}

	@Test
	void demandFlatInNHasNoShape() {
		assertEquals(0, Irrigation.demandAlpha(1000, 1000, 1000));
		assertEquals(1000, Irrigation.demand(1000, 1000, 0, 500));
	}

	@Test
	void noDemandMeansFullyIrrigated() {
		double required = Irrigation.required(0, 0.7);
		double applied = Irrigation.applied(required, 500);
		assertEquals(0, applied);
		assertEquals(1, Irrigation.level(applied, required));
		assertEquals(0, Irrigation.cost(applied, 0.6, 0.5));
	}

	@Test
	void noRunoffMeansNoWaterAndNoCost() {
		double required = Irrigation.required(2000, 0.7);
		double applied = Irrigation.applied(required, 0);
		assertEquals(0, applied);
		assertEquals(0, Irrigation.level(applied, required));
		assertEquals(0, Irrigation.cost(applied, 0.6, 0.5));
	}

	@Test
	void runoffCapsTheWaterApplied() {
		double required = Irrigation.required(1400, 0.7);
		assertEquals(2000, required, 1e-9);
		assertEquals(500, Irrigation.applied(required, 500));
		assertEquals(0.25, Irrigation.level(500, required), 1e-12);
		assertEquals(150, Irrigation.cost(500, 0.6, 0.5), 1e-12);
	}

	@Test
	void plentyOfRunoffMeansFullyIrrigated() {
		double required = Irrigation.required(1400, 0.7);
		assertEquals(required, Irrigation.applied(required, 1e6));
		assertEquals(1, Irrigation.level(required, required));
	}
}
