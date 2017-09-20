package com.irisa.swpatterns.data;

import java.util.LinkedList;
import java.util.List;

import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;

public class RDFPatternValuePath extends RDFPatternComponent {

	public RDFPatternValuePath(List<RDFNode> l, Type type) {
		super(new RDFPatternElement(l), type);
	}

	@Override
	public List toList() {
		LinkedList list = new LinkedList();
		list.addAll(this.getElement().getList());
		list.add(getType());
		return list;
	}

}
