package de.cesr.crafty.core.crafty;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base representation of a spatial cell in the CRAFTY grid.
 *
 * A cell is the core spatial unit of the model. It stores:
 * - Spatial position ({@link #x}, {@link #y}) and an string identifier ({@link #id}).
 * - The current owning manager ({@link Aft}) and region assignment.
 * - Capital used by production/utility calculations.
 * - Current service production vector ({@link #currentProd}) and the last computed utility value.
 *   Production is per unit area (e.g. t/ha), like the capitals it comes from.
 * - The cell's land area ({@link #area}), 1 unless {@code use_cell_area} is on. It is used only where
 *   cells are added up into supply ({@link Cell#currentSupply(int)}); everything per cell stays per unit area.
 * - mask type used for masks and protected areas
 * - Optional visual attributes used for outputs and plotting.
 *
 * This class is intentionally lightweight and focused on state storage; higher-level behaviour
 * (e.g., utility calculation, transitions) is implemented in child class {@link Cell}
 *
 * Capitals and production costs are stored as {@link IndexedValues}: a double[] per cell, with each name's
 * position shared by every cell, which takes far less memory than a map per cell. They answer the same calls
 * as the maps they replace, with the same results (a value never put is missing, not 0).
 *
 * Concurrency note:
 * Different cells may be filled from parallel computations (the CSV loaders fill one row, so one cell, per
 * thread), but two threads must not write to the same cell's capitals or costs at the same time. Callers should
 * still ensure updates are performed in a controlled way (e.g., by step/updater logic) to avoid inconsistent states.
 */

/**
 * @author Mohamed Byari
 *
 */
public abstract class AbstractCell {
	// Where each capital, and each AFT for each cost, sits in a cell's values; shared by every cell. A cost has
	// its own index so that its values cover only the AFTs that pay it.
	private static final IndexedValues.Index CAPITAL_NAMES = new IndexedValues.Index();
	private static final IndexedValues.Index NFERT_COST_AFTS = new IndexedValues.Index();
	private static final IndexedValues.Index IRRIGATION_COST_AFTS = new IndexedValues.Index();
	private static final IndexedValues.Index INTENSITY_COST_AFTS = new IndexedValues.Index();
	private static final IndexedValues.Index STOCKING_COST_AFTS = new IndexedValues.Index();

//	static int size = 1;
	private String id;
	int x, y;
	private IndexedValues capitals = new IndexedValues(CAPITAL_NAMES);
	private ConcurrentHashMap<String, Double> ServicesTax = new ConcurrentHashMap<>();
	private ConcurrentHashMap<String, Double> landTax = new ConcurrentHashMap<>();
	private ConcurrentHashMap<String, Double> capitalsAdjusment = new ConcurrentHashMap<>();

	// Production cost storage (AFT label -> $/ha)
	private IndexedValues nfertCosts = new IndexedValues(NFERT_COST_AFTS);
	private IndexedValues irrigationCosts = new IndexedValues(IRRIGATION_COST_AFTS);
	private IndexedValues intensityCosts = new IndexedValues(INTENSITY_COST_AFTS);
	private IndexedValues stockingCosts = new IndexedValues(STOCKING_COST_AFTS);

	private double giveUpSubsidy = 0.0;

	private double[] currentProd;
	// Land area, in the unit production is per (ha for t/ha yields); set by CellAreaLoader from the GIS file.
	private double area = 1.0;
	private double currentUtility;
	String CurrentRegion;
	private Aft owner;
	protected String color = "#848484";
	private String maskType;
	private int OwnerLifeCounter = 1;

	private long sID;
	private double score;

	public long getCellID() {
		return sID;
	}

	public void setCellID(long sID) {
		this.sID = sID;
	}

	public double getScore() {
		return score;
	}

	public void setScore(double score) {
		this.score = score;
	}

	public int getOwnerLifeCounter() {
		return OwnerLifeCounter;
	}

	public void setOwnerLifeCounter(int ownerLifeCounter) {
		OwnerLifeCounter = ownerLifeCounter;
	}

	public void OwnerLifeCounterIncrement() {
		OwnerLifeCounter++;
	}

	public double getCurrentUtility() {
		return currentUtility;
	}

	public void setCurrentUtility(double currentUtility) {
		this.currentUtility = currentUtility;
	}

	public String getCurrentRegion() {
		return CurrentRegion;
	}

	public void setCurrentRegion(String currentRegion) {
		CurrentRegion = currentRegion;
	}

	public String getColor() {
		return color;
	}

	public void setColor(String color) {
		this.color = color;
	}

	public String getMaskType() {
		return maskType;
	}

	public void setMaskType(String maskType) {
		this.maskType = maskType;
	}

	public int getX() {
		return x;
	}

	public void setX(int x) {
		this.x = x;
	}

	public int getY() {
		return y;
	}

	public void setY(int y) {
		this.y = y;
	}

	public Aft getOwner() {
		return owner;
	}

	public String getOwnerName() {
		if (owner == null)
			return "null";
		return owner.getLabel();
	}

	public void setOwner(Aft owner) {
		this.owner = owner;
	}

	public IndexedValues getCapitals() {
		return capitals ;
	}

	public double getOneCapitals(String capitalName) {
		return getCapitals().get(capitalName);
	}

	public void setOneCapitals(String capitalName, double value) {
		getCapitals().put(capitalName, value);
	}

//	public static int getSize() {
//		return size;
//	}

//	public static void setSize(int size) {
//		AbstractCell.size = size;
//	}

	public String getID() {
		return getId();
	}

	public void setID(String id) {
		this.setId(id);
	}

	@Override
	public String toString() {
		return "\n Cell [index=" + getId() + ", x=" + x + ", y=" + y + ", CurrentRegion=" + CurrentRegion + "\n, Mask="
				+ getMaskType() + ", getOwner()=" + (getOwner() != null ? getOwner().getLabel() : " null")
				+ " category: " + (getOwner() != null ? getOwner().category : "null") + ", Color: " + color
				+ ", getCapitals()=" + getCapitals() + ", getCurrentProductivity()=" + Arrays.toString(getCurrentProd())
				+ "] \n "
		/* + CellBehaviourLoader.cellsBehevoir.get(this) */;
	}

	public double[] getCurrentProd() {
		return currentProd;
	}

	public void setCurrentProd(double[] currentProd) {
		this.currentProd = currentProd;
	}

	public double getArea() {
		return area;
	}

	public void setArea(double area) {
		this.area = area;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public void setCapitals(IndexedValues capitals) {
		this.capitals = capitals;
	}

	public ConcurrentHashMap<String, Double> getServicesTax() {
		return ServicesTax;
	}

	public ConcurrentHashMap<String, Double> getLandTax() {
		return landTax;
	}

	public ConcurrentHashMap<String, Double> getCapitalsAdjusment() {
		return capitalsAdjusment;
	}

	public IndexedValues getNfertCosts() {
		return nfertCosts;
	}

	public IndexedValues getIrrigationCosts() {
		return irrigationCosts;
	}

	public IndexedValues getIntensityCosts() {
		return intensityCosts;
	}

	public IndexedValues getStockingCosts() {
		return stockingCosts;
	}

	public double getGiveUpSubsidy() {
		return giveUpSubsidy;
	}

	public void setGiveUpSubsidy(double giveUpSubsidy) {
		this.giveUpSubsidy = giveUpSubsidy;
	}

}
