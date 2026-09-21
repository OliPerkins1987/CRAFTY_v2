package de.cesr.crafty.react.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One reactive AFT's parameters, already checked (see {@link ReactiveParameters}), together with its
 * baselines from core's {@code AFTsMetaData.csv}.
 *
 * The rule is: baselines live in core, and the react sheet holds only what makes an AFT move away from
 * them. So N starts at {@code Nfert_rate}, crops other intensity starts at {@code Other_intensity}, and
 * whether the AFT irrigates is core's {@code Irrigated}.
 *
 * A {@code null} value means the cell was blank and nothing stands in for it. Where a blank has a
 * meaning, the default is already filled in: a Prospect AFT's {@code nPar} and a pasture AFT's
 * {@code sPar} are 0 when blank.
 *
 * @param label      the AFT
 * @param service    {@code react_service}
 * @param type       the service's {@code LPJG_type}: {@code CROPS} or {@code PASTURE}
 * @param lpjgName   the service's name in LPJ-GUESS column headers
 * @param nMode      {@code react_N_type}; null for pasture, or when blank
 * @param nCapital   {@code react_N_capital}: the capital a Capital AFT's N follows
 * @param nPar       {@code react_N_par}: for Prospect, the inertia threshold per kg N (≥ 0); for
 *                   Capital, the multiplier (> 0)
 * @param iEff       {@code react_I_eff}: irrigation efficiency (0, 1]
 * @param oCapital   {@code react_O_capital}: the capital other intensity follows
 * @param oPar       {@code react_O_par}: the {@code Eff_func} threshold (crops) or husbandry multiplier
 *                   (pasture)
 * @param sPar       {@code react_S_par}: stocking improvement threshold (pasture)
 * @param rPar       {@code react_R_par}: kept for forestry, not used yet
 * @param baseline   the AFT's baselines from core
 */
public record AftReactParameters(String label, String service, LpjgType type, String lpjgName, NitrogenMode nMode,
		String nCapital, Double nPar, Double iEff, String oCapital, Double oPar, Double sPar, Double rPar,
		ReactRunContext.AftBaseline baseline) {

	/** The N baseline, kg N/ha: core's {@code Nfert_rate}. */
	public double nitrogenBaseline() {
		return baseline.nfertRate();
	}

	/** The other-intensity baseline: core's {@code Other_intensity}. */
	public double otherIntensityBaseline() {
		return baseline.otherIntensity();
	}

	/** Whether the AFT irrigates: core's {@code Irrigated}. */
	public boolean isIrrigated() {
		return baseline.irrigated();
	}

	public boolean isCrops() {
		return type == LpjgType.CROPS;
	}

	public boolean isPasture() {
		return type == LpjgType.PASTURE;
	}

	/** Whether N follows a capital rather than price. */
	public boolean usesCapitalForN() {
		return nMode == NitrogenMode.CAPITAL;
	}

	/**
	 * Whether other intensity follows a capital. For a crops AFT, false means its other intensity stays
	 * at {@code Other_intensity} even with the element switched on (for example, extensive crops).
	 */
	public boolean hasCapitalDrivenOtherIntensity() {
		return oCapital != null;
	}

	/** The capitals this AFT reads, for the elements that are switched on. */
	public List<String> capitalsNamed(Set<ReactElement> reactive) {
		List<String> capitals = new ArrayList<>();
		if (reactive.contains(ReactElement.FERTILISER) && usesCapitalForN() && nCapital != null) {
			capitals.add(nCapital);
		}
		if (reactive.contains(ReactElement.OTHER_INTENSITY) && oCapital != null) {
			capitals.add(oCapital);
		}
		return capitals;
	}
}
