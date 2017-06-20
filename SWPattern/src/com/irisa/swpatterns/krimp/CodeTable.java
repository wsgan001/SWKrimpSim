package com.irisa.swpatterns.krimp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import org.apache.log4j.Logger;
import org.slf4j.LoggerFactory;

import com.irisa.swpatterns.data.AttributeIndex;
import com.irisa.swpatterns.data.ItemsetSet;
import com.irisa.swpatterns.data.RDFPatternComponent;

import ca.pfv.spmf.patterns.itemset_array_integers_with_count.Itemset;

/**
 * @author pmaillot
 *
 */
public class CodeTable {
	
	private static Logger logger = Logger.getLogger(CodeTable.class);

	private AttributeIndex _index = null;
	private ItemsetSet _transactions = null;
	private ItemsetSet _codes = null;
	private HashMap<Itemset, Integer> _itemsetUsage = new HashMap<Itemset, Integer>();
	private HashMap<Itemset, Integer> _itemsetCode = new HashMap<Itemset, Integer>();
	private long _usageTotal = 0;
	
	private boolean _standardFlag = false; // Set true if it is the standard codetable
	private CodeTable _standardCT = null; // Codetable containing only singletons for the coding length of a CT
	
	/**
	 * Initialization of the usages and codes indices
	 * @param index
	 * @param transactions
	 * @param codes
	 */
	public CodeTable(AttributeIndex index, ItemsetSet transactions, ItemsetSet codes) {
		this(index, transactions, codes, false);
	}
	
	protected CodeTable(AttributeIndex index, ItemsetSet transactions, ItemsetSet codes, boolean standardFlag) {
		_index = index;
		_transactions = transactions;
		_codes = codes;
		_standardFlag = standardFlag;
		
		if(codes != null) {
			_standardCT = CodeTable.createStandardCodeTable(index, transactions);
		} else { // this is a standard codetable
			_standardCT = null;
			_codes = new ItemsetSet();
		}
		init();	
	}
	
	private void init() {
		initializeSingletons();
		initCodes();
		Collections.sort(_codes, standardCoverOrderComparator);
		countUsages();		
	}
	
	public static CodeTable createStandardCodeTable(AttributeIndex index, ItemsetSet transactions) {
		return new CodeTable(index, transactions, null, true);
	}
	
	public CodeTable(CodeTable ct) {
		_index = ct._index;
		_transactions = ct._transactions;
		_codes = new ItemsetSet(ct._codes);
		_itemsetUsage = new HashMap<Itemset, Integer>(ct._itemsetUsage);
		_itemsetCode = new HashMap<Itemset, Integer>(ct._itemsetCode);
		_usageTotal = ct._usageTotal;
		_standardFlag = ct._standardFlag;
		
		_standardCT = ct._standardCT;
	}

	public ItemsetSet getTransactions() {
		return _transactions;
	}
	
	/**
	 * Trigger reinitialization of the indexes
	 * @param transactions
	 */
	public void setTransactions(ItemsetSet transactions, AttributeIndex index) {
		this._transactions = transactions;
		this._index = index;
		
		init();
	}
	
	/**
	 * Trigger reinitialization of the indexes
	 * @param transactions
	 */
	public void setTransactions(ItemsetSet transactions) {
		this._transactions = transactions;
		
		init();
	}

	/**
	 * @return Iterator over a sorted temporary copy of the itemset list of the code table
	 */
	public Iterator<Itemset> codeIterator() {
		return _codes.iterator();
	}
	
	public ItemsetSet getCodes() {
		return this._codes;
	}
	
	/**
	 * Create new indices for new codes, put the usage of each code to 0
	 */
	private void initCodes() {
		this._codes.forEach(new Consumer<Itemset>() {
			@Override
			public void accept(Itemset code) {
				if(_itemsetCode.get(code) == null) {
					_itemsetCode.put(code, AttributeIndex.getAttributeNumber());
				}
				if(_itemsetUsage.get(code) == null) {
					_itemsetUsage.put(code, 0);
				}
			}
		});
	}
	
	public int getUsage(Itemset is) {
		if(this._itemsetUsage.get(is) == null) {
			return 0;
		}
		return this._itemsetUsage.get(is);
	}
	
	public Integer getCodeIndice(Itemset is) {
		return this._itemsetCode.get(is);
	}
	
