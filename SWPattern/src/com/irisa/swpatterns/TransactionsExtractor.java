package com.irisa.swpatterns;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.function.Consumer;

import org.apache.commons.math3.stat.descriptive.AggregateSummaryStatistics;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.log4j.Logger;

import com.irisa.jenautils.BaseRDF;
import com.irisa.jenautils.CustomQuerySolution;
import com.irisa.jenautils.QueryResultIterator;
import com.irisa.jenautils.UtilOntology;
import com.irisa.swpatterns.data.AttributeIndex;
import com.irisa.swpatterns.data.RDFPatternComponent;
import com.irisa.swpatterns.data.RDFPatternPathFragment;
import com.irisa.swpatterns.data.RDFPatternResource;
import com.irisa.swpatterns.data.RDFPatternValuePath;
import com.irisa.swpatterns.data.LabeledTransaction;
import com.irisa.swpatterns.data.RankUpQuery;
import com.irisa.swpatterns.data.LabeledTransactions;
import com.irisa.swpatterns.data.RDFPatternComponent.Type;
import com.irisa.swpatterns.data.RDFPatternElement;

/**
 * Deal with the transaction extraction. Give access to the patternComponent index for later compressions (Good idea ?).
 * 
 * @author pmaillot
 *
 */
public class TransactionsExtractor {

	private static int queryLimit = 0;
	
	private AttributeIndex _index = AttributeIndex.getInstance();
	private HashMap<Resource, Integer> _inDegreeCount = new HashMap<Resource, Integer>();
	private HashMap<Resource, Integer> _outDegreeCount = new HashMap<Resource, Integer>();
	private HashSet<Resource> _outliers = new HashSet<Resource>();
	private HashSet<Resource> _individuals = new HashSet<Resource>();
	private HashSet<Resource> _ignoredProperties = new HashSet<Resource>();

	private boolean _noTypeBool = false;
	private boolean _noInBool = false;
	private boolean _noOutBool = false;	

	public enum Neighborhood {
		Property,
		PropertyAndType,
		PropertyAndOther,
		PropertyAndValue
	}
	private Neighborhood _neighborLevel = Neighborhood.PropertyAndValue;
	
	private int _pathsLength = 0;
	
	private static Logger logger = Logger.getLogger(TransactionsExtractor.class);
	
	public static int getQueryLimit() {
		return queryLimit;
	}

	public static void setQueryLimit(int queryLimit) {
		TransactionsExtractor.queryLimit = queryLimit;
	}

	public AttributeIndex getIndex() {
		return _index;
	}

	public void setIndex(AttributeIndex index) {
		this._index = index;
	}
	
	public void addIgnoredProperty(Resource prop) {
		this._ignoredProperties.add(prop);
	}
	
	public void removeIgnoredProperty(Resource prop) {
		this._ignoredProperties.remove(prop);
	}
	
	public HashSet<Resource> ignoredProperties() {
		return this._ignoredProperties;
	}

	/**
	 * Neighbor properties transactions: type and outgoing/ingoing properties.
	 * For each class, if limit is > 0, only take into account the limited amount of individual
	 * @param baseRDF
	 * @param onto
	 * @return
	 */
	public LabeledTransactions extractTransactions(BaseRDF baseRDF, UtilOntology onto) {
		
		if(this.getNeighborLevel() == Neighborhood.PropertyAndOther) {
			initDegreeCount( baseRDF, onto);
		}
		
		logger.debug("Transaction extraction");

		// Aggregation of the individual descriptions
		LabeledTransactions results = new LabeledTransactions();
		Iterator<Resource> itClass = onto.usedClassIterator();
		while(itClass.hasNext()) 
		{
			Resource currentClass = itClass.next();
			results.addAll(extractTransactionsForClass(baseRDF, onto, currentClass));
		}

		logger.debug("End of transaction extraction");
		logger.debug(_index.size() + " attributes");
//
		logger.debug(results.size() + " lines");
		return results;
	}
	
	private void initDegreeCount(BaseRDF baseRDF, UtilOntology onto) {
		HashSet<Resource> instances = new HashSet<Resource>();
		Iterator<Resource> itClass = onto.usedClassIterator();
		while(itClass.hasNext()) {
			Resource currentClass = itClass.next();
			QueryResultIterator itInstances = new QueryResultIterator("SELECT DISTINCT ?i WHERE { ?i a <"+ currentClass.getURI() +"> }", baseRDF);
			itInstances.forEachRemaining(new Consumer<CustomQuerySolution>() {

				@Override
				public void accept(CustomQuerySolution sol) {
					instances.add(sol.getResource("i"));
				}
			});
		}

		DescriptiveStatistics summaryInDegree = new DescriptiveStatistics();
		instances.forEach(new Consumer<Resource>() {
			@Override
			public void accept(Resource res) {
				String inDegreeQuery = "SELECT DISTINCT (count(*) AS ?count) WHERE { ?subj ?prop <"+ res.getURI() +"> }";
				QueryResultIterator inDegreeiterator = new QueryResultIterator(inDegreeQuery, baseRDF);
				int degree = inDegreeiterator.next().get("count").asLiteral().getInt();
				_inDegreeCount.put(res, degree);
				summaryInDegree.addValue(degree);
			}
		});
		
		double q1In = summaryInDegree.getPercentile(25);
		double q3In = summaryInDegree.getPercentile(75);
		double outlierInDegreeThreshold = q3In + (1.5 * (q3In -q1In));
		logger.debug(summaryInDegree.getN() +" values q1: " + q1In + " q3: " + q3In + " In Outlier Threshold is: " + outlierInDegreeThreshold);
		
		instances.forEach(new Consumer<Resource>() {
			@Override
			public void accept(Resource res) {
				if((double)_inDegreeCount.get(res) > outlierInDegreeThreshold) {
					_outliers.add(res);
				}
			}
		});

		DescriptiveStatistics summaryOutDegree = new DescriptiveStatistics();
		instances.forEach(new Consumer<Resource>() {
			@Override
			public void accept(Resource res) {
				String outDegreeQuery = "SELECT DISTINCT (count(*) AS ?count) WHERE { <"+ res.getURI() +"> ?prop ?obj }";
				QueryResultIterator outDegreeiterator = new QueryResultIterator(outDegreeQuery, baseRDF);
				int degree = outDegreeiterator.next().get("count").asLiteral().getInt();
				_outDegreeCount.put(res, degree);
				summaryOutDegree.addValue(degree);
			}
		});
		
		double q1Out = summaryOutDegree.getPercentile(25);
		double q3Out = summaryOutDegree.getPercentile(75);
		double outlierOutDegreeThreshold = q3Out + (1.5 * (q3Out -q1Out));
		logger.debug(summaryOutDegree.getN() +" values q1: " + q1Out + " q3: " + q3Out + " Out Outlier Threshold is: " + outlierOutDegreeThreshold);
		
		instances.forEach(new Consumer<Resource>() {
			@Override
			public void accept(Resource res) {
				if((double)_outDegreeCount.get(res) > outlierOutDegreeThreshold) {
					_outliers.add(res);
				}
			}
		});
		logger.debug(instances.size() + " instances et " + _outliers.size() + " outliers.");
	}

