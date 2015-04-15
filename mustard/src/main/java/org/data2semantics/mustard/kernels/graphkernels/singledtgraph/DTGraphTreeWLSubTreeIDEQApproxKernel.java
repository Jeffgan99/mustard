package org.data2semantics.mustard.kernels.graphkernels.singledtgraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.data2semantics.mustard.kernels.ComputationTimeTracker;
import org.data2semantics.mustard.kernels.KernelUtils;
import org.data2semantics.mustard.kernels.SparseVector;
import org.data2semantics.mustard.kernels.data.SingleDTGraph;
import org.data2semantics.mustard.kernels.graphkernels.FeatureVectorKernel;
import org.data2semantics.mustard.kernels.graphkernels.GraphKernel;
import org.data2semantics.mustard.utils.Pair;
import org.data2semantics.mustard.weisfeilerlehman.ApproxStringLabel;
import org.data2semantics.mustard.weisfeilerlehman.WeisfeilerLehmanApproxDTGraphIterator;
import org.data2semantics.mustard.weisfeilerlehman.WeisfeilerLehmanApproxIterator;
import org.nodes.DTGraph;
import org.nodes.DTLink;
import org.nodes.DTNode;
import org.nodes.LightDTGraph;

/**
 * @author Gerben
 *
 */
public class DTGraphTreeWLSubTreeIDEQApproxKernel implements GraphKernel<SingleDTGraph>, FeatureVectorKernel<SingleDTGraph>, ComputationTimeTracker {

	private Map<DTNode<ApproxStringLabel,ApproxStringLabel>, List<Pair<DTNode<ApproxStringLabel,ApproxStringLabel>, Integer>>> instanceVertexIndexMap;
	private Map<DTNode<ApproxStringLabel,ApproxStringLabel>, List<Pair<DTLink<ApproxStringLabel,ApproxStringLabel>, Integer>>> instanceEdgeIndexMap;

	private DTGraph<ApproxStringLabel,ApproxStringLabel> rdfGraph;
	private List<DTNode<ApproxStringLabel,ApproxStringLabel>> instanceVertices;

	private Map<String, Integer> labelFreq;

	private int depth;
	private int iterations;
	private boolean normalize;
	private boolean reverse;
	private boolean iterationWeighting;
	private boolean noDuplicateNBH;
	private boolean noSubGraphs;
	private int[] maxLabelCards;
	private int[] minFreqs;
	private int[] maxPrevNBHs;

	private double depthWeight;
	private double depthDiffWeight;

	private long compTime; // should be last, so that it is the last arg in the label

	public DTGraphTreeWLSubTreeIDEQApproxKernel(int iterations, int depth, boolean reverse, boolean iterationWeighting, boolean noDuplicateNBH, boolean noSubGraphs, double depthWeight, double depthDiffWeight, int[] maxPrevNBHs, int[] maxLabelCards, int[] minFreqs, boolean normalize) {
		this.reverse = reverse;
		this.iterationWeighting = iterationWeighting;
		this.noDuplicateNBH = noDuplicateNBH;
		this.noSubGraphs = noSubGraphs;
		this.normalize = normalize;
		this.depth = depth;
		this.iterations = iterations;
		this.maxLabelCards = maxLabelCards;
		this.minFreqs = minFreqs;
		this.maxPrevNBHs = maxPrevNBHs;
		this.depthWeight = depthWeight;
		this.depthDiffWeight = depthDiffWeight;
	}

	public String getLabel() {
		return KernelUtils.createLabel(this);		
	}

	public void setNormalize(boolean normalize) {
		this.normalize = normalize;
	}

	public long getComputationTime() {
		return compTime;
	}

	public SparseVector[] computeFeatureVectors(SingleDTGraph data) {
		SparseVector[] featureVectors = new SparseVector[data.getInstances().size()];
		for (int i = 0; i < featureVectors.length; i++) {
			featureVectors[i] = new SparseVector();
		}	

		WeisfeilerLehmanApproxIterator<DTGraph<ApproxStringLabel,ApproxStringLabel>,String> wl = new WeisfeilerLehmanApproxDTGraphIterator(reverse, 1, 1, 1);

		double numK = (minFreqs.length) * (maxLabelCards.length) * (maxPrevNBHs.length); // number of different kernels that have to be computed computed	
				
		long tic = System.currentTimeMillis();

		for (int minFreq : minFreqs) {
			for (int maxCard : maxLabelCards) {
				for (int maxPrevNBH : maxPrevNBHs) {
					init(data.getGraph(), data.getInstances());
					List<DTGraph<ApproxStringLabel,ApproxStringLabel>> gList = new ArrayList<DTGraph<ApproxStringLabel,ApproxStringLabel>>();
					gList.add(rdfGraph);
					wl.wlInitialize(gList);

					wl.setMaxLabelCard(maxCard);
					wl.setMinFreq(minFreq);
					wl.setMaxPrevNBH(maxPrevNBH);
					
					double weight = 1.0 / numK;
					//if (iterationWeighting) {
					//	weight = Math.sqrt(1.0 / (iterations + 1));
					//}

					computeFVs(rdfGraph, instanceVertices, weight, featureVectors, wl.getLabelDict().size()-1, 0);


					for (int i = 0; i < iterations; i++) {
						//if (iterationWeighting) {
						//	weight = Math.sqrt((2.0 + i) / (iterations + 1));
						//}
						weight = (1.0 + ((i+1) * ((numK-1.0) / iterations))) / numK;
						
						computeLabelFreqs(rdfGraph, instanceVertices);
						wl.wlIterate(gList, labelFreq);
						computeFVs(rdfGraph, instanceVertices, weight, featureVectors, wl.getLabelDict().size()-1, i+1);
					}
				}
			}
		}

		compTime = System.currentTimeMillis() - tic;

		if (this.normalize) {
			featureVectors = KernelUtils.normalize(featureVectors);
		}
		return featureVectors;
	}


