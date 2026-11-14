package de.cesr.crafty.landsymm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.cesr.crafty.core.cli.Config;
import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.crafty.Cell;
import de.cesr.crafty.core.dataLoader.serivces.ServiceSet;
import de.cesr.crafty.core.utils.general.DeterministicAggregation;

/**
 * Core's static state that cells read (the config, the service list and which services are market goods), set for a
 * test and put back by {@link #close()}.
 */
final class CoreStateForTests implements AutoCloseable {

	private final Config configBefore;
	private final List<String> servicesBefore;
	private final Map<String, Boolean> marketGoodsBefore;

	CoreStateForTests(Config config, List<String> services, Set<String> marketGoods) {
		configBefore = ConfigLoader.config;
		servicesBefore = new ArrayList<>(ServiceSet.getServicesList());
		marketGoodsBefore = new HashMap<>(ServiceSet.getMarketGood());
		ConfigLoader.config = config;
		ServiceSet.getServicesList().clear();
		ServiceSet.getServicesList().addAll(services);
		ServiceSet.getMarketGood().clear();
		services.forEach(s -> ServiceSet.getMarketGood().put(s, marketGoods.contains(s)));
	}

	/** Core's own sum of a region's supply (RegionalModelRunner.computeRegionsSupply), over every service. */
	static Map<String, Double> coreSum(Collection<Cell> cells) {
		Map<String, Double> supply = new HashMap<>();
		List<String> services = ServiceSet.getServicesList();
		for (Cell c : DeterministicAggregation.cellsInStableOrder(cells)) {
			for (int i = 0; i < services.size(); i++) {
				supply.merge(services.get(i), c.currentSupply(i), Double::sum);
			}
		}
		return supply;
	}

	@Override
	public void close() {
		ConfigLoader.config = configBefore;
		ServiceSet.getServicesList().clear();
		ServiceSet.getServicesList().addAll(servicesBefore);
		ServiceSet.getMarketGood().clear();
		ServiceSet.getMarketGood().putAll(marketGoodsBefore);
	}
}