	private LabeledTransaction extractTypeAttributeForIndividual(BaseRDF baseRDF, UtilOntology onto, Resource currIndiv) {
		LabeledTransaction indivResult = new LabeledTransaction();
		String typeTripQueryString = "SELECT DISTINCT ?t WHERE { <" + currIndiv + "> a ?t }";
		QueryResultIterator itTypeResult = new QueryResultIterator(typeTripQueryString, baseRDF);
		try {
			while(itTypeResult.hasNext()) {
				CustomQuerySolution queryResultLine = itTypeResult.nextAnswerSet();
				Resource indiType = queryResultLine.getResource("t");
				RDFPatternResource attribute = new RDFPatternResource(indiType, RDFPatternResource.Type.TYPE );

				if(onto.isClass(indiType) && ! onto.isOntologyClassVocabulary(indiType)) {
					_index.add(attribute);
					indivResult.add(attribute);
				}
			}
		} catch(HttpException e) {

		} finally {
			itTypeResult.close();
		}
		return indivResult;
	}
	
	private LabeledTransaction extractOutPropertyAttributeForIndividual(BaseRDF baseRDF, UtilOntology onto, Resource currIndiv) {

		LabeledTransaction indivResult = new LabeledTransaction();
		boolean blankNodeToExplore = false; // There are nested BN to be translated as property chains "We have to go deeper !"
		
		String outTripQueryString = "SELECT DISTINCT ?p "; 
		if(this.getNeighborLevel() == Neighborhood.PropertyAndType || this.getNeighborLevel() == Neighborhood.PropertyAndOther) {
			outTripQueryString += " ?ot " ;
		}
		if(this.getNeighborLevel() == Neighborhood.PropertyAndOther || this.getNeighborLevel() == Neighborhood.PropertyAndValue) {
			outTripQueryString += " ?o " ;
		}
		outTripQueryString += " WHERE { <" + currIndiv + "> ?p ?o . ";
		if(this.getNeighborLevel() == Neighborhood.PropertyAndType || this.getNeighborLevel() == Neighborhood.PropertyAndOther) {
			outTripQueryString += " OPTIONAL { ?o a ?ot . } ";
		}
		outTripQueryString += " }";
		QueryResultIterator itOutResult = new QueryResultIterator(outTripQueryString, baseRDF);
		try {
			while(itOutResult.hasNext()) {
				CustomQuerySolution queryResultLine = itOutResult.nextAnswerSet();
				Resource prop = queryResultLine.getResource("p");
				if(prop != null && ! onto.isOntologyPropertyVocabulary(prop)) {
					
					RDFPatternComponent propAttribute = new RDFPatternResource(prop, RDFPatternResource.Type.OUT_PROPERTY );
					if(! _index.contains(propAttribute)) {
						_index.add(propAttribute);
					}
					indivResult.add(propAttribute);
				
					if(this.getNeighborLevel()== Neighborhood.PropertyAndOther 
							|| this.getNeighborLevel() == Neighborhood.PropertyAndValue
							&& queryResultLine.getResource("o") != null) {
						Resource obj = queryResultLine.getResource("o");
						if(! onto.isOntologyClassVocabulary(obj)) {
							if(this._outliers.contains(obj)) {
								RDFPatternComponent attributeOther = new RDFPatternResource(prop, RDFPatternResource.Type.OUT_NEIGHBOUR );
								if(! _index.contains(attributeOther)) {
									_index.add(attributeOther);
								}
								indivResult.add(attributeOther);
							} else if (! obj.isAnon()) {
								ArrayList<RDFNode> list = new ArrayList<RDFNode>();
								list.add(prop);
								list.add(obj);
								RDFPatternComponent attributeOther = new RDFPatternValuePath(list, RDFPatternResource.Type.OUT_VALUE );
								if(! _index.contains(attributeOther)) {
									_index.add(attributeOther);
								}
								indivResult.add(attributeOther);
							} else {
								blankNodeToExplore = true;
							}
						}
					} 
					if((this.getNeighborLevel() == Neighborhood.PropertyAndType 
							|| this.getNeighborLevel() == Neighborhood.PropertyAndOther)
							&& queryResultLine.getResource("ot") != null ) {
						Resource oType = queryResultLine.getResource("ot");
						if(! onto.isOntologyClassVocabulary(oType) 
								&& onto.isClass(oType)) {
							RDFPatternComponent attributeType = new RDFPatternPathFragment(prop, oType, RDFPatternResource.Type.OUT_NEIGHBOUR_TYPE );
							if(! _index.contains(attributeType)) {
								_index.add(attributeType);
							}
							indivResult.add(attributeType);
						}
					}
				}
			}
		} catch(HttpException e) {

		} finally {
			itOutResult.close();
		}
		
		return indivResult;
	}
	
