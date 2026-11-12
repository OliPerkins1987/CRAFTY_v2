package de.cesr.crafty.landsymm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import de.cesr.crafty.core.modelRunner.ModelRunner;
import de.cesr.crafty.core.modelRunner.ModelState;
import de.cesr.crafty.core.updaters.FlagUpdater;
import de.cesr.crafty.core.updaters.SupplyUpdater;
import de.cesr.crafty.core.updaters.Timestep;

/**
 * Puts the coupler's four steps into CRAFTY's yearly schedule, which ModelRunner.start() builds as:
 *
 * <pre>
 * Flag, Services, Capital, ProductionCost, [react], Subsidy, Afts, CellBehaviour, LandMask, Capital_Degradation,
 * Supply, Listener, Tracker, RegionsModelRunner (giving up, competition), Timestep
 * </pre>
 *
 * <ul>
 * <li>PlumInputStep: before Flag, so prices are set before ServicesUpdater and react read them;</li>
 * <li>TradeCapitalStep: after Capital, which reloads every capital from the year's file;</li>
 * <li>ConsumptionStep: after Supply, before RegionsModelRunner computes export shares;</li>
 * <li>ProductionStep: after RegionsModelRunner in sequential mode (production after this year's land use), after
 * ConsumptionStep in overlapping mode (production before it).</li>
 * </ul>
 * The anchors are found with tests, so a toy schedule can be used in tests. The run stops if one is missing.
 */
public final class StepInserter {

	/** How each anchor in the schedule is recognised. */
	public record Anchors(Predicate<ModelState> flag, Predicate<ModelState> capital, Predicate<ModelState> supply,
			Predicate<ModelState> regions, Predicate<ModelState> timestep) {

		/** The anchors in a started ModelRunner's schedule. */
		public static Anchors of(ModelRunner runner) {
			return new Anchors(s -> s instanceof FlagUpdater, s -> s == ModelRunner.capitalUpdater,
					s -> s instanceof SupplyUpdater, s -> s == runner.regionsModelRunnerUpdater,
					s -> s instanceof Timestep);
		}
	}

	/** The four coupler steps. */
	public record Steps(ModelState plumInput, ModelState tradeCapital, ModelState consumption,
			ModelState production) {
	}

	private StepInserter() {
	}

	/**
	 * Inserts the steps for a coupling mode ("sequential" or "overlapping"). Stops with a
	 * {@link CouplingInputException} naming any anchor that isn't in the schedule.
	 */
	public static void insert(List<ModelState> schedule, Steps steps, String mode, Anchors anchors) {
		List<String> missing = new ArrayList<>();
		check(schedule, anchors.flag(), "FlagUpdater", missing);
		check(schedule, anchors.capital(), "CapitalUpdater", missing);
		check(schedule, anchors.supply(), "SupplyUpdater", missing);
		check(schedule, anchors.regions(), "RegionsModelRunnerUpdater", missing);
		check(schedule, anchors.timestep(), "Timestep", missing);
		if (!missing.isEmpty()) {
			throw new CouplingInputException("CRAFTY's yearly schedule has no " + missing
					+ ", so the coupler's steps can't be placed");
		}
		if (indexOf(schedule, anchors.timestep()) < indexOf(schedule, anchors.regions())) {
			throw new CouplingInputException("In CRAFTY's yearly schedule, Timestep comes before "
					+ "RegionsModelRunnerUpdater, so the year can't end with the coupler's production step");
		}
		boolean sequential = "sequential".equals(mode);
		if (!sequential && !"overlapping".equals(mode)) {
			throw new CouplingInputException("plum_coupling_mode must be sequential or overlapping, got: " + mode);
		}

		schedule.add(indexOf(schedule, anchors.flag()), steps.plumInput());
		schedule.add(indexOf(schedule, anchors.capital()) + 1, steps.tradeCapital());
		schedule.add(indexOf(schedule, anchors.supply()) + 1, steps.consumption());
		if (sequential) {
			schedule.add(indexOf(schedule, anchors.regions()) + 1, steps.production());
		} else {
			schedule.add(schedule.indexOf(steps.consumption()) + 1, steps.production());
		}
	}

	private static void check(List<ModelState> schedule, Predicate<ModelState> anchor, String name,
			List<String> missing) {
		if (indexOf(schedule, anchor) < 0) {
			missing.add(name);
		}
	}

	private static int indexOf(List<ModelState> schedule, Predicate<ModelState> anchor) {
		for (int i = 0; i < schedule.size(); i++) {
			if (anchor.test(schedule.get(i))) {
				return i;
			}
		}
		return -1;
	}
}
