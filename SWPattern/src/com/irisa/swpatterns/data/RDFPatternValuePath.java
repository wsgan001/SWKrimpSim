package com.irisa.swpatterns.data;

import java.util.List;

import org.apache.jena.rdf.model.Resource;

public class RDFPatternValuePath extends RDFPatternComponent {

	public RDFPatternValuePath(List<? extends Resource> l, Type type) {
		super(new RDFPatternElement(l), type);
	}

	@Override
	public List<Object> toList() {
		// TODO Auto-generated method stub
		return null;
	}

}