	/**
	 * Extract property paths terminated by a literal value starting with the given individual
	 * @param baseRDF
	 * @param onto
	 * @param currIndiv
	 * @return
	 */
	private LabeledTransaction extractPropertyAttributePathForIndividual(BaseRDF baseRDF, UtilOntology onto, Resource currIndiv) {
		LabeledTransaction result = new LabeledTransaction();
		
		String outTripQueryString = "SELECT DISTINCT ?p "; 
		outTripQueryString += " ?o " ;
		outTripQueryString += " WHERE { <" + currIndiv + "> ?p ?o . ";
		outTripQueryString += " } ";
		
		QueryResultIterator itTripQueryResult = new QueryResultIterator(outTripQueryString, baseRDF);
		ArrayList<ArrayList<RDFNode> > valuesPaths = new ArrayList<ArrayList<RDFNode> >();
		HashSet<Resource> nodesSeen = new HashSet<Resource>();
		try {
			while(itTripQueryResult.hasNext()) {
				CustomQuerySolution queryResult = itTripQueryResult.nextAnswerSet();
				Resource prop = queryResult.getResource("p");
				if(! onto.isOntologyPropertyVocabulary(prop) && ! prop.equals(RDFS.label) && ! this._ignoredProperties.contains(prop)) {
					RDFNode obj = queryResult.get("o");
					if(obj != null) {
						if(obj.isResource() && ! onto.isOntologyClassVocabulary((Resource) obj) && ! nodesSeen.contains(obj) && obj.isAnon()) {
							ArrayList<Resource> chain = new ArrayList<Resource>();
							chain.add(prop);
							HashSet<Resource> history = new HashSet<Resource>();
							history.add(currIndiv);
							valuesPaths.addAll(extractPropertyValueChain(baseRDF, onto, currIndiv, chain, history));
						}else if(obj.isLiteral()) {
							ArrayList<RDFNode> shortChain = new ArrayList<RDFNode>();
							shortChain.add(prop);
							shortChain.add(obj);
							valuesPaths.add(shortChain);
						}
					}
				}
			}
		} finally {
			itTripQueryResult.close();
		}
		
		
		Iterator<ArrayList<RDFNode>> itChain = valuesPaths.iterator();
		while(itChain.hasNext()) {
			ArrayList<RDFNode> chain = itChain.next();
			RDFPatternValuePath newRDFPatternElem = new RDFPatternValuePath(chain, Type.OUT_VALUE);
			if(! _index.contains(newRDFPatternElem)) {
				_index.add(newRDFPatternElem);
			}
			result.add(newRDFPatternElem);
		}
		
		return result;
	}
	
	/**
	 * Récursive function for the property path of length superior to 1
	 * @param baseRDF
	 * @param onto
	 * @param currIndiv starting individual
	 * @param previous encountered properties
	 * @param nodeHistory intermediary blank nodes
	 * @return
	 */
	private ArrayList<ArrayList<RDFNode> > extractPropertyValueChain(BaseRDF baseRDF, UtilOntology onto, Resource currIndiv, ArrayList<? extends Resource> previous, HashSet<Resource> nodeHistory) {
		ArrayList<ArrayList<RDFNode> > result = new ArrayList<ArrayList<RDFNode> >();

		if(! currIndiv.isAnon()) {
			int beforeLastVar = previous.size()-1;
			int lastVar = previous.size();
			String propertyChainQueryStringHead = "SELECT DISTINCT ?p ?o"+lastVar +" ";
			String propertyChainQueryStringBody = " WHERE { <" + currIndiv + "> <"+ previous.get(0).getURI() +"> ?o0 . ";
			for(int i = 1; i < previous.size(); i++) {
				propertyChainQueryStringBody += " ?o" + (i-1) + " <"+previous.get(i) + "> ?o"+ (i) + " . ";
			}
			propertyChainQueryStringBody += " ?o" + beforeLastVar + " ?p ?o"+ lastVar + " . ";
			propertyChainQueryStringBody += " } ";
			String propertyChainQueryString = propertyChainQueryStringHead + propertyChainQueryStringBody;
			logger.debug(propertyChainQueryString);
			QueryResultIterator itPathResult = new QueryResultIterator(propertyChainQueryString, baseRDF);
			try {
				while(itPathResult.hasNext()) {
					CustomQuerySolution queryResultLine = itPathResult.nextAnswerSet();
					Resource prop = queryResultLine.getResource("p");
					if(! onto.isOntologyPropertyVocabulary(prop)) {
						RDFNode obj = queryResultLine.get("o"+lastVar);
						if(obj != null && ! obj.equals(currIndiv) && ! nodeHistory.contains(obj)) {
							logger.debug(queryResultLine);
							if(obj.isAnon()) {
								ArrayList<Resource> newPrevious = new ArrayList<Resource>(previous);
								newPrevious.add(prop);
								nodeHistory.add((Resource) obj);
								result.addAll(extractPropertyValueChain(baseRDF, onto, currIndiv, newPrevious, nodeHistory));
							} else if(obj.isLiteral()) {
								ArrayList<RDFNode> finalPath = new ArrayList<RDFNode>(previous);
								finalPath.add(prop);
								if(this.getNeighborLevel() == Neighborhood.PropertyAndValue) {
									finalPath.add(obj);
								}
								result.add(finalPath);
							}
						}
					}
				}
				
			} finally {
				itPathResult.close();
			}
		}
		
		return result;
	}

