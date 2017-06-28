package com.irisa.swpatterns.krimp;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.apache.log4j.Logger;
import com.irisa.swpatterns.data.AttributeIndex;
import com.irisa.swpatterns.data.ItemsetSet;
import com.irisa.swpatterns.exception.LogicException;

import ca.pfv.spmf.patterns.itemset_array_integers_with_count.Itemset;

/**
 * @author pmaillot
 *
 */
public class CodeTable {

	private static Logger logger = Logger.getLogger(CodeTable.class);

	//	private AttributeIndex _index = null;
	private HashMap<Integer, Integer> _supports = new HashMap<Integer, Integer>();
	private ItemsetSet _transactions = null;
	private ItemsetSet _codes = null;
	private HashMap<Itemset, Integer> _itemsetUsage = new HashMap<Itemset, Integer>();
	private HashMap<Itemset, Integer> _itemsetCode = new HashMap<Itemset, Integer>();
	private long _usageTotal = 0;
	private HashMap<Itemset, BitSet> _codeSupportMap = new HashMap<Itemset, BitSet>();
	private HashMap<Itemset, BitSet> _codeBitSetMap = new HashMap<Itemset, BitSet>();
	private HashMap<Itemset, BitSet> _codeUsageMap = new HashMap<Itemset, BitSet>();

	private boolean _standardFlag = false; // Set true if it is the standard codetable
	private CodeTable _standardCT = null; // Codetable containing only singletons for the coding length of a CT
	
	private boolean _useVector = true;

	/**
	 * Initialization of the usages and codes indices
	 * @param index
	 * @param transactions
	 * @param codes
	 */
	public CodeTable(ItemsetSet transactions, ItemsetSet codes) {
		this(transactions, codes, false);
	}

	protected CodeTable(ItemsetSet transactions, ItemsetSet codes, boolean standardFlag) {
		_transactions = transactions;
		if(codes == null) {
			_codes = new ItemsetSet();
		} else {
			_codes = new ItemsetSet(codes);
		}
		_standardFlag = standardFlag;

		if(codes != null) {
			_standardCT = CodeTable.createStandardCodeTable(/*index,*/ transactions);
		} else { // this is a standard codetable
			_standardCT = null;
			_codes = new ItemsetSet();
		}
		init();	
	}

	public static CodeTable createStandardCodeTable(ItemsetSet transactions) {
		return new CodeTable(transactions, null, true);
	}

	public CodeTable(CodeTable ct) {
		_supports = new HashMap<Integer, Integer>(ct._supports);
		_transactions = new ItemsetSet(ct._transactions);
		_codes = new ItemsetSet(ct._codes);
		_itemsetUsage = new HashMap<Itemset, Integer>(ct._itemsetUsage);
		_itemsetCode = new HashMap<Itemset, Integer>(ct._itemsetCode);
		_usageTotal = ct._usageTotal;
		_standardFlag = ct._standardFlag;
		_codeSupportMap = new HashMap<Itemset, BitSet>(ct._codeSupportMap);
		_codeUsageMap = new HashMap<Itemset, BitSet>(ct._codeUsageMap);
		_codeBitSetMap = new HashMap<Itemset, BitSet>(ct._codeBitSetMap);

		_standardCT = ct._standardCT;
	}

	private void init() {
		initSupportVectors();
		initSupports();
		initializeSingletons();
		initCodes();
		countUsages();
	}

	/**
	 * Fill the support vectors according to the transactions
	 */
	private void initSupportVectors() {
		//		logger.debug("init support vectors");
		// Init singletons
		for(int iTrans = 0; iTrans < this._transactions.size(); iTrans++) {
			for(int iItem = 0; iItem < this._transactions.get(iTrans).size() ; iItem++) {
				int item = this._transactions.get(iTrans).get(iItem);
				Itemset itemcode = createCodeSingleton(item);
				if(this._codeSupportMap.get(itemcode) == null) {
					this._codeSupportMap.put(itemcode, new BitSet(this._transactions.size()));
				}
				this._codeSupportMap.get(itemcode).set(iTrans);
				this._codeBitSetMap.put(itemcode, new BitSet());
				this._codeBitSetMap.get(itemcode).set(item);
			}
		}

		//		logger.debug("init support vectors for codes");
		// Init big codes
		for(int iCode = 0; iCode < this._codes.size(); iCode++) {
			Itemset code = this._codes.get(iCode);
			initBitSetForCodes(code);
		}
		
	}