	public double probabilisticDistrib(Itemset code) {
		return (double) this.getUsage(code) / (double) this._usageTotal;
	}
	
	/**
	 * L(code_CT(X))
	 * @param code
	 * @return
	 */
	public double codeLengthOfcode(Itemset code) {
		return - Math.log(this.probabilisticDistrib(code));
	}
	
	/**
	 * L(t | CT)  [Dirty version]
	 * @param transaction
	 * @return
	 */
	public double encodedTransactionCodeLength(Itemset transaction) {
		double result = 0.0;
		Iterator<Itemset> itCodes = this.codeIterator();
		while(itCodes.hasNext()) {
			Itemset code = itCodes.next();
			if(isCover(transaction, code)) {
				result += codeLengthOfcode(code);
			}
		}
		return result;
	}
	
	/**
	 * L(D | CT)
	 * @return
	 */
	public double encodedTransactionSetCodeLength() {
		double result = 0.0;
		Iterator<Itemset> itTrans = this._transactions.iterator();
		while(itTrans.hasNext()) {
			Itemset trans = itTrans.next();
			
			result += this.encodedTransactionCodeLength(trans);
		}
		
		return result;
	}
	
	/**
	 * L(CT|D)
	 * @return
	 */
	public double codeTableCodeLength() {
		double result = 0.0;
		Iterator<Itemset> itCodes = this.codeIterator();
		while(itCodes.hasNext()) {
			Itemset code = itCodes.next();
			if(this.getUsage(code) != 0.0) {
				double cL = codeLengthOfcode(code);
				double stcL = 0 ;
				if(code.size() == 1 && ! this._standardFlag) {
					stcL = this._standardCT.codeLengthOfcode(code);
				}
				result += cL + stcL;
			}
		}
		return result;
	}
	
	/**
	 * L(D, CT)
	 * @return
	 */
	public double totalCompressedSize() {
		double ctL = codeTableCodeLength();
		double teL = encodedTransactionSetCodeLength();
//		logger.debug("CodeTable Length: " + ctL + " transactionLength: " + teL);
		return ctL + teL;
	}

	/**
	 * Add the singletons of all items to the code table 
	 */
	private void initializeSingletons() {
		Iterator<RDFPatternComponent> itComp = _index.patternComponentIterator();
		while(itComp.hasNext()) {
			RDFPatternComponent compo = itComp.next();
			
			int compoItem = _index.getItem(compo);
			Itemset single = new Itemset(compoItem);
			single.setAbsoluteSupport(_index.getAttributeCount(compo));
			if(! this._codes.contains(single)) {
				_itemsetUsage.put(single, _index.getAttributeCount(compo));
				_itemsetCode.put(single, compoItem);
				this._codes.addItemset(single);
			}
		}
	}
	
	/**
	 * Initialize the usage of each code according to the cover
	 */
	public void countUsages() {
		Iterator<Itemset> itCodes = this.codeIterator();
		while(itCodes.hasNext()) {
			Itemset code = itCodes.next();
			
			_transactions.forEach(new Consumer<Itemset>(){
				@Override
				public void accept(Itemset trans) {
//					if(trans.size() > 1) {
						if(isCover(trans, code)) {
							_itemsetUsage.replace(code, _itemsetUsage.get(code) +1);
						}
//					}
				}
			});
			
			this._usageTotal += _itemsetUsage.get(code);
		}
	}
	
	/**
	 * Comparator to sort the code list
	 */
	public static Comparator<Itemset> standardCoverOrderComparator = new Comparator<Itemset>() {
		@Override
		public int compare(Itemset o1, Itemset o2) {
			if(o1.size() != o2.size()) {
				return - Integer.compare(o1.size(), o2.size());
			} else if(o1.support != o2.support) {
				return - Integer.compare(o1.support, o2.support);
			} else if( ! o1.isEqualTo(o2)) {
				for(int i = 0 ; i < o1.size() ; i++) {
					if(o1.get(i) != o2.get(i)) {
						return Integer.compare(o1.get(i), o2.get(i));
					}
				}
			}
			return 0;
		}
	};
	
	/**
	 * fast check for basic conditions to be a cover of a transaction
	 * @param trans transaction
	 * @param code code
	 * @return true if code is smaller or equal and contained in the transaction
	 */
	private boolean isCoverCandidate(Itemset trans, Itemset code) {
		return ( code.size() <= trans.size()  && ( trans.containsAll(code)));
	}
	