	public LabeledTransaction extractTransactionsForIndividual(BaseRDF baseRDF, UtilOntology onto, Resource currIndiv) {
		LabeledTransaction indivResult = new LabeledTransaction();

		if(this.getNeighborLevel() != Neighborhood.PropertyAndValue) {
		
			// QUERY types triples
			if(! _noTypeBool) {
				indivResult.addAll(this.extractTypeAttributeForIndividual(baseRDF, onto, currIndiv));
			}
	
			// QUERY out triples
			if(! _noOutBool) {
				indivResult.addAll(this.extractOutPropertyAttributeForIndividual(baseRDF, onto, currIndiv));
			}
	
			// QUERY in triples
			if(! _noInBool) {
				indivResult.addAll(this.extractInPropertyAttributesForIndividual(baseRDF, onto, currIndiv));
			}
		} else {
			// QUERY property and values path
			indivResult.addAll(extractPropertyAttributePathForIndividual(baseRDF, onto, currIndiv));
		}
		
		
		return indivResult;
	}
	
	public LabeledTransactions extractTransactionsForClass(BaseRDF baseRDF, UtilOntology onto, Resource currentClass) {

//		logger.debug("Current class: " + currentClass);

		LabeledTransactions results = new LabeledTransactions();

		HashSet<Resource> indivSet = new HashSet<Resource>();
		String indivQueryString = "SELECT DISTINCT ?i WHERE { ?i a <" + currentClass + "> . }";
		if(queryLimit > 0) {
			indivQueryString += " LIMIT " + queryLimit;
		}
		QueryResultIterator itIndivQuery = new QueryResultIterator(indivQueryString, baseRDF);
		try {
			while(itIndivQuery.hasNext()) {
				CustomQuerySolution indivSol = itIndivQuery.next();
				indivSet.add(indivSol.getResource("i"));
			}
		} catch(HttpException e) {

		} finally{
			itIndivQuery.close();
		}

		Iterator<Resource> itIndiv = indivSet.iterator();
		while(itIndiv.hasNext()) {
			Resource currIndiv = itIndiv.next();
			if(! this._individuals.contains(currIndiv)) {
				this._individuals.add(currIndiv);
				results.add(extractTransactionsForIndividual(baseRDF, onto, currIndiv));
			}
			
		}
		return results;
	}

	@Deprecated
	public LabeledTransactions extractPathAttributes(BaseRDF baseRDF, UtilOntology onto){
		return extractPathAttributes(baseRDF, onto, this.getPathsLength());
	}
	
	@Deprecated
	private LabeledTransactions extractPathAttributes(BaseRDF baseRDF, UtilOntology onto, int rank) {
		LabeledTransactions result = new LabeledTransactions();
		
		switch (rank) {
		case 1:
			result.addAll(extractPathAttributesRankOne(baseRDF, onto));
			break;
		case 2:
			result.addAll(extractPathAttributesRankTwo(baseRDF, onto));
			break;
		case 3:
			result.addAll(extractPathAttributesRankThree(baseRDF, onto));
			break;
		case 4:
			result.addAll(extractPathAttributesRankFour(baseRDF, onto));
			break;

		default:
			break;
		}
		
		return result;
	}