	/**
	 * fill the support vector of one code > 1 by doing an "and" of the support vector of each item in the code
	 * @param code
	 */
	private void initBitSetForCodes(Itemset code) {
		if(code.size() > 1) {
			// Recensing all transactions having at least one item in common
			BitSet commonTrans = new BitSet(this._transactions.size());
			commonTrans.set(0, commonTrans.size()-1);
			for(int iCodeItem = 0; iCodeItem < code.size(); iCodeItem++ ) {
				Itemset codeItemSingleton = createCodeSingleton(code.get(iCodeItem));
				commonTrans.and(this._codeSupportMap.get(codeItemSingleton)); // Adding all potential transaction which have one item in common
			}
			this._codeSupportMap.put(code, commonTrans);
			this._codeBitSetMap.put(code, this.itemSetToBItSet(code));
		}
	}

	public ItemsetSet getTransactions() {
		return _transactions;
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
		ItemsetSet tmpCodes = new ItemsetSet(this._codes);
		Collections.sort(tmpCodes, standardCandidateOrderComparator);
		return tmpCodes.iterator();
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
				if(_codeSupportMap.get(code) == null) {
					_codeSupportMap.put(code, new BitSet(_transactions.size()));
				}
			}
		});
	}

	private void initSupports() {
		//		for(int iTrans = 0; iTrans < this._transactions.size(); iTrans++) {
		//			Itemset trans = this._transactions.get(iTrans);
		//			for(int i = 0; i < trans.size() ; i++) {
		//				int item = trans.get(i);
		//				if(_supports.get(item) == null) {
		//					_supports.put(item, 0);
		//				}
		//				_supports.replace(item, _supports.get(item) + 1);
		//				
		//			}
		//		}

		// Don't have to iterate over transactions anymore !
		Iterator<Itemset> itCode = this._codeSupportMap.keySet().iterator();
		while(itCode.hasNext()) {
			Itemset code = itCode.next();
			if(code.size() == 1) {
				this._supports.put(code.getLastItem(), this._codeSupportMap.get(code).cardinality());
			}
		}
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
	 * @throws LogicException 
	 */
	public double encodedTransactionCodeLength(Itemset transaction) throws LogicException {
		double result = 0.0;
		Iterator<Itemset> itCodes = this.codeIterator();
		while(itCodes.hasNext()) {
			Itemset code = itCodes.next();
			if(this.getUsage(code) > 0 && isCover(transaction, code) ) {
				result += codeLengthOfcode(code);
			}
		}
		return result;
	}

	/**
	 * L(t | CT)  [Vector version]
	 * @param transaction
	 * @return
	 * @throws LogicException 
	 */
	public double encodedTransactionCodeLengthWithVectors(int transactionIndex) throws LogicException {
		double result = 0.0;
		Iterator<Itemset> itCodes = this.codeIterator();
		while(itCodes.hasNext()) {
			Itemset code = itCodes.next();
			if(this.getUsage(code) > 0 && this._codeUsageMap.get(code).get(transactionIndex)) {
				result += codeLengthOfcode(code);
			}
		}
		return result;
	}

	/**
	 * L(D | CT)
	 * @return
	 * @throws LogicException 
	 */
	public double encodedTransactionSetCodeLength() throws LogicException {
		double result = 0.0;
		Iterator<Itemset> itTrans = this._transactions.iterator();
		while(itTrans.hasNext()) {
			Itemset trans = itTrans.next();

			result += this.encodedTransactionCodeLength(trans);
		}

		return result;
	}

	/**
	 * L(D | CT)
	 * @return
	 * @throws LogicException 
	 */
	public double encodedTransactionSetCodeLengthWithVectors() throws LogicException {
		double result = 0.0;
		for(int iTrans = 0; iTrans < this._transactions.size(); iTrans++) {
			result += this.encodedTransactionCodeLengthWithVectors(iTrans);
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
				// CB: this is the code length according to the CT
				double cL = codeLengthOfcode(code);

				// CB: we also need the code length according to the ST: we codify the codeusing it
				double stcL = 0 ;
				if (!_standardFlag) {
					stcL = this._standardCT.codeLengthOfCodeAccordingST(code);
				}
				// else => it is a 0.0

				//				if(code.size() == 1 && ! this._standardFlag) {
				//					stcL = this._standardCT.codeLengthOfcode(code);
				//				} else if(this._standardFlag) {
				//					stcL = cL;
				//				}

				result += cL + stcL;
			}
		}
		return result;
	}

	/** 
	 * L(code_ST(X))
	 */

	public double codeLengthOfCodeAccordingST(Itemset code) {
		double result = 0.0;
		// this method should return 0 if the codetable is not the ST
		if (!_standardFlag) {
			for (int i=0; i<code.size(); i++) {
				result+= this._standardCT.codeLengthOfCodeAccordingST(new Itemset(code.get(i))); 
			}
		}
		return result; 
	}

	/**
	 * L(D, CT)
	 * @return
	 * @throws LogicException 
	 */
	public double totalCompressedSize() throws LogicException {
		if(this._useVector) {
			return totalCompressedSizeWithVectors();
		}
		double ctL = codeTableCodeLength();
		double teL = encodedTransactionSetCodeLength();
//		logger.debug("CodeTable Length: " + ctL + " transactionLength: " + teL);
		return ctL + teL;
	}

	/**
	 * L(D, CT)
	 * @return
	 * @throws LogicException 
	 */
	private double totalCompressedSizeWithVectors() throws LogicException {
		logger.debug("totalCompressedSizeWithVectors ");
		double ctL = codeTableCodeLength();
		double telVec = encodedTransactionSetCodeLengthWithVectors();
		logger.debug("totalCompressedSizeWithVectors, CodeTable Length: " + ctL + " transactionLength: " + telVec);
		return ctL + telVec;
	}

	/**
	 * Add the singletons of all items to the code table 
	 */
	private void initializeSingletons() {
		Iterator<Integer> itItems = _supports.keySet().iterator();
		while(itItems.hasNext()) {
			Integer item = itItems.next();

			Itemset single = new Itemset(item);
			single.setAbsoluteSupport(_supports.get(item));
			if(this._codes.contains(single)) {
				this._codes.removeFirstOccurrence(single);
				_itemsetUsage.remove(single);
				_itemsetCode.remove(single);
			}
			_itemsetUsage.put(single, single.getAbsoluteSupport());
			_itemsetCode.put(single, item);
			this._codes.addItemset(single);
		}
	}

	/**
	 * Initialize the usage of each code according to the cover
	 */
	protected void countUsages() {
		logger.debug("countUsages");
		this._usageTotal = 0;
		Collections.sort(this._codes, CodeTable.standardCoverOrderComparator);

		Iterator<Itemset> itCodes = this.codeIterator();
		while(itCodes.hasNext()) {
			Itemset code = itCodes.next();

			_itemsetUsage.replace(code, 0);
			this._codeUsageMap.put(code, new BitSet(this._transactions.size()));

			int iTrans = 0;
			while(this._codeSupportMap.get(code).nextSetBit(iTrans) > -1) {
				if(isCoverWithVectors(iTrans, code)) {
					_itemsetUsage.replace(code, _itemsetUsage.get(code) +1);
					_codeUsageMap.get(code).set(iTrans);
				}
				iTrans = this._codeSupportMap.get(code).nextSetBit(iTrans)+1;
			}

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
	 * Comparator to sort the code list
	 */
	public static Comparator<Itemset> standardCandidateOrderComparator = new Comparator<Itemset>() {
		@Override
		public int compare(Itemset o1, Itemset o2) {
			if(o1.support != o2.support) {
				return - Integer.compare(o1.support, o2.support);
			} else if(o1.size() != o2.size()) {
				return - Integer.compare(o1.size(), o2.size());
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
					} else if (tmpCode.intersection(code).size() != 0) { // if another cover code overlap with code = !OK
						return false;
					} else { // transaction partially covered but there is still some chances
						Itemset covered = CodeTable.itemsetSubstraction(trans, tmpCode);
						return isCover(covered, code); 
					}
				}
			}

		}
		return false;
	}

	/**
	 * With vectors
	 * @param trans transaction
	 * @param code code from the codetable
	 * @return true if the code is part of the transaction cover
	 */
	private boolean isCoverWithVectors(int transIndex, Itemset code) {
//		logger.debug("isCover( " + transIndex + " , " + code + " )");
		if(isCoverCandidateWithVectors(transIndex, code)) {
			Iterator<Itemset> itCode = codeIterator();
			return isCoverWithVectors(transIndex, itemSetToBItSet(code), itCode, new BitSet(this._supports.size()));
		}
		return false;
	}
	
	private boolean isCoverWithVectors(int transIndex, BitSet codeBS, Iterator<Itemset> itCode, BitSet codeMask) {
		Itemset tmpCode = null;
		while(itCode.hasNext()) {
			tmpCode = itCode.next();
			BitSet tmpCodeBS = this.itemSetToBItSet(tmpCode);
//			logger.debug("isCover( " + transIndex + " , " + codeBS + " , " + codeMask + " ) " + tmpCode);
			if(isCoverCandidateWithVectors(transIndex, tmpCode)) { // If the size of code is correct and it is contained in trans
				if(tmpCodeBS.equals(codeBS)) { // if code cover = OK
					return true;
				} else if (tmpCodeBS.intersects(codeBS)) { // if another cover code overlap with code = !OK
					return false;
				} else if(codeMask.intersects(tmpCodeBS)) { // Can't have to overlapping codes in the same transaction cover
					continue;
				} else { // transaction partially covered but there is still some chances
					codeMask.or(tmpCodeBS); // tmpCode is cover
					return isCoverWithVectors(transIndex, codeBS, itCode, codeMask); 
				}
			}
		}
		return false;
	}

	/**
	 * fast check for basic conditions to be a cover of a transaction (With vector)
	 * @param trans transaction
	 * @param code code
	 * @return true if code is smaller or equal and contained in the transaction
	 */
	private boolean isCoverCandidateWithVectors(int transIndex, Itemset code) {
		return this._codeSupportMap.get(code).get(transIndex);
	}
	
	private BitSet itemSetToBItSet(Itemset code) {
		BitSet codesBitSet = new BitSet(this._supports.size());
		for(int i = 0; i < code.size(); i++) {
			codesBitSet.set(code.get(i));
		}
		return codesBitSet;
	}

	public void removeCode(Itemset code) {
		this._codes.remove(code);
		this._itemsetCode.remove(code);
		this._itemsetUsage.remove(code);
		this._codeBitSetMap.remove(code);
		this._codeSupportMap.remove(code);
		this._codeUsageMap.remove(code);

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
		if(! this._codes.contains(code)) {
			this._codes.add(code);
			this._itemsetCode.put(code, indice);
			this._itemsetUsage.put(code, this.getUsage(code));
			this._codeSupportMap.put(code, new BitSet(this._transactions.size()));

			this.initBitSetForCodes(code);
			this.countUsages(); // maintain the usage index uptodate ?
		}
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
		Collections.sort(this._codes, new Comparator<Itemset>(){
			@Override
			public int compare(Itemset o1, Itemset o2) {
				return - Integer.compare(o1.size(), o2.size());
			}
		});

		// StringBuilder copied from smpf code, just to see ...
		StringBuilder r = new StringBuilder ();
		r.append("Total Usages: ");
		r.append(this._usageTotal);
		r.append('\n');
		Iterator<Itemset> itIs = this._codes.iterator();
		while(itIs.hasNext()) {
			Itemset is = itIs.next();
			r.append('[');
			r.append(is.toString());
			r.append(']');
			r.append(" u:");
			r.append(this.getUsage(is));
			r.append(" P:");
			r.append(this.probabilisticDistrib(is));
			r.append(" L:");
			r.append(this.codeLengthOfcode(is));
			r.append('\n');
		}

		Collections.sort(this._codes, CodeTable.standardCoverOrderComparator);
		return r.toString();
	}

	public boolean useVector() {
		return _useVector;
	}

	public void setUseVector(boolean _useVector) {
		this._useVector = _useVector;
	}

}