	/**
	 * 
	 * @param trans transaction
	 * @param code code from the codetable
	 * @return true if the code is part of the transaction cover
	 */
	public boolean isCover(Itemset trans, Itemset code) {
		if(isCoverCandidate(trans, code)) {
			Iterator<Itemset> itIs = codeIterator();
			Itemset tmpCode = null;
			while(itIs.hasNext()) {
				tmpCode = itIs.next();
				
				if(isCoverCandidate(trans, tmpCode)) { // If the size of code is correct and it is contained in trans
					if(tmpCode.isEqualTo(code)) { // if code cover = OK
						return true;
					} else if(trans.isEqualTo(tmpCode)) { // if another cover code cover everything = !OK
						return false;
					}else if (tmpCode.intersection(code).size() != 0) { // if another cover code overlap with code = !OK
						return false;
					} else { // transaction partially covered but there is still some chances
						Itemset covered = CodeTable.itemsetAddition(trans, createCodeSingleton(getCodeIndice(tmpCode)));
						covered = CodeTable.itemsetSubstraction(covered, tmpCode);
						return isCover(covered, code); 
					}
				}
			}
			
		}
		return false;
	}
	
	public void removeCode(Itemset code) {
		this._codes.remove(code);
		this._itemsetCode.remove(code);
		this._itemsetUsage.remove(code);
		
		countUsages(); // Have to maintain the thing up to date ? 
	}
	
	public boolean contains(Itemset code) {
		return this._codes.contains(code);
	}
	
	/**
	 * Supposed to be a new code
	 * @param code
	 */
	public void addCode(Itemset code) {
		this.addCode(code, AttributeIndex.getAttributeNumber());
	}
	
	/**
	 * Add a code and its already existing indice
	 * @param code
	 * @param indice
	 */
	public void addCode(Itemset code, int indice) {
		this._codes.add(code);
		this._itemsetCode.put(code, indice);
		this._itemsetUsage.put(code, this.getUsage(code));

		Collections.sort(_codes, standardCoverOrderComparator);
		this.countUsages(); // maintain the usage index uptodate ?
	}
	
	public static Itemset itemsetAddition(Itemset iSet, Itemset added) {
		TreeSet<Integer> tmpBaseSet = new TreeSet<Integer>();
		for(int i = 0; i < iSet.getItems().length; i++) {
			tmpBaseSet.add(iSet.get(i));
		}
		TreeSet<Integer> tmpAddedSet = new TreeSet<Integer>();
		for(int i = 0; i < added.getItems().length; i++) {
			tmpAddedSet.add(added.get(i));
		}
		tmpBaseSet.addAll(tmpAddedSet);
		
		return new Itemset(new ArrayList<Integer>(tmpBaseSet), iSet.getAbsoluteSupport());
	}
	
	public static Itemset itemsetSubstraction(Itemset iSet, Itemset substracted) {
		TreeSet<Integer> tmpBaseSet = new TreeSet<Integer>();
		for(int i = 0; i < iSet.getItems().length; i++) {
			tmpBaseSet.add(iSet.get(i));
		}
		TreeSet<Integer> tmpSubstractedSet = new TreeSet<Integer>();
		for(int i = 0; i < substracted.getItems().length; i++) {
			tmpSubstractedSet.add(substracted.get(i));
		}
		tmpBaseSet.removeAll(tmpSubstractedSet);
		
		return new Itemset(new ArrayList<Integer>(tmpBaseSet), iSet.getAbsoluteSupport());
	}
	
	public static Itemset createCodeSingleton(int codeNum) {
		return new Itemset(codeNum);
	}
	
	public String toString() {

		// StringBuilder copied from smpf code, just to see ...
		StringBuilder r = new StringBuilder ();
		Iterator<Itemset> itIs = this.codeIterator();
		while(itIs.hasNext()) {
			Itemset is = itIs.next();
			r.append(is.toString());
			r.append(' ');
			r.append(is.getAbsoluteSupport());
			r.append(' ');
			r.append(this.getUsage(is));
			r.append(' ');
			r.append(this.probabilisticDistrib(is));
			r.append(' ');
			r.append(this.codeLengthOfcode(is));
			r.append('\n');
		}
		
		return r.toString();
	}
	
}