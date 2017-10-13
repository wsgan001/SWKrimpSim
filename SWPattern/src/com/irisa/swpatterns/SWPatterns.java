package com.irisa.swpatterns;

import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Iterator;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionGroup;
import org.apache.commons.cli.Options;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

import com.irisa.jenautils.BaseRDF;
import com.irisa.jenautils.BaseRDF.MODE;
import com.irisa.jenautils.QueryResultIterator;
import com.irisa.jenautils.UtilOntology;
import com.irisa.krimp.CodeTable;
import com.irisa.krimp.KrimpAlgorithm;
import com.irisa.krimp.KrimpSlimAlgorithm;
import com.irisa.krimp.data.DataIndexes;
import com.irisa.krimp.data.ItemsetSet;
import com.irisa.krimp.data.KItemset;
import com.irisa.krimp.data.Utils;
import com.irisa.swpatterns.TransactionsExtractor.Neighborhood;
import com.irisa.swpatterns.data.AttributeIndex;
import com.irisa.swpatterns.data.LabeledTransactions;

public class SWPatterns {

	private static Logger logger = Logger.getLogger(SWPatterns.class);

	public static void main(String[] args) {
		BasicConfigurator.configure();
		PropertyConfigurator.configure("log4j-config.txt");

		logger.debug(Arrays.toString(args));

		// In/out options name to facilitate further references
		String inputRDFOption = "inputRDF";
		String outputTransactionOption = "outputTransaction";
		String inputCodeTableOption = "inputCodeTable";
		String outputCodeTableOption = "outputCodeTable";
		String inputConversionIndexOption = "inputConversionIndex";
		String outputConversionIndexOption = "outputConversionIndex";

		String PropertiesConversionOption = "nProperties";
		String PropertiesAndTypesConversionOption = "nPropertiesAndTypes";
		String PropertiesAndOthersConversionOption = "nPropertiesAndOthers";
		String PropertyPathAndValueConversionOption = "nPropertyPathAndValues";

		OptionGroup conversion = new OptionGroup();
		conversion.addOption(new Option(PropertiesConversionOption, false, "Extract items representing only properties (central individual types, out-going and in-going properties), encoding="+Neighborhood.Property+"."));
		conversion.addOption(new Option(PropertiesAndTypesConversionOption, false, "Extract items representing only properties and connected ressources types, encoding="+Neighborhood.PropertyAndType+"."));
		conversion.addOption(new Option(PropertiesAndOthersConversionOption, false, "Extract items representing properties and connected ressources, encoding="+Neighborhood.PropertyAndOther+"."));
		conversion.addOption(new Option(PropertyPathAndValueConversionOption, false, "Extract items representing property path including blank nodes ending with literals, encoding="+Neighborhood.PropertyAndValue+". (default)"));

		// Setting up options
		CommandLineParser parser = new DefaultParser();
		Options options = new Options();
		// In/Out
		options.addOption(inputRDFOption, true, "RDF file.");
		options.addOption(outputTransactionOption, false, "Create a .dat transaction for each given RDF file named <filename>.<encoding>.dat .");
		options.addOption(inputCodeTableOption, true, "Itemset file containing the KRIMP codetable for the first file.");
		options.addOption(outputCodeTableOption, false, "Create an Itemset file containing the  KRIMP codetable for each file <filename>.<encoding>.krimp.dat.");
		options.addOption(inputConversionIndexOption, true, "Set the index used for RDF to transaction conversion, new items will be added.");
		options.addOption(outputConversionIndexOption, true, "Create a file containing the index used for RDF to transaction conversion, new items will be added.");

		options.addOption("limit", true, "Limit to the number of individuals extracted from each class.");
		options.addOption("resultWindow", true, "Size of the result window used to query RDF data.");
		options.addOption("classPattern", true, "Substring contained by the class uris.");
		options.addOption("class", true, "Class of the selected individuals.");
		options.addOption("ignoredProperties", true, "File containing properties to be ignored during transaction extraction. File must contain a column of URIs between quotes.");
		// Boolean behavioral options
		options.addOptionGroup(conversion);
		options.addOption("noKrimp", false, "Krimp algorithm will no be launched, only the conversion of the first RDF file.");
		options.addOption("noOut", false, "Not taking OUT properties into account for RDF conversion.");
		options.addOption("noIn", false, "Not taking IN properties into account for RDF conversion.");
		options.addOption("noTypes", false, "Not taking TYPES into account for RDF conversion.");
		options.addOption("help", false, "Display this help.");

		// Setting up options and constants etc.
		try {
			CommandLine cmd = parser.parse( options, args);

			boolean helpAsked = cmd.hasOption("help");
			if(helpAsked) {
				HelpFormatter formatter = new HelpFormatter();
				formatter.printHelp( "RDFtoTransactionConverter", options );
			} else {
				boolean inputRDF = cmd.hasOption(inputRDFOption);
				boolean outputTransaction = cmd.hasOption(outputTransactionOption);
				boolean inputCodeTableCodes = cmd.hasOption(inputCodeTableOption);
				boolean outputCodeTableCodes = cmd.hasOption(outputCodeTableOption);
				boolean inputConversionIndex = cmd.hasOption(inputConversionIndexOption);
				boolean outputConversionIndex = cmd.hasOption(outputConversionIndexOption);
				boolean classPattern = cmd.hasOption("classPattern");
				boolean noKrimp = cmd.hasOption("noKrimp");

				UtilOntology onto = new UtilOntology();
				TransactionsExtractor converter = new TransactionsExtractor();
				SWFrequentItemsetExtractor fsExtractor = new SWFrequentItemsetExtractor();
				ItemsetSet realtransactions ;
				ItemsetSet codes = null;

				// Boolean options
				converter.setNoTypeTriples( cmd.hasOption("noTypes") || converter.noTypeTriples());
				converter.noInTriples(cmd.hasOption("noIn") || converter.noInTriples());
				converter.setNoOutTriples(cmd.hasOption("noOut") || converter.noOutTriples());

				fsExtractor.setMinSupport(0.0);

				converter.setNeighborLevel(Neighborhood.PropertyAndValue);
				// Encoding options
				if(cmd.hasOption(PropertiesConversionOption)) {
					converter.setNeighborLevel(Neighborhood.Property);
				}
				if(cmd.hasOption(PropertiesAndTypesConversionOption)) {
					converter.setNeighborLevel(Neighborhood.PropertyAndType);
				}
				if(cmd.hasOption(PropertiesAndOthersConversionOption)) {
					converter.setNeighborLevel(Neighborhood.PropertyAndOther);
				}

				// NO MODE cmd.hasOption() past this point

				String firstRDFFile = cmd.getOptionValue(inputRDFOption);
				String firstKRIMPFile = cmd.getOptionValue(inputCodeTableOption);
				String firstOutputFile = "";

				if(inputRDF) {
					firstOutputFile = firstRDFFile;
				}
				firstOutputFile += "." + converter.getNeighborLevel();
				String firstOutputTransactionFile = firstOutputFile + ".dat";
				String firstOutputCandidateFile = firstOutputFile + ".candidates.dat";
				String firstOutputKRIMPFile = firstOutputFile + ".krimp.dat";

				String inputConversionIndexFile = cmd.getOptionValue(inputConversionIndexOption);
				String outputConversionIndexFile = cmd.getOptionValue(outputConversionIndexOption);

				// RDF handling options
				String limitString = cmd.getOptionValue("limit");
				String resultWindow = cmd.getOptionValue("resultWindow");
				String classRegex = cmd.getOptionValue("classPattern");
				// Other encoding options
				String className = cmd.getOptionValue("class");
				String ignoredPropertiesFilename = cmd.getOptionValue("ignoredProperties");

				if(limitString != null) {
					QueryResultIterator.setDefaultLimit(Integer.valueOf(limitString));
				}
				if(resultWindow != null) {
					QueryResultIterator.setDefaultLimit(Integer.valueOf(resultWindow));
				}
				if(classPattern) {
					UtilOntology.setClassRegex(classRegex);
				} else {
					UtilOntology.setClassRegex(null);
				}

				BaseRDF baseRDF = new BaseRDF(firstRDFFile, MODE.LOCAL);

				//					logger.debug("initOnto");
				onto.init(baseRDF);

				logger.debug("Extracting transactions from RDF file with conversion " + converter.getNeighborLevel());


				AttributeIndex index = converter.getIndex();

				// Extracting transactions
				LabeledTransactions transactions;
				if(inputConversionIndex) {
					index.readAttributeIndex(inputConversionIndexFile);
				}
				
				converter.readIgnoredPropertiesFile(ignoredPropertiesFilename);
				
				if(cmd.hasOption("class")) {
					Resource classRes = onto.getModel().createResource(className);
					transactions = converter.extractTransactionsForClass(baseRDF, onto, classRes);
				} else {
					transactions = converter.extractTransactions(baseRDF, onto);
				}

				// Printing transactions for both files
				if(outputTransaction) {
					index.printTransactionsItems(transactions, firstOutputTransactionFile);
				}

				realtransactions = index.convertToTransactions(transactions);

				logger.debug("Nb transactions: " + realtransactions.size());

				logger.debug("Nb items: " + converter.getIndex().size());

				baseRDF.close();
				
				if(! noKrimp) {
//					codes = new ItemsetSet(fsExtractor.computeItemsets(transactions, index));
	
//					ItemsetSet realcodes = new ItemsetSet(codes);
	
					try {
						DataIndexes analysis = new DataIndexes(realtransactions);
						CodeTable standardCT = CodeTable.createStandardCodeTable(realtransactions, analysis );
	
						KrimpSlimAlgorithm kAlgo = new KrimpSlimAlgorithm(realtransactions);
						CodeTable krimpCT;
						if(inputCodeTableCodes) {
							ItemsetSet KRIMPcodes = Utils.readItemsetSetFile(firstKRIMPFile);
							krimpCT = new CodeTable(realtransactions, KRIMPcodes, analysis);
						} else {
							krimpCT = kAlgo.runAlgorithm();
						}
	
						if(outputCodeTableCodes) {
							Utils.printItemsetSet(krimpCT.getCodes(), firstOutputKRIMPFile);
						}
						double normalSize = standardCT.totalCompressedSize();
						double compressedSize = krimpCT.totalCompressedSize();
						logger.debug("-------- FIRST RESULT ---------");
//						logger.debug(krimpCT);
//						logger.debug("First Code table: " + krimpCT);
						logger.debug("NormalLength: " + normalSize);
						logger.debug("CompressedLength: " + compressedSize);
						logger.debug("Compression: " + (compressedSize / normalSize));
	
						Iterator<KItemset> itKrimpCode = krimpCT.codeIterator();
						int codeNum = 0;
						Model rdfFinale = ModelFactory.createDefaultModel();
						while(itKrimpCode.hasNext()) {
							KItemset code = itKrimpCode.next();
	
							rdfFinale.add(index.rdfizePattern(code, codeNum));
							codeNum++;
						}
						rdfFinale.write(new FileOutputStream("rdfPatterns.ttl"), "TTL");
	
						// Printing conversion index
						if(outputConversionIndex) {
							converter.getIndex().printAttributeIndex(outputConversionIndexFile);
						}
	
					} catch (Exception e) {
						logger.fatal("RAAAH", e);
					}
				}
				onto.close();
			}
		} catch (Exception e) {
			logger.fatal("Failed on " + Arrays.toString(args), e);
		}
	}


}
