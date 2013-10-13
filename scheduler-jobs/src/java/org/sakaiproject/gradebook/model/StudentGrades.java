package org.sakaiproject.gradebook.model;

import java.util.HashMap;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

/**
 * Model for a students grades in a course
 * 
 * @author Steve Swinsburg (steve.swinsburg@gmail.com)
 *
 */
public class StudentGrades {

	@Getter @Setter
	private String userEid;

	/**
	 * stores the grades for each assignment. Course grade is stored in COURSE_GRADE_ASSIGNMENT_ID.
	 */
	@Getter
	private Map<Long,String> grades;
	
	
	public StudentGrades(String userEid) {
		this.userEid = userEid;
	}
	
	
	/**
	 * Helper to add a grade to the list. Inits the list if it is empty
	 * @param assignmentId 	the id of the assignment
	 * @param grade 		the grade to add
	 */
	public void addGrade(long assignmentId, String grade) {
		if (grades == null) {
			grades = new HashMap<Long,String>();
		}
		
		grades.put(assignmentId, grade);
		
	}
	
	
	
}
