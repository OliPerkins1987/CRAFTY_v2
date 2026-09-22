package de.cesr.crafty.react.science;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProspectTheoryTest {

	@Test
	void valueMatchesR() {
		GoldenCsv.check("prospect_value", "value",
				r -> ProspectTheory.value(r.get("x"), r.get("ref"), r.get("alpha"), r.get("beta"), r.get("lambda")));
	}

	@Test
	void atTheReferencePointTheValueIsZero() {
		assertEquals(0, ProspectTheory.value(0.33, 0.33, 0.88, 0.88, 2.25));
	}

	@Test
	void anOutcomeBelowTheReferenceIsALossEvenIfItIsPositive() {
		assertTrue(ProspectTheory.value(0.2, 0.5, 0.88, 0.88, 2.25) < 0);
	}

	@Test
	void lossesWeighMoreThanEqualGains() {
		double gain = ProspectTheory.value(1, 0, 0.88, 0.88, 2.25);
		double loss = ProspectTheory.value(-1, 0, 0.88, 0.88, 2.25);
		assertEquals(1, gain);
		assertEquals(-2.25, loss);
	}
}
