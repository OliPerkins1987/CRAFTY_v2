package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SelfSufficiencyLogisticRuleTest {

	private static TradeCapitalRule.Inputs inputs(double production, double domesticUse) {
		return new TradeCapitalRule.Inputs("Tanzania", "Tanzania", "c3_cereals_trade", "wheat", production,
				domesticUse, 0.3, 0.4);
	}

	@Test
	void theDefaultCurve() {
		SelfSufficiencyLogisticRule rule = new SelfSufficiencyLogisticRule(6, 1);

		assertEquals(0.5, rule.next(inputs(10, 10)), 1e-12, "self-sufficient");
		assertEquals(1 / (1 + Math.exp(-6)), rule.next(inputs(20, 10)), 1e-12);
		assertEquals(0.9975, rule.next(inputs(20, 10)), 5e-5, "twice its use");
		assertEquals(0.0474, rule.next(inputs(5, 10)), 5e-5, "half its use");
		assertEquals(1.0, rule.next(inputs(10, 0)), 0.0, "everything exported");
		assertEquals(1.0, rule.next(inputs(10, -2)), 0.0, "exports more than it produces");
		assertEquals(1 / (1 + Math.exp(6)), rule.next(inputs(0, 10)), 1e-12, "no production");
		assertEquals(0.0025, rule.next(inputs(0, 0)), 5e-5, "nothing at all counts as no production");
	}

	@Test
	void theSlopeAndMidpointAreTheConfigs() {
		assertEquals(1 / (1 + Math.exp(-3)), new SelfSufficiencyLogisticRule(3, 1).next(inputs(20, 10)), 1e-12);
		assertEquals(0.5, new SelfSufficiencyLogisticRule(6, 1.5).next(inputs(15, 10)), 1e-12);
	}

	@Test
	void theRuleIgnoresLastAndFileValues() {
		SelfSufficiencyLogisticRule rule = new SelfSufficiencyLogisticRule(6, 1);
		TradeCapitalRule.Inputs other = new TradeCapitalRule.Inputs("Tanzania", "Tanzania", "c3_cereals_trade",
				"wheat", 10, 10, 0.9, 0.1);

		assertEquals(rule.next(inputs(10, 10)), rule.next(other), 0.0);
	}

	@Test
	void selfSufficiencyIsProductionOverUse() {
		assertEquals(2.0, inputs(20, 10).selfSufficiency(), 0.0);
		assertEquals(Double.POSITIVE_INFINITY, inputs(20, 0).selfSufficiency());
		assertEquals(0.0, inputs(0, 10).selfSufficiency(), 0.0);
		assertEquals(0.0, inputs(-1, 10).selfSufficiency(), 0.0);
	}
}