	@Deprecated
	private Collection<? extends LabeledTransaction> extractPathAttributesRankFour(BaseRDF baseRDF, UtilOntology onto) {
		LinkedList<LabeledTransaction> result = new LinkedList<LabeledTransaction>();
		
		String rankTwoQueryString = "SELECT ?i1 ?i1c ?p1 ?i2 ?i2c ?p2 ?i3 ?i3c ?p3 ?i4 ?i4c ?p4 ?i5 ?i5c WHERE { ";
		rankTwoQueryString += " ?i1 ?p1 ?i2 . ?i2 ?p2 ?i3 . ?i3 ?p3 ?i4 . ?i4 ?p4 ?i5 ";
		rankTwoQueryString += " OPTIONAL { ?i1 a ?i1c . } ";
		rankTwoQueryString += " OPTIONAL { ?i2 a ?i2c . } ";
		rankTwoQueryString += " OPTIONAL { ?i3 a ?i3c . } ";
		rankTwoQueryString += " OPTIONAL { ?i4 a ?i4c . } ";
		rankTwoQueryString += " OPTIONAL { ?i5 a ?i5c . } ";
		rankTwoQueryString += " }";
		
		QueryResultIterator itRank2 = new QueryResultIterator(rankTwoQueryString, baseRDF);
		while(itRank2.hasNext()) {
			CustomQuerySolution sol = itRank2.next();
			Resource i1 = sol.getResource("i1");
			Resource i2 = sol.getResource("i2");
			Resource i3 = sol.getResource("i3");
			Resource i4 = sol.getResource("i4");
			Resource i5 = sol.getResource("i5");
			Resource p1 = sol.getResource("p1");
			Resource p2 = sol.getResource("p2");
			Resource p3 = sol.getResource("p3");
			Resource p4 = sol.getResource("p4");
			Resource i1c = sol.getResource("i1c");
			Resource i2c = sol.getResource("i2c");
			Resource i3c = sol.getResource("i3c");
			Resource i4c = sol.getResource("i4c");
			Resource i5c = sol.getResource("i5c");
			
			// Regroupement des statement pour éliminer les cycles
			HashSet<Resource> setStat1 = new HashSet<Resource>();
			setStat1.add(i1);
			setStat1.add(p1);
			setStat1.add(i2);
			HashSet<Resource> setStat2 = new HashSet<Resource>();
			setStat2.add(i2);
			setStat2.add(p2);
			setStat2.add(i3);
			HashSet<Resource> setStat3 = new HashSet<Resource>();
			setStat3.add(i3);
			setStat3.add(p3);
			setStat3.add(i4);
			HashSet<Resource> setStat4 = new HashSet<Resource>();
			setStat4.add(i4);
			setStat4.add(p4);
			setStat4.add(i5);
			
			if(i1 != null && i2 != null && i3 != null && i4 != null && p1 != null && p2 != null && p3 != null
				&& ! onto.isClass(i1) && ! onto.isClass(i2) && ! onto.isClass(i3) && ! onto.isClass(i4)
				&& ! onto.isOntologyClassVocabulary(i1) && ! onto.isOntologyClassVocabulary(i2) && ! onto.isOntologyClassVocabulary(i3) && ! onto.isOntologyClassVocabulary(i4)
				&& ! onto.isOntologyPropertyVocabulary(p1) && ! onto.isOntologyPropertyVocabulary(p2) && ! onto.isOntologyPropertyVocabulary(p3)
				&& ! setStat1.equals(setStat2) && ! setStat1.equals(setStat3) && ! setStat1.equals(setStat4) && ! setStat2.equals(setStat3) && ! setStat2.equals(setStat4) && ! setStat3.equals(setStat4)
				) {
				LabeledTransaction line = new LabeledTransaction();
				
				RDFPatternResource i1Attr = new RDFPatternResource(i1, RDFPatternComponent.Type.NODE1);
				RDFPatternResource i2Attr = new RDFPatternResource(i2, RDFPatternComponent.Type.NODE2);
				RDFPatternResource i3Attr = new RDFPatternResource(i3, RDFPatternComponent.Type.NODE3);
				RDFPatternResource i4Attr = new RDFPatternResource(i4, RDFPatternComponent.Type.NODE4);
				RDFPatternResource i5Attr = new RDFPatternResource(i5, RDFPatternComponent.Type.NODE5);
				RDFPatternResource p1Attr = new RDFPatternResource(p1, RDFPatternComponent.Type.RELATION1);
				RDFPatternResource p2Attr = new RDFPatternResource(p2, RDFPatternComponent.Type.RELATION2);
				RDFPatternResource p3Attr = new RDFPatternResource(p3, RDFPatternComponent.Type.RELATION3);
				RDFPatternResource p4Attr = new RDFPatternResource(p4, RDFPatternComponent.Type.RELATION4);

				_index.add(i1Attr);
				line.add(i1Attr);

				_index.add(i2Attr);
				line.add(i2Attr);
				

				_index.add(i3Attr);
				line.add(i3Attr);
				

				_index.add(i4Attr);
				line.add(i4Attr);
				

				_index.add(i5Attr);
				line.add(i5Attr);
				

				_index.add(p1Attr);
				line.add(p1Attr);
				

				_index.add(p2Attr);
				line.add(p2Attr);
				

				_index.add(p3Attr);
				line.add(p3Attr);
				

				_index.add(p4Attr);
				line.add(p4Attr);
				
				if(i1c != null) { 
					RDFPatternResource i1cAttr = new RDFPatternResource(i1c, RDFPatternComponent.Type.NODE1TYPE);

					_index.add(i1cAttr);
					line.add(i1cAttr);
				}
				
				if(i2c != null) { 
					RDFPatternResource i2cAttr = new RDFPatternResource(i2c, RDFPatternComponent.Type.NODE2TYPE);

					_index.add(i2cAttr);
					line.add(i2cAttr);
				}
				
				if(i3c != null) {
					RDFPatternResource i3cAttr = new RDFPatternResource(i3c, RDFPatternComponent.Type.NODE3TYPE);

					_index.add(i3cAttr);
					line.add(i3cAttr);
				}
				
				if(i4c != null) {
					RDFPatternResource i4cAttr = new RDFPatternResource(i4c, RDFPatternComponent.Type.NODE4TYPE);

					_index.add(i4cAttr);
					line.add(i4cAttr);
				}
				
				if(i5c != null) {
					RDFPatternResource i5cAttr = new RDFPatternResource(i5c, RDFPatternComponent.Type.NODE5TYPE);

					_index.add(i5cAttr);
					line.add(i5cAttr);
				}
								
				result.add(line);
			}
		}
		
		return result;
	}

