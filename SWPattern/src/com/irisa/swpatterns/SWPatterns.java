package com.irisa.swpatterns;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Arrays;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionGroup;
import org.apache.commons.cli.Options;
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
import com.irisa.krimp.data.DataIndexes;
import com.irisa.krimp.data.ItemsetSet;
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
	
		OptionGroup algorithm = new OptionGroup();
		algorithm.addOption(new Option("FPClose", false, "Use FPClose algorithm. (default)"));
		algorithm.addOption(new Option("FPMax", false, "Use FPMax algorithm."));
		algorithm.addOption(new Option("FIN", false, "Use FIN algorithm."));
		algorithm.addOption(new Option("PrePost", false, "Use PrePost algorithm."));
		algorithm.addOption(new Option("FPGrowth", false, "Use FPGrowth algorithm."));
		algorithm.addOption(new Option("Relim", false, "Use Relim algorithm."));
		OptionGroup conversion = new OptionGroup();
		conversion.addOption(new Option(PropertiesConversionOption, false, "Extract items representing only properties (central individual types, out-going and in-going properties), encoding="+Neighborhood.Property+"."));
		conversion.addOption(new Option(PropertiesAndTypesConversionOption, false, "Extract items representing only properties and connected ressources types, encoding="+Neighborhood.PropertyAndType+"."));
		conversion.addOption(new Option(PropertiesAndOthersConversionOption, false, "Extract items representing properties and connected ressources, encoding="+Neighborhood.PropertyAndOther+"."));
		
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
		// Boolean behavioral options
		options.addOptionGroup(algorithm);
		options.addOptionGroup(conversion);
		options.addOption("pruning", false, "Activate post-acceptance pruning for better quality code table but slower performances."); 
		options.addOption("noOut", false, "Not taking OUT properties into account for RDF conversion.");
		options.addOption("noIn", false, "Not taking IN properties into account for RDF conversion.");
		options.addOption("noTypes", false, "Not taking TYPES into account for RDF conversion.");
		options.addOption("path", true, "Extract paths of length N. (Here be dragons)");
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
				
				UtilOntology onto = new UtilOntology();
				TransactionsExtractor converter = new TransactionsExtractor();
				SWFrequentItemsetExtractor fsExtractor = new SWFrequentItemsetExtractor();
				ItemsetSet realtransactions ;
				ItemsetSet codes = null;
				
				// Boolean options
				boolean activatePruning = cmd.hasOption("pruning"); 
				converter.setNoTypeTriples( cmd.hasOption("noTypes") || converter.noTypeTriples());
				converter.noInTriples(cmd.hasOption("noIn") || converter.noInTriples());
				converter.setNoOutTriples(cmd.hasOption("noOut") || converter.noOutTriples());
					// Algorithm are excluding each other
				if(cmd.hasOption("FPGrowth")) {
					fsExtractor.setAlgoFPGrowth();
				}
				else if(cmd.hasOption("FPClose")) {
					fsExtractor.setAlgoFPClose();
				}
				else if(cmd.hasOption("FPMax")) {
					fsExtractor.setAlgoFPMax();
				}
				else if(cmd.hasOption("FIN")) {
					fsExtractor.setAlgoFIN();
				}
				else if(cmd.hasOption("Relim")) {
					fsExtractor.setAlgoRelim();
				}
				else if(cmd.hasOption("PrePost")) {
					fsExtractor.setAlgoPrePost();
				}
				
				fsExtractor.setMinSupport(0.05);
				
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
				converter.setNeighborLevel(Neighborhood.PropertyAndValue);
				logger.debug("Pruning activated: "+activatePruning);
				
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
				String pathOption = cmd.getOptionValue("path");
			
				
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
	
					if(pathOption != null) {
						converter.setPathsLength(Integer.valueOf(pathOption));
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
	
					if(cmd.hasOption("class")) {
						Resource classRes = onto.getModel().createResource(className);
						transactions = converter.extractTransactionsForClass(baseRDF, onto, classRes);
					} else if(cmd.hasOption("path")) {
						transactions = converter.extractPathAttributes(baseRDF, onto);
					} else {
						transactions = converter.extractTransactions(baseRDF, onto);
					}
					
					// Printing transactions for both files
					if(outputTransaction) {
						index.printTransactionsItems(transactions, firstOutputTransactionFile);
					}
	
					realtransactions = index.convertToTransactions(transactions);
						codes = new ItemsetSet(fsExtractor.computeItemsets(transactions, index));
					logger.debug("Nb transactions: " + realtransactions.size());
	
					logger.debug("Nb items: " + converter.getIndex().size());
	
					baseRDF.close();

				ItemsetSet realcodes = new ItemsetSet(codes);
	
				try {
					DataIndexes analysis = new DataIndexes(realtransactions);
					CodeTable standardCT = CodeTable.createStandardCodeTable(realtransactions, analysis );
	
					KrimpAlgorithm kAlgo = new KrimpAlgorithm(realtransactions, realcodes);
					CodeTable krimpCT;
					if(inputCodeTableCodes) {
						ItemsetSet KRIMPcodes = Utils.readItemsetSetFile(firstKRIMPFile);
						krimpCT = new CodeTable(realtransactions, KRIMPcodes, analysis);
					} else {
						krimpCT = kAlgo.runAlgorithm(activatePruning);
					}
					
					if(outputCodeTableCodes) {
						Utils.printItemsetSet(krimpCT.getCodes(), firstOutputKRIMPFile);
					}
					double normalSize = standardCT.totalCompressedSize();
					double compressedSize = krimpCT.totalCompressedSize();
					logger.debug("-------- FIRST RESULT ---------");
//					logger.debug(krimpCT);
					//					logger.debug("First Code table: " + krimpCT);
					logger.debug("First NormalLength: " + normalSize);
					logger.debug("First CompressedLength: " + compressedSize);
					logger.debug("First Compression: " + (compressedSize / normalSize));
	
	
					// Printing conversion index
					if(outputConversionIndex) {
						converter.getIndex().printAttributeIndex(outputConversionIndexFile);
					}
					
				} catch (Exception e) {
					logger.fatal("RAAAH", e);
				}
				onto.close();
			}
		} catch (Exception e) {
			logger.fatal("Failed on " + Arrays.toString(args), e);
		}
	}
	

}
