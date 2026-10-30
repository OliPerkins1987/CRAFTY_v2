package de.cesr.crafty.core.crafty;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.dataLoader.serivces.ServiceSet;
import de.cesr.crafty.core.updaters.CapitalUpdater;
import de.cesr.crafty.core.output.Listener;
import de.cesr.crafty.core.output.Tracker;
import de.cesr.crafty.core.updaters.ProductionCostUpdater;
import de.cesr.crafty.core.updaters.Timestep;
import de.cesr.crafty.core.utils.general.DeterministicRandom;

/**
 * Concrete spatial cell implementation used during simulation.
 *
 * In addition to the basic state stored in {@link AbstractCell}, this class implements core per-cell
 * calculations and events that depend on the current owner (AFT):
 *
 * - productivity:
 *   {@link #productivity(Aft, String)} computes service potontial production as a multiplicative function of
 *   capitals raised to AFT-specific sensitivity exponents (service -> capital -> exponent),
 *   multiplied by an AFT-specific productivity level for the service.
 *   {@link #calculateCurrentProductivity()} fills the full production vector for all services.
 *   {@link #currentSupply(int)} is what the cell adds to supply: production times its area when
 *   {@code use_cell_area} is on, production alone otherwise, less the owner's self-consumption of
 *   market goods when {@code use_self_consumption} is on.
 *
 * - Production costs:
 *   {@link #productionCost(Aft, RegionalModelRunner)} is the $/ha subtracted from explicit-price utility:
 *   fertiliser, irrigation, intensity and stocking costs, plus
 *   {@link #transportCost(Aft, RegionalModelRunner)}.
 *
 * - Behavioural land abandonment (give-up):
 *   {@link #giveUp(RegionalModelRunner, ConcurrentHashMap)} compares the cell utility to the mean utility
 *   of the owning AFT (from the regional distribution statistics) and stochastically abandons the cell
 *   using the AFT’s give-up parameters. When give-up occurs, the owner is cleared, the cell is added to
 *   the region’s unmanaged pool, and the global land-use change counter is incremented
 *   ({@link Listener#landUseChangeCounter}).
 *
 * Notes:
 * - Production assumes required capital values are present in the cell’s capital map.
 * - The production vector is sized using the current global service list ({@link ServiceSet}).
 */

/**
 * @author Mohamed Byari
 *
 */

public class Cell extends AbstractCell {

	private static final CustomLogger LOGGER = new CustomLogger(Cell.class);

	public Cell(int x, int y) {
		this.x = x;
		this.y = y;
		this.setCellID(DeterministicRandom.stableCellId(x, y));
		setCurrentProd(new double[ServiceSet.getServicesList().size()]);
	}

	private static final java.util.Set<String> warnedMissingSensitivities = ConcurrentHashMap.newKeySet();

	public double productivity(Aft a, String serviceName) {
		if (a == null || !a.isInteract())
			return 0.0;
		if (ConfigLoader.config.separate_production_competitiveness) {
			final Map<String, Double> exps = a.getSensByService().get(serviceName);
			if (exps == null || exps.isEmpty()) {
				if (warnedMissingSensitivities.add(a.getLabel() + "|" + serviceName)) {
					LOGGER.warn("AFT '" + a.getLabel() + "' has no capital sensitivities for service '"
							+ serviceName + "' - production for this service is treated as 0");
				}
				return 0.0;
			}
			// Production counts suitability capitals only.
			return capitalProduct(exps, true) * a.getProductivityLevel().get(serviceName);
		}
		return competitiveness(a, serviceName);
	}

	public double competitiveness(Aft a, String serviceName) {
		if (a == null || !a.isInteract())
			return 0.0;

		final Map<String, Double> exps = a.getSensByService().get(serviceName);
		if (exps == null || exps.isEmpty())
			return 0.0;

		// Competitiveness counts every capital the AFT is sensitive to.
		return capitalProduct(exps, false) * a.getProductivityLevel().get(serviceName);
	}