	@Deprecated
	private Collection<? extends LabeledTransaction> extractPathAttributesRankThree(BaseRDF baseRDF, UtilOntology onto) {
		LinkedList<LabeledTransaction> result = new LinkedList<LabeledTransaction>();
		
		String rankTwoQueryString = "SELECT ?i1 ?i1c ?p1 ?i2 ?i2c ?p2 ?i3 ?i3c ?p3 ?i4 ?i4c WHERE { ?i1 ?p1 ?i2 . ?i2 ?p2 ?i3 . ?i3 ?p3 ?i4 ";
		rankTwoQueryString += " OPTIONAL { ?i1 a ?i1c . } ";
		rankTwoQueryString += " OPTIONAL { ?i2 a ?i2c . } ";
		rankTwoQueryString += " OPTIONAL { ?i3 a ?i3c . } ";
		rankTwoQueryString += " OPTIONAL { ?i4 a ?i4c . } ";
		rankTwoQueryString += " }";
		
		QueryResultIterator itRank2 = new QueryResultIterator(rankTwoQueryString, baseRDF);
		while(itRank2.hasNext()) {
			CustomQuerySolution sol = itRank2.next();
			Resource i1 = sol.getResource("i1");
			Resource i2 = sol.getResource("i2");
			Resource i3 = sol.getResource("i3");
			Resource i4 = sol.getResource("i4");
			Resource p1 = sol.getResource("p1");
			Resource p2 = sol.getResource("p2");
			Resource p3 = sol.getResource("p3");
			Resource i1c = sol.getResource("i1c");
			Resource i2c = sol.getResource("i2c");
			Resource i3c = sol.getResource("i3c");
			Resource i4c = sol.getResource("i4c");
			
			// Regroupement des statement pour éliminer les cycles
			HashSet<Resource> setStat1 = new HashSet<Resource>();
			setStat1.add(i1);
			setStat1.add(p1);
			setStat1.add(i2);
			HashSet<Resource> setStat2 = new HashSet<Resource>();
			setStat2.add(i2);
			setStat2.add(p2);
			setStat2.add(i3);
			HashSet<Resource> setStat3 = new HashSet<Resource>();
			setStat3.add(i3);
			setStat3.add(p3);
			setStat3.add(i4);
			
			if(i1 != null && i2 != null && i3 != null && i4 != null && p1 != null && p2 != null && p3 != null // no null
				&& ! onto.isClass(i1) && ! onto.isClass(i2) && ! onto.isClass(i3) && ! onto.isClass(i4) // no class
				&& ! onto.isOntologyClassVocabulary(i1) && ! onto.isOntologyClassVocabulary(i2) && ! onto.isOntologyClassVocabulary(i3) && ! onto.isOntologyClassVocabulary(i4) // no RDF stuff
				&& ! onto.isOntologyPropertyVocabulary(p1) && ! onto.isOntologyPropertyVocabulary(p2) && ! onto.isOntologyPropertyVocabulary(p3) // no RDF stuff
				&& ! setStat1.equals(setStat2) && ! setStat1.equals(setStat3) && ! setStat2.equals(setStat3)
				) {
				LabeledTransaction line = new LabeledTransaction();
				
				RDFPatternResource i1Attr = new RDFPatternResource(i1, RDFPatternComponent.Type.NODE1);
				RDFPatternResource i2Attr = new RDFPatternResource(i2, RDFPatternComponent.Type.NODE2);
				RDFPatternResource i3Attr = new RDFPatternResource(i3, RDFPatternComponent.Type.NODE3);
				RDFPatternResource i4Attr = new RDFPatternResource(i4, RDFPatternComponent.Type.NODE4);
				RDFPatternResource p1Attr = new RDFPatternResource(p1, RDFPatternComponent.Type.RELATION1);
				RDFPatternResource p2Attr = new RDFPatternResource(p2, RDFPatternComponent.Type.RELATION2);
				RDFPatternResource p3Attr = new RDFPatternResource(p3, RDFPatternComponent.Type.RELATION3);


				_index.add(i1Attr);
				line.add(i1Attr);

				_index.add(i2Attr);
				line.add(i2Attr);

				_index.add(i3Attr);
				line.add(i3Attr);

				_index.add(i4Attr);
				line.add(i4Attr);

				_index.add(p1Attr);
				line.add(p1Attr);

				_index.add(p2Attr);
				line.add(p2Attr);

				_index.add(p3Attr);
				line.add(p3Attr);
				
				if(i1c != null) { 
					RDFPatternResource i1cAttr = new RDFPatternResource(i1c, RDFPatternComponent.Type.NODE1TYPE);

					_index.add(i1cAttr);
					line.add(i1cAttr);
				}
				
				if(i2c != null) { 
					RDFPatternResource i2cAttr = new RDFPatternResource(i2c, RDFPatternComponent.Type.NODE2TYPE);

					_index.add(i2cAttr);
					line.add(i2cAttr);
				}
				
				if(i3c != null) {
					RDFPatternResource i3cAttr = new RDFPatternResource(i3c, RDFPatternComponent.Type.NODE3TYPE);

					_index.add(i3cAttr);
					line.add(i3cAttr);
				}
				
				if(i4c != null) {
					RDFPatternResource i4cAttr = new RDFPatternResource(i4c, RDFPatternComponent.Type.NODE3TYPE);

					_index.add(i4cAttr);
					line.add(i4cAttr);
				}
								
				result.add(line);
			}
		}
		
		return result;
	}

