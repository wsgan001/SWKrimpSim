package com.irisa.swpatterns.data.big;

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
import com.irisa.jenautils.QueryResultIterator;
import com.irisa.jenautils.UtilOntology;
import com.irisa.jenautils.BaseRDF.MODE;
import com.irisa.krimp.CodeTable;
import com.irisa.krimp.KrimpAlgorithm;
import com.irisa.krimp.data.DataIndexes;
import com.irisa.krimp.data.ItemsetSet;
import com.irisa.krimp.data.Utils;
import com.irisa.swpatterns.SWFrequentItemsetExtractor;
import com.irisa.swpatterns.TransactionsExtractor;
import com.irisa.swpatterns.TransactionsExtractor.Neighborhood;
import com.irisa.swpatterns.data.AttributeIndex;
import com.irisa.swpatterns.data.LabeledTransactions;

public class BigDataTransactionExtractorMain {
	
	private static Logger logger = Logger.getLogger(BigDataTransactionExtractorMain.class);

	public static void main(String[] args) {
		BasicConfigurator.configure();
		PropertyConfigurator.configure("log4j-config.txt");
		
		logger.debug(Arrays.toString(args));
		
		// In/out options name to facilitate further references
		String inputRDFOption = "inputRDF";
		String inputTransactionOption = "inputTransaction";
		String inputOtherRDFOption = "inputOtherRDF";
		String inputOtherTransactionOption = "inputOtherTransaction";
		String outputTransactionOption = "outputTransaction";
		String inputCandidatesOption = "inputCandidates";
		String inputOtherCandidatesOption = "inputOtherCandidates";
		String outputCandidatesOption = "outputCandidates";
		String inputCodeTableOption = "inputCodeTable";
		String inputOtherCodeTableOption = "inputOtherCodeTable";
		String outputCodeTableOption = "outputCodeTable";
		String inputConversionIndexOption = "inputConversionIndex";
		String outputConversionIndexOption = "outputConversionIndex";
		
		// CB: added to gather the results in CSV file
		String outputComparisonResultsFileOption = "outputComparisonResultsFile"; 
		
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
		options.addOption(inputTransactionOption, true, "Transaction file (RDF data will be ignored).");
		options.addOption(inputOtherRDFOption, true, "Other RDF file to be compared to the first one.");
		options.addOption(inputOtherTransactionOption, true, "Other transaction file (RDF data will be ignored) to be compared to the first one.");
		options.addOption(outputTransactionOption, false, "Create a .dat transaction for each given RDF file named <filename>.<encoding>.dat .");
		options.addOption(inputCandidatesOption, true, "File containing the candidate codes extracted for the first file.");
		options.addOption(inputOtherCandidatesOption, true, "File containing the candidate codes extracted for the first file.");
		options.addOption(outputCandidatesOption, false, "Create file containing the candidate codes extracted for the first file <filename>.<encoding>.candidates.dat.");
		options.addOption(inputCodeTableOption, true, "Itemset file containing the KRIMP codetable for the first file.");
		options.addOption(inputOtherCodeTableOption, true, "Itemset file containing the KRIMP codetable for the other file.");
		options.addOption(outputCodeTableOption, false, "Create an Itemset file containing the  KRIMP codetable for each file <filename>.<encoding>.krimp.dat.");
		options.addOption(inputConversionIndexOption, true, "Set the index used for RDF to transaction conversion, new items will be added.");
		options.addOption(outputConversionIndexOption, true, "Create a file containing the index used for RDF to transaction conversion, new items will be added.");
		
		// CB: 
		options.addOption(outputComparisonResultsFileOption, true, "File to add the output of the comparison to in CSV format."); 
		
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
				boolean inputTransaction = cmd.hasOption(inputTransactionOption);
				boolean inputOtherRDFFile = cmd.hasOption(inputOtherRDFOption);
				boolean inputOtherTransaction = cmd.hasOption(inputOtherTransactionOption);
				boolean inputOtherCodeTable = cmd.hasOption(inputOtherCodeTableOption);
				boolean otherInput = inputOtherRDFFile || inputOtherTransaction || inputOtherCodeTable;
				boolean outputTransaction = cmd.hasOption(outputTransactionOption);
				boolean inputCandidatesCodes = cmd.hasOption(inputCandidatesOption);
				boolean inputOtherCandidatesCodes = cmd.hasOption(inputOtherCandidatesOption);
				boolean outputCandidatesCodes = cmd.hasOption(outputCandidatesOption);
				boolean inputCodeTableCodes = cmd.hasOption(inputCodeTableOption);
				boolean outputCodeTableCodes = cmd.hasOption(outputCodeTableOption);
				boolean inputConversionIndex = cmd.hasOption(inputConversionIndexOption);
				boolean outputConversionIndex = cmd.hasOption(outputConversionIndexOption);
				boolean classPattern = cmd.hasOption("classPattern");
				
				UtilOntology onto = new UtilOntology();
				BigDataTransactionExtractor converter = new BigDataTransactionExtractor();
				TransactionsExtractor normalConverter = new TransactionsExtractor();
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
				normalConverter.setNeighborLevel(converter.getNeighborLevel());
				logger.debug("Pruning activated: "+activatePruning);
				
				// NO MODE cmd.hasOption() past this point
	
				String firstRDFFile = cmd.getOptionValue(inputRDFOption);
				String otherRDFFile = cmd.getOptionValue(inputOtherRDFOption);
				String firstTransactionFile = cmd.getOptionValue(inputTransactionOption);
				String otherTransactionFile = cmd.getOptionValue(inputOtherTransactionOption);
				String firstCandidatesFile = cmd.getOptionValue(inputCandidatesOption);
				String otherCandidatesFile = cmd.getOptionValue(inputOtherCandidatesOption);
				String firstKRIMPFile = cmd.getOptionValue(inputCodeTableOption);
				String otherKRIMPFile = cmd.getOptionValue(inputOtherCodeTableOption);
				String firstOutputFile = "";
				String outputComparisonResultsFile = cmd.getOptionValue(outputComparisonResultsFileOption); 
				
				if(inputRDF) {
					firstOutputFile = firstRDFFile;
				} else if(inputTransaction) {
					firstOutputFile = firstTransactionFile;
				}
				firstOutputFile += "." + converter.getNeighborLevel();
				String firstOutputTransactionFile = firstOutputFile + ".dat";
				String firstOutputCandidateFile = firstOutputFile + ".candidates.dat";
				String firstOutputKRIMPFile = firstOutputFile + ".krimp.dat";
				String otherOutputFile = "";
				if(inputOtherRDFFile) {
					otherOutputFile = otherRDFFile;
				} else if(inputOtherTransaction) {
					otherOutputFile = otherTransactionFile;
				}
				otherOutputFile += "." + converter.getNeighborLevel();
				String otherOutputTransactionFile = otherOutputFile + ".dat";
				String otherOutputCandidateFile = otherOutputFile + ".candidates.dat";
				String otherOutputKRIMPFile = otherOutputFile + ".krimp.dat";
				
				String inputConversionIndexFile = cmd.getOptionValue(inputConversionIndexOption);
				String outputConversionIndexFile = cmd.getOptionValue(outputConversionIndexOption);
				if(! cmd.hasOption(outputConversionIndexOption)) {
					outputConversionIndexFile = "conversionIndex.attr";
				}
				
				// RDF handling options
				String limitString = cmd.getOptionValue("limit");
				String resultWindow = cmd.getOptionValue("resultWindow");
				String classRegex = cmd.getOptionValue("classPattern");
				// Other encoding options
				String className = cmd.getOptionValue("class");
				String pathOption = cmd.getOptionValue("path");
			
				
				if(! inputTransaction) {
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
	
//					if(pathOption != null) {
//						converter.setPathsLength(Integer.valueOf(pathOption));
//					}
	
//					BaseRDF baseRDF = new BaseRDF(firstRDFFile, MODE.LOCAL);
	
//					logger.debug("initOnto");
//					onto.init(baseRDF);
	
					logger.debug("Extracting transactions from RDF file with conversion " + converter.getNeighborLevel());

					
					AttributeIndex index = AttributeIndex.getInstance();
					
					// Extracting transactions
					ItemsetSet transactionsBigData;
//					LabeledTransactions transactionsNormal;
					if(inputConversionIndex) {
						index.readAttributeIndex(inputConversionIndexFile);
					}
	
//					if(cmd.hasOption("class")) {
//						Resource classRes = onto.getModel().createResource(className);
//						transactions = converter.extractTransactionsForClass(baseRDF, onto, classRes);
//					} else if(cmd.hasOption("path")) {
//						transactions = converter.extractPathAttributes(baseRDF, onto);
//					} else {
						transactionsBigData = converter.extractTransactionsFromFile(firstRDFFile);
						// Printing transactions for both files
						if(outputTransaction) {
//							index.printTransactionsItems(transactionsBigData, firstOutputTransactionFile);
							Utils.printTransactions(transactionsBigData, firstOutputTransactionFile);
							logger.debug("Transactions printed to " + firstOutputTransactionFile);
							AttributeIndex.getInstance().printAttributeIndex(outputConversionIndexFile);
							logger.debug("Index printed to " + outputConversionIndexFile);
						}
//						transactionsNormal = normalConverter.extractTransactions(baseRDF, onto);
//						if(outputTransaction) {
//							index.printTransactionsItems(transactionsNormal, firstOutputTransactionFile + ".normal");
//						}
//					}
					
	
//					realtransactions = index.convertToTransactions(transactionsBigData);
//					realtransactions = converter.extractTransactionsFromFile(firstRDFFile);
					logger.debug("Nb transactions: " + transactionsBigData.size());
					logger.debug("Nb items: " + index.size());
					
					
					
//					if(! inputCandidatesCodes) {
//						codes = new ItemsetSet(fsExtractor.computeItemsets(transactionsBigData, index));
//					} else {
//						codes = Utils.readItemsetSetFile(cmd.getOptionValue(inputCandidatesOption));
//					}
	
//					if(outputCandidatesCodes) {
//						index.printTransactionsItems(transactionsBigData, firstOutputCandidateFile);
//					}
//	
//					baseRDF.close();
	
				} else {
					realtransactions = new ItemsetSet(Utils.readTransactionFile(cmd.getOptionValue(inputTransactionOption)));
					if(! inputCandidatesCodes) {
						codes = new ItemsetSet(fsExtractor.computeItemsets(realtransactions));
					} else {
						codes = Utils.readItemsetSetFile(inputCandidatesOption);
					}
					logger.debug("Nb Lines: " + realtransactions.size());
				}
//				ItemsetSet realcodes = new ItemsetSet(codes);
	
//				try {
//					DataIndexes analysis = new DataIndexes(realtransactions);
//					CodeTable standardCT = CodeTable.createStandardCodeTable(realtransactions, analysis );
//	
//					KrimpAlgorithm kAlgo = new KrimpAlgorithm(realtransactions, realcodes);
//					CodeTable krimpCT;
//					if(inputCodeTableCodes) {
//						ItemsetSet KRIMPcodes = Utils.readItemsetSetFile(firstKRIMPFile);
//						krimpCT = new CodeTable(realtransactions, KRIMPcodes, analysis);
//					} else {
//						krimpCT = kAlgo.runAlgorithm(activatePruning);
//					}
//					
//					if(outputCodeTableCodes) {
//						Utils.printItemsetSet(krimpCT.getCodes(), firstOutputKRIMPFile);
//					}
//					double normalSize = standardCT.totalCompressedSize();
//					double compressedSize = krimpCT.totalCompressedSize();
//					logger.debug("-------- FIRST RESULT ---------");
////					logger.debug(krimpCT);
//					//					logger.debug("First Code table: " + krimpCT);
//					logger.debug("First NormalLength: " + normalSize);
//					logger.debug("First CompressedLength: " + compressedSize);
//					logger.debug("First Compression: " + (compressedSize / normalSize));
//	
//					if(otherInput) {
//	
//						ItemsetSet otherRealTransactions;
//						BaseRDF otherBase = new BaseRDF(otherRDFFile, MODE.LOCAL);
//						logger.debug("processing base of " + otherBase.size() + " triples.");
//						onto.init(otherBase);
//						if(! inputOtherTransaction) {
//							LabeledTransactions otherTransactions;
////							if(cmd.hasOption("class")) {
////								Resource classRes = onto.getModel().createResource(className);
////								otherTransactions = converter.extractTransactionsForClass(otherBase,  onto, classRes);
////							} else if(cmd.hasOption("path")) {
////								otherTransactions = converter.extractPathAttributes(otherBase,  onto);
////							} else {
//								otherTransactions = converter.extractTransactionsFromFile(otherRDFFile);
////							}
//							logger.debug("Other RDF transactions: " + otherTransactions.size() + " transactions");
//		
//							otherRealTransactions = AttributeIndex.getInstance().convertToTransactions(otherTransactions);
//							if(outputTransaction) {
//								AttributeIndex.getInstance().printTransactionsItems(otherTransactions, otherOutputTransactionFile);
//							}
//						} else {
//							otherRealTransactions = new ItemsetSet(Utils.readTransactionFile(otherTransactionFile));
//						}
//						logger.debug("Other final transactions: " + otherRealTransactions.size() + " transactions");
//						
//						DataIndexes otherAnalysis = new DataIndexes(otherRealTransactions);
//						standardCT = CodeTable.createStandardCodeTable(otherRealTransactions, otherAnalysis );
//						CodeTable otherKrimpCT ;
//						if(! inputOtherCodeTable) {
//							ItemsetSet otherCandidates;
//							if(inputOtherCandidatesCodes) {
//								otherCandidates = Utils.readItemsetSetFile(otherCandidatesFile);
//							} else {
//								otherCandidates = new ItemsetSet(fsExtractor.computeItemsets(otherRealTransactions));
//							}
//							if(outputCandidatesCodes) {
//								Utils.printItemsetSet(otherCandidates, otherOutputCandidateFile);
//							}
//							
//							KrimpAlgorithm otherKrimpAlgo = new KrimpAlgorithm(otherRealTransactions, otherCandidates);
//							otherKrimpCT = otherKrimpAlgo.runAlgorithm(activatePruning);
//						} else {
//							otherKrimpCT = new CodeTable(otherRealTransactions, Utils.readItemsetSetFile(otherKRIMPFile), otherAnalysis);
//						}
//						
//						CodeTable otherComparisonResult = new CodeTable( otherRealTransactions, krimpCT.getCodes(), otherAnalysis);
//						double otherNormalSize = standardCT.totalCompressedSize();
//						double otherCompressedSize = otherKrimpCT.totalCompressedSize();
//						double otherCompressedSizeWithoutCT = otherKrimpCT.encodedTransactionSetCodeLength();
//						double othercomparisonSize = otherComparisonResult.totalCompressedSize();
//						double othercomparisonSizeWithoutCT = otherComparisonResult.encodedTransactionSetCodeLength();
//						//	logger.debug("First Code table: " + krimpCT);
//						logger.debug("-------- OTHER RESULT ---------");
//						logger.debug("Other NormalLength: " + otherNormalSize);
//						logger.debug("Other CompressedLength: " + otherCompressedSize);
//						logger.debug("Other Compression: " + (otherCompressedSize / otherNormalSize));
//						logger.debug("Second Compression with first CT: " + (othercomparisonSize / otherNormalSize));
//						logger.debug("Compression ratio: "+(othercomparisonSize/otherCompressedSize));
//						logger.debug("-------- NEW FORMULATION --------");
//						double evalKrimpSize = otherKrimpCT.codificationLength(otherRealTransactions); 
//						krimpCT.setTransactions(otherRealTransactions);
//						// WARNING!!! update usages is tricky
//						// The cover order is not modified, but the usages (and therefore the code length) are 
//						// This is done on purpose, trying to use the set of codes as efficiently as possible
//						krimpCT.updateUsages();
//						double refKrimpSize = krimpCT.codificationLength(otherRealTransactions); 
//						
//						logger.debug("Size of eval database using its Krimp CT: "+evalKrimpSize);
//						logger.debug("Size of eval database using the Reference Krimp CT: "+refKrimpSize);
//						logger.debug("Compression ratio: "+(refKrimpSize/evalKrimpSize));
//						
////						logger.debug("-------- OTHER RESULT ---------");
////						logger.debug(otherKrimpCT);
//						
//						if(outputCodeTableCodes) {
//							Utils.printItemsetSet(otherKrimpCT.getCodes(), otherOutputKRIMPFile);
//						}
//						
//						if (outputComparisonResultsFile == null){						
//							System.out.println(otherCompressedSize+";"+othercomparisonSize+";"+otherCompressedSizeWithoutCT+";"+othercomparisonSizeWithoutCT);
//						}
//						else{
//							
//							File outputFile = new File (outputComparisonResultsFile); 
//							
//							if (!outputFile.exists()) {
//								logger.debug("creating a new file ..."); 
//								outputFile.createNewFile(); 
//								try (FileWriter fw = new FileWriter(outputFile); 
//										PrintWriter out = new PrintWriter(fw)) {									
//									out.println("input;other;Conversion;refCompressionRatioOwnCT;evalCompressionRationOwnCT;relativeCompressionRatio(withCT);newRelativeCompressionRatio");
//									out.flush();
//								}
//								catch( Exception e) {
//									e.printStackTrace();
//								}
//							}
//							else {
//								logger.debug("the file existed before ..."); 
//							}
//							
//							try (FileWriter fw = new FileWriter(outputComparisonResultsFile, true); 
//									PrintWriter out = new PrintWriter(fw)) {
//															
//								StringBuilder line = new StringBuilder(); 
//								if (inputRDF) {
//									line.append(firstRDFFile); 									
//								}
//								else if (inputCodeTableCodes) {
//									line.append(firstKRIMPFile); 									
//								}
//								line.append(";"); 
//								if (inputOtherRDFFile) {
//									line.append(otherRDFFile); 
//								}
//								else if (inputOtherCodeTable) {
//									line.append(otherKRIMPFile); 
//								}
//								line.append(";");
//								line.append(converter.getNeighborLevel().toString());
//								line.append(";");
//								// refCompressionRationOwnCT
//								line.append(compressedSize / normalSize); 
//								line.append(";"); 
//								// evalCompressionRatioOwnCT
//								line.append(otherCompressedSize / otherNormalSize); 
//								line.append(";"); 
//								// the old relative ratio
//								line.append(othercomparisonSize/otherCompressedSize); 
//								line.append(";"); 
//								// the new relative ratio
//								line.append(refKrimpSize/evalKrimpSize);
//								out.println(line); 
//								out.flush();
//							}							
//						}
//	
//					}
//	
//					// Printing conversion index
//					if(outputConversionIndex) {
//						AttributeIndex.getInstance().printAttributeIndex(outputConversionIndexFile);
//					}
//					
//				} catch (Exception e) {
//					logger.fatal("RAAAH", e);
//				}
				onto.close();
			}
		} catch (Exception e) {
			logger.fatal("Failed on " + Arrays.toString(args), e);
		}
	}

}