	public double[][] compute(SingleDTGraph data) {
		SparseVector[] featureVectors = computeFeatureVectors(data);
		double[][] kernel = KernelUtils.initMatrix(data.getInstances().size(), data.getInstances().size());
		long tic = System.currentTimeMillis();
		kernel = KernelUtils.computeKernelMatrix(featureVectors, kernel);
		compTime += System.currentTimeMillis() - tic;
		return kernel;
	}



	private void init(DTGraph<String,String> graph, List<DTNode<String,String>> instances) {
		DTNode<ApproxStringLabel,ApproxStringLabel> startV;
		List<DTNode<String,String>> frontV, newFrontV;
		List<Pair<DTNode<ApproxStringLabel,ApproxStringLabel>, Integer>> vertexIndexMap;
		List<Pair<DTLink<ApproxStringLabel,ApproxStringLabel>, Integer>> edgeIndexMap;
		Map<DTNode<String,String>, DTNode<ApproxStringLabel,ApproxStringLabel>> vOldNewMap = new HashMap<DTNode<String,String>,DTNode<ApproxStringLabel,ApproxStringLabel>>();
		Map<DTLink<String,String>, DTLink<ApproxStringLabel,ApproxStringLabel>> eOldNewMap = new HashMap<DTLink<String,String>,DTLink<ApproxStringLabel,ApproxStringLabel>>();

		instanceVertices       = new ArrayList<DTNode<ApproxStringLabel,ApproxStringLabel>>();
		instanceVertexIndexMap = new HashMap<DTNode<ApproxStringLabel,ApproxStringLabel>, List<Pair<DTNode<ApproxStringLabel,ApproxStringLabel>, Integer>>>();
		instanceEdgeIndexMap   = new HashMap<DTNode<ApproxStringLabel,ApproxStringLabel>, List<Pair<DTLink<ApproxStringLabel,ApproxStringLabel>, Integer>>>();
		rdfGraph = new LightDTGraph<ApproxStringLabel,ApproxStringLabel>();

		for (DTNode<String,String> oldStartV : instances) {				
			vertexIndexMap = new ArrayList<Pair<DTNode<ApproxStringLabel,ApproxStringLabel>, Integer>>();
			edgeIndexMap   = new ArrayList<Pair<DTLink<ApproxStringLabel,ApproxStringLabel>, Integer>>();

			// Get the start node
			if (vOldNewMap.containsKey(oldStartV)) {
				startV = vOldNewMap.get(oldStartV);
			} else { 
				startV = rdfGraph.add(new ApproxStringLabel());
				vOldNewMap.put(oldStartV, startV);
			}
			startV.label().clear();
			startV.label().append(oldStartV.label());
			instanceVertices.add(startV);

			instanceVertexIndexMap.put(startV, vertexIndexMap);
			instanceEdgeIndexMap.put(startV, edgeIndexMap);

			frontV = new ArrayList<DTNode<String,String>>();
			frontV.add(oldStartV);

			// Process the start node
			vertexIndexMap.add(new Pair<DTNode<ApproxStringLabel,ApproxStringLabel>,Integer>(startV, depth));

			for (int j = depth - 1; j >= 0; j--) {
				newFrontV = new ArrayList<DTNode<String,String>>();
				for (DTNode<String,String> qV : frontV) {
					for (DTLink<String,String> edge : qV.linksOut()) {
						if (vOldNewMap.containsKey(edge.to())) { // This vertex has been added to rdfGraph
							vertexIndexMap.add(new Pair<DTNode<ApproxStringLabel,ApproxStringLabel>,Integer>(vOldNewMap.get(edge.to()), j));  
							vOldNewMap.get(edge.to()).label().clear();
							vOldNewMap.get(edge.to()).label().append(edge.to().label()); 
						} else {
							DTNode<ApproxStringLabel,ApproxStringLabel> newN = rdfGraph.add(new ApproxStringLabel());
							newN.label().clear();
							newN.label().append(edge.to().label());
							vOldNewMap.put(edge.to(), newN);
							vertexIndexMap.add(new Pair<DTNode<ApproxStringLabel,ApproxStringLabel>,Integer>(newN, j)); 
						}

						if (eOldNewMap.containsKey(edge)) {
							edgeIndexMap.add(new Pair<DTLink<ApproxStringLabel,ApproxStringLabel>,Integer>(eOldNewMap.get(edge),j)); 		
							eOldNewMap.get(edge).tag().clear();
							eOldNewMap.get(edge).tag().append(edge.tag());
						} else {
							DTLink<ApproxStringLabel,ApproxStringLabel> newE = vOldNewMap.get(qV).connect(vOldNewMap.get(edge.to()), new ApproxStringLabel());
							newE.tag().clear();
							newE.tag().append(edge.tag());
							eOldNewMap.put(edge, newE);
							edgeIndexMap.add(new Pair<DTLink<ApproxStringLabel,ApproxStringLabel>,Integer>(newE, j));
						}

						// Add the vertex to the new front, if we go into a new round
						if (j > 0) {
							newFrontV.add(edge.to());
						}
					}
				}
				frontV = newFrontV;
			}
		}		
	}