	@Deprecated
	private Collection<? extends LabeledTransaction> extractPathAttributesRankTwo(BaseRDF baseRDF, UtilOntology onto) {
		LinkedList<LabeledTransaction> result = new LinkedList<LabeledTransaction>();
		
		String rankTwoQueryString = "SELECT ?i1 ?i1c ?p1 ?i2 ?i2c ?p2 ?i3 ?i3c WHERE { ?i1 ?p1 ?i2 . ?i2 ?p2 ?i3 .";
		rankTwoQueryString += " OPTIONAL { ?i1 a ?i1c . } ";
		rankTwoQueryString += " OPTIONAL { ?i2 a ?i2c . } ";
		rankTwoQueryString += " OPTIONAL { ?i3 a ?i3c . } ";
		rankTwoQueryString += " }";
		
		QueryResultIterator itRank2 = new QueryResultIterator(rankTwoQueryString, baseRDF);
		while(itRank2.hasNext()) {
			CustomQuerySolution sol = itRank2.next();
			Resource i1 = sol.getResource("i1");
			Resource i2 = sol.getResource("i2");
			Resource i3 = sol.getResource("i3");
			Resource p1 = sol.getResource("p1");
			Resource p2 = sol.getResource("p2");
			Resource i1c = sol.getResource("i1c");
			Resource i2c = sol.getResource("i2c");
			Resource i3c = sol.getResource("i3c");
			
			// Regroupement des statement pour éliminer les cycles
			HashSet<Resource> setStat1 = new HashSet<Resource>();
			setStat1.add(i1);
			setStat1.add(p1);
			setStat1.add(i2);
			HashSet<Resource> setStat2 = new HashSet<Resource>();
			setStat2.add(i2);
			setStat2.add(p2);
			setStat2.add(i3);
			
			if(i1 != null && i2 != null && i3 != null && p1 != null && p2 != null
				&& ! onto.isClass(i1) && ! onto.isClass(i2) && ! onto.isClass(i3)
				&& ! onto.isOntologyClassVocabulary(i1) && ! onto.isOntologyClassVocabulary(i2) && ! onto.isOntologyClassVocabulary(i3)
				&& ! onto.isOntologyPropertyVocabulary(p1) && ! onto.isOntologyPropertyVocabulary(p2)
				&& ! setStat1.equals(setStat2)
				) {
				LabeledTransaction line = new LabeledTransaction();
				
				RDFPatternResource i1Attr = new RDFPatternResource(i1, RDFPatternComponent.Type.NODE1);
				RDFPatternResource i2Attr = new RDFPatternResource(i2, RDFPatternComponent.Type.NODE2);
				RDFPatternResource i3Attr = new RDFPatternResource(i3, RDFPatternComponent.Type.NODE3);
				RDFPatternResource p1Attr = new RDFPatternResource(p1, RDFPatternComponent.Type.RELATION1);
				RDFPatternResource p2Attr = new RDFPatternResource(p2, RDFPatternComponent.Type.RELATION2);

				_index.add(i1Attr);
				line.add(i1Attr);

				_index.add(i2Attr);
				line.add(i2Attr);

				_index.add(i3Attr);
				line.add(i3Attr);

				_index.add(p1Attr);
				line.add(p1Attr);

				_index.add(p2Attr);
				line.add(p2Attr);
				
				if(i1c != null) { 
					RDFPatternResource i1cAttr = new RDFPatternResource(i1c, RDFPatternComponent.Type.NODE1TYPE);

					_index.add(i1cAttr);
					line.add(i1cAttr);
				}
				if(i2c != null) { 
					RDFPatternResource i2cAttr = new RDFPatternResource(i2c, RDFPatternComponent.Type.NODE2TYPE);

					_index.add(i2cAttr);
					line.add(i2cAttr);
				}
				if(i3c != null) {
					RDFPatternResource i3cAttr = new RDFPatternResource(i3c, RDFPatternComponent.Type.NODE3TYPE);

					_index.add(i3cAttr);
					line.add(i3cAttr);
				}
								
				result.add(line);
			}
		}
		
		return result;
	}

	@Deprecated
	private Collection<? extends LabeledTransaction> extractPathAttributesRankOne(BaseRDF baseRDF, UtilOntology onto) {
		// TODO Auto-generated method stub
		return null;
	}
	
