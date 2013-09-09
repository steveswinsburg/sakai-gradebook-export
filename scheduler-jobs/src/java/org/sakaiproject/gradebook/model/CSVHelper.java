package org.sakaiproject.gradebook.model;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * Helper to store the data of a CSV file before it is written out.
 * 
 * @author Steve Swinsburg (steve.swinsburg@gmail.com)
 *
 */
public class CSVHelper {
	 
	@Getter @Setter
	private String[] header;
	
	@Getter @Setter
	private List<String[]> rows;
	
	public CSVHelper() {
		rows = new ArrayList<String[]>();
	}

	public void addRow(String[] r) {
		rows.add(r);
	}
	
}
