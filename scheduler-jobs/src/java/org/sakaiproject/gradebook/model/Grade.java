package org.sakaiproject.gradebook.model;

import lombok.Data;

/**
 * Model for a students grade in a course
 * 
 * @author Steve Swinsburg (steve.swinsburg@gmail.com)
 *
 */
@Data
public class Grade {

	private String siteId;
	private String userEid;
	private String gradeLetter;
	private String gradePoints;
	private String gradePercent;
	
}