	@Deprecated
	public RankUpQuery sparqlizeItemSet(LabeledTransaction is) {
		HashMap<RDFPatternComponent, String> variables = new HashMap<RDFPatternComponent, String>();
		HashMap<String, RDFPatternComponent> patterns = new HashMap<String, RDFPatternComponent>();
		
		String patternCenterVar = "?pattern";
		
		String queryHead = "SELECT DISTINCT " + patternCenterVar;
		String queryBody = " WHERE { ";
		String queryEnd = " }";
		int varNum = 0;
		
		Iterator<RDFPatternComponent> itItems = is.iterator();
		while(itItems.hasNext()) {
			RDFPatternComponent item = itItems.next();
			if(item.getType() != Type.TYPE) {
				if(! variables.containsKey( item )) {
					String tmpVar = "?var" + varNum++;
					variables.put(item, tmpVar);
					patterns.put(tmpVar, item);
				}
			}
			String varName = variables.get(item);
			
			String patternString = "";
			if(item instanceof RDFPatternResource) {
				Resource patternRes = ((RDFPatternResource) item).getResource();
				if(item.getType() == Type.TYPE) {
					patternString = patternCenterVar + " <" + RDF.type + "> <" + patternRes.getURI() + "> . ";
				} else if(item.getType() == Type.OUT_PROPERTY) {
					patternString = patternCenterVar + " <" + patternRes.getURI() + "> " + varName +" . ";
				} else if(item.getType() == Type.IN_PROPERTY) {
					patternString = varName + " <" + patternRes.getURI() + "> " + patternCenterVar +" . ";
				}
			} else 
				if(item instanceof RDFPatternPathFragment) {
				Resource patternFirst = ((RDFPatternPathFragment) item).getPathFragment().getFirst();
				Resource patternSecond = ((RDFPatternPathFragment) item).getPathFragment().getSecond();
				if(item.getType() == Type.OUT_NEIGHBOUR_TYPE) {
					patternString = patternCenterVar + " <" + patternFirst.getURI() + "> " + varName +" . " + varName + " <" + RDF.type + "> <" + patternSecond.getURI() + "> . ";
				} else if(item.getType() == Type.IN_NEIGHBOUR_TYPE) {
					patternString = varName + " <" + patternSecond.getURI() + "> " + patternCenterVar + " . " + varName + " <" + RDF.type + "> <" + patternFirst.getURI() + "> . ";
				}
			}
			if(varName != null) {
				queryHead += " " + varName;
				queryBody += patternString;
			}
		}
		
		String queryString = queryHead + queryBody + queryEnd;
		return new RankUpQuery(queryString, variables, patterns);
	}

	
	private LabeledTransaction extractInPropertyAttributesForIndividual(BaseRDF baseRDF, UtilOntology onto, Resource currIndiv) {

		LabeledTransaction indivResult = new LabeledTransaction();
		
		String inTripQueryString = "SELECT DISTINCT ?p "; 
		if(this.getNeighborLevel() == Neighborhood.PropertyAndType 
				|| this.getNeighborLevel() == Neighborhood.PropertyAndOther) {
			inTripQueryString += " ?st " ;
		}
		if(this.getNeighborLevel() == Neighborhood.PropertyAndOther) {
			inTripQueryString += " ?s " ;
		}
		inTripQueryString += " WHERE { ?s ?p <" + currIndiv + "> . ";
		if(this.getNeighborLevel() == Neighborhood.PropertyAndType 
				|| this.getNeighborLevel() == Neighborhood.PropertyAndOther) {
			inTripQueryString += " OPTIONAL { ?s a ?st . } ";
		}
		inTripQueryString += " }";
		QueryResultIterator itInResult = new QueryResultIterator(inTripQueryString, baseRDF);
		try {
			while(itInResult.hasNext()) {
				CustomQuerySolution queryResultLine = itInResult.nextAnswerSet();
				Resource prop = queryResultLine.getResource("p");
				if(! onto.isOntologyPropertyVocabulary(prop)) {
					
					RDFPatternComponent propAttribute = new RDFPatternResource(prop, RDFPatternResource.Type.IN_PROPERTY );
					if(! _index.contains(propAttribute)) {
						_index.add(propAttribute);
					}
					indivResult.add(propAttribute);
					
					if(this.getNeighborLevel()== Neighborhood.PropertyAndOther) {
						RDFPatternComponent attributeOther = null;
						Resource subj = queryResultLine.getResource("s");
						if(! onto.isOntologyClassVocabulary(subj) && _outliers.contains(subj)) {
							attributeOther = new RDFPatternResource(prop, RDFPatternResource.Type.IN_NEIGHBOUR );
							if(! _index.contains(attributeOther)) {
								_index.add(attributeOther);
							}
							indivResult.add(attributeOther);
						}
					} 
					if (this.getNeighborLevel() == Neighborhood.PropertyAndType || this.getNeighborLevel() == Neighborhood.PropertyAndOther) {
						RDFPatternComponent attributeType = null;
						Resource sType = queryResultLine.getResource("st");
						if(sType != null && ! onto.isOntologyClassVocabulary(sType) && onto.isClass(sType)) {
							attributeType = new RDFPatternPathFragment(prop, sType, RDFPatternResource.Type.IN_NEIGHBOUR_TYPE );
							if(! _index.contains(attributeType)) {
								_index.add(attributeType);
							}
							indivResult.add(attributeType);
						}
					}
				}
			}
		} catch(HttpException e) {

		} finally {
			itInResult.close();
		}
		
		return indivResult;
	}
	

//	private LabeledTransaction extractPathFragmentAttributesForIndividual(BaseRDF baseRDF, UtilOntology onto, Resource currIndiv) {
//
//		LabeledTransaction indivResult = new LabeledTransaction();
//		
//		if(! this.noOutTriples()) {
//			String outTripQueryString = "SELECT DISTINCT ?p ?oc WHERE { <" + currIndiv + "> ?p ?o . ?o a ?oc }";
//			QueryResultIterator itOutResult = new QueryResultIterator(outTripQueryString, baseRDF);
//			try {
//				while(itOutResult.hasNext()) {
//					CustomQuerySolution queryResultLine = itOutResult.nextAnswerSet();
//					Resource prop = queryResultLine.getResource("p");
//					Resource objType = queryResultLine.getResource("oc");
//					if(!onto.isOntologyPropertyVocabulary(prop)) {
//						if(objType != null && prop != null) {
//							RDFPatternPathFragment attribute = new RDFPatternPathFragment(prop, objType, RDFPatternResource.Type.OUT_NEIGHBOUR_TYPE );
//							if(! onto.isOntologyClassVocabulary(objType) && onto.isClass(objType)) {
//								_index.add(attribute);
//								indivResult.add(attribute);
//							}
//						}
//					}
//				}
//			} catch(HttpException e) {
//	
//			} finally {
//				itOutResult.close();
//			}
//		}
//		
//		if(! this.noInTriples()) {
//			String inTripQueryString = "SELECT DISTINCT ?p ?oc WHERE { ?o ?p <" + currIndiv + "> . ?o a ?oc }";
//			QueryResultIterator itInResult = new QueryResultIterator(inTripQueryString, baseRDF);
//			try {
//				while(itInResult.hasNext()) {
//					CustomQuerySolution queryResultLine = itInResult.nextAnswerSet();
//					Resource prop = queryResultLine.getResource("p");
//					Resource objType = queryResultLine.getResource("oc");
////					logger.debug("Extracting InNeighbour attributes " + prop + " " +  );
//					if(!onto.isOntologyPropertyVocabulary(prop)) {
//						if(objType != null && prop != null) {
//							RDFPatternPathFragment attribute = new RDFPatternPathFragment(objType, prop, RDFPatternResource.Type.IN_NEIGHBOUR_TYPE );
//							if(! onto.isOntologyClassVocabulary(objType) && onto.isClass(objType)) {
//								_index.add(attribute);
//								indivResult.add(attribute);
//							}
//						}
//					}
//				}
//			} catch(HttpException e) {
//	
//			} finally {
//				itInResult.close();
//			}
//		}
//		
//		return indivResult;
//	}
//	
	public void setNeighborLevel(Neighborhood level) {
		this._neighborLevel = level;
	}
	
	public Neighborhood getNeighborLevel() {
		return this._neighborLevel;
	}

	public boolean noTypeTriples() {
		return _noTypeBool;
	}

	public void setNoTypeTriples(boolean noTypeBool) {
		this._noTypeBool = noTypeBool;
	}

	public boolean noInTriples() {
		return _noInBool;
	}

	public void noInTriples(boolean noInBool) {
		this._noInBool = noInBool;
	}

	public boolean noOutTriples() {
		return _noOutBool;
	}

	public void setNoOutTriples(boolean noOutBool) {
		this._noOutBool = noOutBool;
	}

	public int getPathsLength() {
		return _pathsLength;
	}

	public void setPathsLength(int pathsLength) {
		this._pathsLength = pathsLength;
	}
	
}