	private double capitalProduct(Map<String, Double> exps, boolean suitabilityOnly) {
		double product = 1.0;

		for (var e : exps.entrySet()) {
			if (suitabilityOnly && !CapitalUpdater.isSuitability(e.getKey()))
				continue;
			final double p = e.getValue();
			if (p == 0.0)
				continue;
			final double capVal = (getCapitals().getOrDefault(e.getKey(), 0.)
					* (1 + getCapitalsAdjusment().getOrDefault(e.getKey(), 0.)));
			// Exponent 1 is the common case and skips the cost of Math.pow.
			if (p == 1.0)
				product *= capVal;
			else
				product *= Math.pow(capVal, p);
		}

		return product;
	}

	/**
	 * @param r the cell's region, for the export shares that spatial-mode transport is charged on;
	 *          null (tests only) charges no spatial-mode transport
	 */
	public double productionCost(Aft a, RegionalModelRunner r) {
		if (a == null || !a.isInteract() || !ConfigLoader.isUseProductionCosts()) {
			return 0.0;
		}
		/*
		 * The cost components are independent and additive. An AFT that only crops has
		 * no stocking cost and one that only grazes has no fertiliser cost, but nothing
		 * here assumes that: a mixed crop/livestock AFT carries both.
		 */
		double cost = getIrrigationCosts().getOrDefault(a.getLabel(), 0.0);
		if (ConfigLoader.isSpatialProductionCosts()) {
			cost += getNfertCosts().getOrDefault(a.getLabel(), 0.0)
					+ getIntensityCosts().getOrDefault(a.getLabel(), 0.0)
					+ getStockingCosts().getOrDefault(a.getLabel(), 0.0);
		} else {
			cost += a.getNfertCostPerHa() + a.getIntensityCostPerHa() + a.getStockingCostPerHa();
		}
		return cost + transportCost(a, r);
	}

	/**
	 * The cost of moving this cell's goods, $/ha: for each service that pays transport (every market
	 * good, or every service when Services.csv has no Market_good column), the AFT's production x a
	 * factor x the transport cost per unit, less the share the AFT uses itself (with
	 * {@code use_self_consumption}), since that never travels. The factor is
	 * - with spatial production costs, the region's export share of the service (the part of its supply
	 *   above its demand, see {@link RegionalModelRunner#exportShare(double, double)}), whatever the AFT's
	 *   capitals;
	 * - with global production costs, the AFT's trade factor (its *_trade value on that service's row).
	 * It is charged on production, the quantity supply is built from, though utility's revenue is built
	 * on competitiveness. It is kept out of the cell cost maps, so CRAFTY-react never sees it.
	 */
	double transportCost(Aft a, RegionalModelRunner r) {
		double[] unitCosts = ProductionCostUpdater.getTransportCosts();
		if (unitCosts.length == 0) {
			return 0.0;
		}
		final boolean bySurplus = ConfigLoader.isTransportByExportShare();
		// Only tests pass no region; then no service has an export share.
		final double[] exportShare = !bySurplus || r == null ? new double[0] : r.getExportShare();
		List<String> services = ServiceSet.getServicesList();
		double cost = 0.0;
		for (int i = 0; i < Math.min(unitCosts.length, services.size()); i++) {
			// Zero for every service that does not pay transport.
			if (unitCosts[i] == 0.0)
				continue;
			final double factor = bySurplus ? (i < exportShare.length ? exportShare[i] : 0.0) : a.getTradeFactor(i);
			if (factor == 0.0)
				continue;
			cost += productivity(a, services.get(i)) * factor * unitCosts[i];
		}
		return cost * (1 - selfConsumption(a));
	}

	public void calculateCurrentProductivity() {
		for (int i = 0; i < ServiceSet.getServicesList().size(); i++) {
			getCurrentProd()[i] = productivity(getOwner(), ServiceSet.getServicesList().get(i));
		}
		// Stored with the production it belongs to: competition sets a new owner before production is recalculated.
		setCurrentSelfConsumption(selfConsumption(getOwner()));
	}

	public void calculateCurrentProductivity(String[] services) {
		for (int i = 0; i < ServiceSet.getServicesList().size(); i++) {
			getCurrentProd()[i] = productivity(getOwner(), services[i]);
		}
		setCurrentSelfConsumption(selfConsumption(getOwner()));
	}