	/**
	 * @param graph
	 * @param instances
	 * @param weight
	 * @param featureVectors
	 */
	private void computeFVs(DTGraph<ApproxStringLabel,ApproxStringLabel> graph, List<DTNode<ApproxStringLabel,ApproxStringLabel>> instances, double weight, SparseVector[] featureVectors, int lastIndex, int currentIt) {
		int index;
		List<Pair<DTNode<ApproxStringLabel,ApproxStringLabel>, Integer>> vertexIndexMap;
		List<Pair<DTLink<ApproxStringLabel,ApproxStringLabel>, Integer>> edgeIndexMap;

		for (int i = 0; i < instances.size(); i++) {
			featureVectors[i].setLastIndex((lastIndex * (this.depth+1)) + this.depth);

			vertexIndexMap = instanceVertexIndexMap.get(instances.get(i));
			for (Pair<DTNode<ApproxStringLabel,ApproxStringLabel>, Integer> vertex : vertexIndexMap) {
				if ((!noDuplicateNBH || vertex.getFirst().label().getSameAsPrev() == 0)  && (noSubGraphs || (depth * 2) >= currentIt)) {
					index = Integer.parseInt(vertex.getFirst().label().toString());

					for (int j = 0; j <= this.depth; j++) {
						int index2 = (index * (this.depth+1)) + j;
						double weight2 = weight / Math.pow(depthDiffWeight,Math.abs(j-depth)); // farther away depths get lower weight, the distance is abs(j-depth)
						weight2 = weight2 / Math.pow(depthWeight, j);
						featureVectors[i].setValue(index2, featureVectors[i].getValue(index2) + weight2);
					}
				}
			}
			edgeIndexMap = instanceEdgeIndexMap.get(instances.get(i));
			for (Pair<DTLink<ApproxStringLabel,ApproxStringLabel>, Integer> edge : edgeIndexMap) {
				if ((!noDuplicateNBH || edge.getFirst().tag().getSameAsPrev() == 0) && (noSubGraphs || ((depth * 2)+1) >= currentIt)) {
					index = Integer.parseInt(edge.getFirst().tag().toString());

					for (int j = 0; j <= this.depth; j++) {
						int index2 = (index * (this.depth+1)) + j;
						double weight2 = weight / Math.pow(depthDiffWeight,Math.abs(j-depth)); // farther away depths get lower weight, the distance is abs(j-depth)
						weight2 = weight2 / Math.pow(depthWeight, j);
						featureVectors[i].setValue(index2, featureVectors[i].getValue(index2) + weight2);
					}
				}
			}
		}
	}

	private void computeLabelFreqs(DTGraph<ApproxStringLabel,ApproxStringLabel> graph, List<DTNode<ApproxStringLabel,ApproxStringLabel>> instances) {
		List<Pair<DTNode<ApproxStringLabel,ApproxStringLabel>, Integer>> vertexIndexMap;
		List<Pair<DTLink<ApproxStringLabel,ApproxStringLabel>, Integer>> edgeIndexMap;

		// Build a new label Frequencies map
		labelFreq = new HashMap<String, Integer>();

		for (int i = 0; i < instances.size(); i++) {
			Set<String> seen = new HashSet<String>(); // to track seen label for this instance

			vertexIndexMap = instanceVertexIndexMap.get(instances.get(i));
			for (Pair<DTNode<ApproxStringLabel,ApproxStringLabel>, Integer> vertex : vertexIndexMap) {
				String lab = vertex.getFirst().label().toString();
				if (!labelFreq.containsKey(lab)) {
					labelFreq.put(lab, 0);
				} 
				if (!seen.contains(lab)) {
					labelFreq.put(lab, labelFreq.get(lab) + 1);
					seen.add(lab);
				}
			}
			edgeIndexMap = instanceEdgeIndexMap.get(instances.get(i));
			for (Pair<DTLink<ApproxStringLabel,ApproxStringLabel>, Integer> edge : edgeIndexMap) {
				String lab = edge.getFirst().tag().toString();
				if (!labelFreq.containsKey(lab)) {
					labelFreq.put(lab, 0);
				} 
				if (!seen.contains(lab)) {
					labelFreq.put(lab, labelFreq.get(lab) + 1);
					seen.add(lab);
				}
			}
		}
	}
}