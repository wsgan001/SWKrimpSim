package com.irisa.krimp.data;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

import com.irisa.swpatterns.data.AttributeIndex;

/**
 * Intended to replace SPMF Itemsets
 * @author pmaillot
 *
 */
public class ItemsetSet extends LinkedList<KItemset> {

//	protected HashMap<Integer, HashSet<KItemset>> itemItemsetIndex = new HashMap<Integer, HashSet<KItemset>>();
	private BitSet _knownItemSet = new BitSet(); // An set bit at position i indicate the use of item i somewhere in the dataset
	
	public ItemsetSet() {
		super();
	}
	
	public ItemsetSet(ItemsetSet is) {
		this();
		for(KItemset code : is) {
			addItemset(code);
		}
//		itemItemsetIndex = new HashMap<Integer, HashSet<KItemset>>(is.itemItemsetIndex);
	}
	
	public void addItemset(KItemset auxCode) {
		KItemset newIs = new KItemset(auxCode);
		super.add(newIs);
//		Iterator<Integer> itAux = auxCode.iterator();
//		while(itAux.hasNext()) {
//			int item = itAux.next();
//			if(itemItemsetIndex.get(item) == null) {
//				itemItemsetIndex.put(item, new HashSet<KItemset>());
//			}
//			itemItemsetIndex.get(item).add(newIs);
//		}
		for(int item : auxCode) {
			_knownItemSet.set(item);
		}
	}
	
	/**
	 * @return List of items used in this itemsetSet
	 */
	public List<Integer> knownItems() {
		LinkedList<Integer> result = new LinkedList<Integer>();
		
		int index = 0;
		while(this._knownItemSet.nextSetBit(index) >= 0) {
			result.add(this._knownItemSet.nextSetBit(index));
			index++;
		}
		
		return result;
	}
	
	public double averageSize() {
		int sumSize = 0;
		
		for( KItemset t : this) {
			sumSize += t.size();
		}
		
		return (double)sumSize/(double)this.size();
	}
	
	/**
	 * Process the whole dataset
	 * @return density value
	 */
	public double density() {
		int sumSize = 0;
		BitSet itemset = new BitSet();
		
		for( KItemset t : this) {
			sumSize += t.size();
			for( int item : t ) {
				itemset.set(item);
			}
		}
		
		return (double)sumSize/((double)this.size()*(double)itemset.cardinality());
	}
	
	public String toString() {
		StringBuilder r = new StringBuilder ();
		Iterator<KItemset> itIs = this.iterator();
		while(itIs.hasNext()) {
			KItemset is = itIs.next();
			r.append(is.toString());
			r.append(" (");
			r.append(is.getUsage());
			r.append(",");
			r.append(is.getSupport());
			r.append(")\n");
		}
		
		return r.toString();
	}
}