	/**
	 * The share of the market goods it produces that an owner uses itself: its self_consumption with
	 * {@code use_self_consumption} on, 0 otherwise (and for no owner or a manager that does not produce).
	 */
	static double selfConsumption(Aft owner) {
		if (owner == null || !owner.isInteract() || !ConfigLoader.config.use_self_consumption) {
			return 0.0;
		}
		return owner.getSelfConsumption();
	}

	/**
	 * What this cell adds to the supply of one service. Production is per unit area, so with
	 * {@code use_cell_area} on this is production times the cell's area (e.g. t/ha x ha = t); with it off
	 * it is production alone, as before cell areas existed. This is the only place area enters supply.
	 *
	 * With {@code use_self_consumption} on, a market good (Market_good = Yes) is supplied less the share
	 * the owner uses itself, the share recorded when this production was calculated. Production itself
	 * ({@link #getCurrentProd()}), and so utility and the cell outputs, still count all of it.
	 */
	public double currentSupply(int serviceIndex) {
		double production = getCurrentProd()[serviceIndex];
		double supply = ConfigLoader.config.use_cell_area ? production * getArea() : production;
		double share = getCurrentSelfConsumption();
		// Only market goods are self-consumed. The share is 0 whenever use_self_consumption is off.
		if (share == 0.0 || !ServiceSet.isMarketGood(ServiceSet.getServicesList().get(serviceIndex))) {
			return supply;
		}
		return supply * (1 - share);
	}

	void giveUp(RegionalModelRunner r, ConcurrentHashMap<String, Double> distributionMean) {

		Aft owner = getOwner();
		if (owner == null || !owner.isInteract()) {
			return;
		}

		Double averageUtilityObj = distributionMean.get(owner.getLabel());
		if (averageUtilityObj == null) {
			return;
		}

		double utility = getCurrentUtility();
		double averageUtility = averageUtilityObj;

		long runSeed = ConfigLoader.config.random_seed;
		int year = Timestep.getCurrentYear();

		long cellId = DeterministicRandom.stableCellKey(this);
		long aftId = DeterministicRandom.stableAftId(owner.getLabel());

		double gaussian = DeterministicRandom.randomGaussian(runSeed, year,
				DeterministicRandom.Process.GIVE_UP_GAUSSIAN, cellId, aftId, 0);

		double thresholdFactor = owner.getGiveUpMean() + owner.getGiveUpSD() * gaussian;

		boolean passesUtilityTest = utility < averageUtility * thresholdFactor;
		boolean passesProbabilityTest = DeterministicRandom.randomBoolean(runSeed, year,
				DeterministicRandom.Process.GIVE_UP_PROBABILITY, cellId, aftId, 0, owner.getGiveUpProbabilty());

		boolean giveUp = passesUtilityTest && passesProbabilityTest;
//		System.out.println("Utility= "+utility +" average Utiulity= "+ averageUtility +" threshold= "+ thresholdFactor+"==> "+giveUp);
		if (giveUp) {
			String oldOwner = getOwner().getLabel();
			setOwner(null);
//			CellsUpdater.decesionsNewOwner.put(this, AFTsLoader.getAftHash().get("Abandoned"));
			r.R.getUnmanageCellsR().add(this);
			setOwnerLifeCounter(0);
			Listener.landUseChangeCounter.getAndIncrement();
			Listener.newAftsInLandNbr.merge("null", 1, Integer::sum);
			Tracker.sankeydata.get("Abandoned").get(Timestep.getCurrentYear()).merge(oldOwner, 1, Integer::sum);
//			RegionalModelRunner.tmp.getAndIncrement();
		}
	}

	void priceExplicitGiveUp(RegionalModelRunner r) {
		Aft owner = getOwner();
		if (owner == null || !owner.isInteract()) {
			return;
		}

		double utility = getCurrentUtility();
		double subsidy = owner.getReceivesSubsidy() * getGiveUpSubsidy();

		if (utility + subsidy < 0) {
			String oldOwner = owner.getLabel();
			setOwner(null);
			r.R.getUnmanageCellsR().add(this);
			setOwnerLifeCounter(0);
			Listener.landUseChangeCounter.getAndIncrement();
			Listener.newAftsInLandNbr.merge("null", 1, Integer::sum);
			Tracker.sankeydata.get("Abandoned").get(Timestep.getCurrentYear()).merge(oldOwner, 1, Integer::sum);
		}
	}

}
