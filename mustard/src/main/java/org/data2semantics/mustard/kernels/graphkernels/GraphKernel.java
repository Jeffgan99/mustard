package org.data2semantics.mustard.kernels.graphkernels;

import org.data2semantics.mustard.kernels.Kernel;
import org.data2semantics.mustard.kernels.data.GraphData;




/**
 * GraphKernel interface
 * 
 * TODO add compute for train and test split
 * 
 */
public interface GraphKernel<G extends GraphData> extends Kernel {
		
	public double[][] compute(G data);
	//public double[][] compute(List<G> trainGraphs, List<G> testGraphs);
	
}
