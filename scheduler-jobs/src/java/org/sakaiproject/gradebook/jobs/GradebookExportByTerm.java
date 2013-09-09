package org.sakaiproject.gradebook.jobs;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.Setter;
import lombok.extern.apachecommons.CommonsLog;

import org.apache.commons.lang.builder.ReflectionToStringBuilder;
import org.apache.commons.lang.math.NumberUtils;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.authz.api.AuthzGroup;
import org.sakaiproject.authz.api.AuthzGroupService;
import org.sakaiproject.authz.api.GroupNotDefinedException;
import org.sakaiproject.authz.api.Member;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.coursemanagement.api.AcademicSession;
import org.sakaiproject.coursemanagement.api.CourseManagementService;
import org.sakaiproject.event.api.EventTrackingService;
import org.sakaiproject.event.api.UsageSessionService;
import org.sakaiproject.gradebook.model.CSVHelper;
import org.sakaiproject.gradebook.model.Grade;
import org.sakaiproject.service.gradebook.shared.Assignment;
import org.sakaiproject.service.gradebook.shared.CategoryDefinition;
import org.sakaiproject.service.gradebook.shared.GradebookNotFoundException;
import org.sakaiproject.service.gradebook.shared.GradebookService;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.site.api.SiteService.SelectionType;
import org.sakaiproject.site.api.SiteService.SortType;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.tool.gradebook.Gradebook;
import org.sakaiproject.user.api.UserDirectoryService;
import org.sakaiproject.user.api.UserNotDefinedException;

import au.com.bytecode.opencsv.CSVWriter;


/**
 * Job to gradebook information for all students in all sites to a CSV
 * 
 * @author Steve Swinsburg (steve.swinsburg@gmail.com)
 *
 */
@CommonsLog
public class GradebookExportByTerm implements Job {

	private final String JOB_NAME=  "GradebookExportByTerm";
		
	private final String DATE_FORMAT_TIMESTAMP = "yyyy-MM-dd-HH-mm-ss";
	
	private final String FILE_GRADES = "gradebook.csv";

	
	public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
		
		log.info(JOB_NAME + "started: " + getDateAsString(DATE_FORMAT_TIMESTAMP));
		
		//get admin session
		establishSession(JOB_NAME);
		
		//get all sites that match the criteria
		List<Site> sites = getSites();
		
		for(Site s:sites) {
			//get the grades for each site
			//List<Grade> grades = getGradesForSite(s);
		
			//write out
			//writeGradesToCsv(s.getId(), grades);
		}
		
		
		log.info(JOB_NAME + " ended: " + getDateAsString(DATE_FORMAT_TIMESTAMP));
	}
	
	
	/**
	 * Get gradebook data
	 * @return
	 */
	private List<Grade> getGradesForSite(Site s) {
		
		List<Grade> grades = new ArrayList<Grade>();
		
		//get the sites
		List<Site> sites = getSites();
	
		//get members, skip if none
		List<Member> members = getUsersInSite(s.getId());
		
		if(members == null) {
			log.info("No members for site: " + s.getId() + ", skipping.");
			return grades;
		}
		
		//get gradebook for this site, skip if none
		Gradebook gradebook = null;
		try {
			gradebook = (Gradebook)gradebookService.getGradebook(s.getId());
		} catch (GradebookNotFoundException gbe) {
			log.info("No gradebook for site: " + s.getId() + ", skipping.");
			return grades;
		}
		
		//finalise the grades so we get an accurate export
		gradebookService.finalizeGrades(gradebook.getUid());
		
		//get the map of course grade data from the gradebook
		//Map of enrollment displayId as key, grade as value
		Map<String,String> calculatedCourseGrades = gradebookService.getCalculatedCourseGrade(gradebook.getUid());
		Map<String,String> enteredCourseGrades = gradebookService.getEnteredCourseGrade(gradebook.getUid());		
		
		//calculate the grades according to how the gradebook is configured
		switch (gradebook.getCategory_type()) {
            case GradebookService.CATEGORY_TYPE_NO_CATEGORY:
            	log.debug("Gradebook: " + gradebook.getUid() + " is of type 'no category'");
            	grades.addAll(getGradesIgnoringCategories(gradebook, members, s.getId(), calculatedCourseGrades, enteredCourseGrades));
           
            break;
            
            case GradebookService.CATEGORY_TYPE_ONLY_CATEGORY:
            	log.debug("Gradebook: " + gradebook.getUid() + " is of type 'only category'");
            	grades.addAll(getGradesIgnoringCategories(gradebook, members, s.getId(), calculatedCourseGrades, enteredCourseGrades));
            
            break;
            
            case GradebookService.CATEGORY_TYPE_WEIGHTED_CATEGORY:
            	log.debug("Gradebook: " + gradebook.getUid() + " is of type 'category and weighted'");
            	grades.addAll(getGradesInWeightedCategories(gradebook, members, s.getId(), calculatedCourseGrades, enteredCourseGrades));
            
            break;
		}
		
		return grades;
	}
	
	
	/**
	 * Get the grades for a student where there are no weightings. Since weightings are applied to categories we can also handle the case where we have
	 * assignments in categories, but no weightings.
	 * This gets the assignments in the gradebook and the grades for each with simple percentage calculations
	 * @param gradebook
	 * @param members
	 * @param siteId
	 * @param calculatedCourseGrades
	 * @param enteredCourseGrades
	 * @return
	 */
	private List<Grade> getGradesIgnoringCategories(Gradebook gradebook, List<Member> members, String siteId, Map<String,String> calculatedCourseGrades, Map<String,String> enteredCourseGrades) {
				
		List<Grade> grades = new ArrayList<Grade>();
				
		//for each site, get the assignments, tally up what the total points are for each assignment
		List<Assignment> assignments = gradebookService.getAssignments(gradebook.getUid());
		
		double totalPoints = 0;
		for(Assignment assignment:assignments) {
			totalPoints += assignment.getPoints();
		}
		
		//now for each student
		for(Member m: members) {
			
			Grade g = new Grade();
			g.setSiteId(siteId);
			g.setUserEid(m.getUserEid());
			
			//get the total points that this student scored for all assignments in the gradebook
			double points = 0;
			for(Assignment assignment:assignments) {
								
				try {
					Double score = NumberUtils.createDouble(gradebookService.getAssignmentScoreString(gradebook.getUid(), assignment.getId(), m.getUserId()));
					if(score != null) {
						points += score;
					}
				} catch (NumberFormatException nfe) {
					//ignore, continue with the rest
				}
			}
			g.setGradePoints(String.valueOf(points));
			
			//determine the percent
			double percent = points/totalPoints * 100;
			g.setGradePercent(String.valueOf(Math.round(percent)));
			
			//if we have an entered course grade, use that preferentially
			if(enteredCourseGrades.containsKey(m.getUserEid())) {
				g.setGradeLetter(enteredCourseGrades.get(m.getUserEid()));
			} else {
				g.setGradeLetter(calculatedCourseGrades.get(m.getUserEid()));
			}
			
			grades.add(g);
		}
		
		return grades;
	}
	
	/**
	 * Get the grades for a student where the assignments are in weighted categories
	 * This gets the assignments in each category and applies a weighted percentage calculation to the results for each student.
	 * @param gradebook
	 * @param members
	 * @param siteId
	 * @param calculatedCourseGrades
	 * @param enteredCourseGrades
	 * @return
	 */
	private List<Grade> getGradesInWeightedCategories(Gradebook gradebook, List<Member> members, String siteId, Map<String,String> calculatedCourseGrades, Map<String,String> enteredCourseGrades) {

		List<Grade> grades = new ArrayList<Grade>();
		
		//get the categories
		List<CategoryDefinition> categories = gradebookService.getCategoryDefinitions(gradebook.getUid());
				
		//for each student
		for(Member m: members) {
							
			Grade g = new Grade();
			g.setSiteId(siteId);
			g.setUserEid(m.getUserEid());
		
			double totalPointsEarnedOverall = 0;
			double weightedTotalPercentEarnedOverall = 0;
			
			//for each category 
			//get the list of assignments in each and get the student score for each assignment
			//calculate the percentage this student received in the category and apply the weighting
			//add the weighted percentages together for each category to get the final percent.
			//This depends on the fix to the Sakai edu-services in SAK-22118.
			
			//log.debug("------- USER: " + m.getUserEid());
			
			for(CategoryDefinition category: categories) {
				
				//log.debug("category: " + category.getName());
				
				List<Assignment> assignments = category.getAssignmentList();
				
				double totalPointsPossibleInCategory = 0;
				double totalPointsEarnedInCategory = 0;
				for(Assignment assignment:assignments) {
					totalPointsPossibleInCategory += assignment.getPoints();
				
					//log.debug("assignment points: " + assignment.getPoints());
					
					//get the students score for each assignment in this category and add them together
					try {
						Double score = NumberUtils.createDouble(gradebookService.getAssignmentScoreString(gradebook.getUid(), assignment.getId(), m.getUserId()));
						if(score != null) {
							totalPointsEarnedOverall += score;
							totalPointsEarnedInCategory += score;
						}
					} catch (NumberFormatException nfe) {
						//ignore, continue with the rest
					}
					
				}
				
				//log.debug("totalPointsEarnedInCategory: " + totalPointsEarnedInCategory);
				//log.debug("totalPointsPossibleInCategory: " + totalPointsPossibleInCategory);
				//log.debug("percent: " + totalPointsEarnedInCategory/totalPointsPossibleInCategory * 100);

				//calculate the weighted percentage for this category and add to tally
				weightedTotalPercentEarnedOverall += (totalPointsEarnedInCategory/totalPointsPossibleInCategory * 100) * category.getWeight();
				
				//log.debug("weighted percent for category: " + weightedTotalPercentEarnedOverall);
			}
			
			//log.debug("final weighted percent: " + weightedTotalPercentEarnedOverall);
		
			g.setGradePoints(String.valueOf(totalPointsEarnedOverall));
			g.setGradePercent(String.valueOf(Math.round(weightedTotalPercentEarnedOverall)));
			
			//if we have an entered course grade, use that preferentially
			if(enteredCourseGrades.containsKey(m.getUserEid())) {
				g.setGradeLetter(enteredCourseGrades.get(m.getUserEid()));
			} else {
				g.setGradeLetter(calculatedCourseGrades.get(m.getUserEid()));
			}
			
			grades.add(g);
		}
		
		return grades;
	}
	
	
	/**
	 * Write the list of courses to CSV
	 * @param courses
	 * @return
	 */
	private void writeGradesToCsv(List<Grade> grades) {
		
		String file = getOutputPath() + FILE_GRADES;
		
		//delete existing file so we know the data is current
		if(deleteFile(file)) {
			log.info("New file: "  +file);
		}
		
		CSVWriter writer;
		try {
			writer = new CSVWriter(new FileWriter(file), ',');
	   
			CSVHelper csv = new CSVHelper();
			
			//set the header
			csv.setHeader(new String[]{"siteid","eid","grade_letter","grade_points","grade_percent"});
			
			//convert each object into a string[] and add to the csv helper
			for(Grade g: grades) {
				
				log.debug(ReflectionToStringBuilder.toString(g));
				
				String[] row = {g.getSiteId(),g.getUserEid(),g.getGradeLetter(),g.getGradePoints(), g.getGradePercent()};
				csv.addRow(row);
			}
			
			//write header
			writer.writeNext(csv.getHeader());
			writer.writeAll(csv.getRows());
			
			writer.close();
			
			log.info("Successfully wrote CSV to: " + file);
	           
		} catch (IOException e) {
			log.equals("Error writing CSV: " + e.getClass() + " : " + e.getMessage());
		}
		
	}
	
	/**
	 * Start a session for the admin user and the given jobName
	 */
	private void establishSession(String jobName) {
		
		//set the user information into the current session
	    Session sakaiSession = sessionManager.getCurrentSession();
	    sakaiSession.setUserId("admin");
	    sakaiSession.setUserEid("admin");

	    //establish the user's session
	    usageSessionService.startSession("admin", "127.0.0.1", "gradebook-export");
	
	    //update the user's externally provided realm definitions
	    authzGroupService.refreshUser("admin");

	    //post the login event
	    eventTrackingService.post(eventTrackingService.newEvent(UsageSessionService.EVENT_LOGIN, null, true));
	}
	
	/**
	 * Get the current date as a string in the specified format
	 * @return
	 */
	private String getDateAsString(String format) {
		return getDateAsString(Calendar.getInstance().getTime(), format);
	}
	
	/**
	 * Get the given date as a string in the specified format
	 * @return
	 */
	private String getDateAsString(Date date, String format) {
		SimpleDateFormat dateFormat = new SimpleDateFormat(format);
		return dateFormat.format(date);
	}
	
	/**
	 * Get configurable output path. Defaults to /tmp
	 * @return
	 */
	private String getOutputPath() {
		return serverConfigurationService.getString("gradebook.export.path", "/tmp");
	}
	
	/**
	 * Get all sites that match the criteria, filter out special sites and my workspace sites
	 * @return
	 */
	private List<Site> getSites() {

		//setup property criteria
		//this could be extended to dynamically fill the map with properties and values from sakai.props
		Map<String, String> propertyCriteria = new HashMap<String,String>();
		propertyCriteria.put("term_eid", serverConfigurationService.getString("gradebook.export.term", getMostRecentTerm()));

		List<Site> sites = new ArrayList<Site>();
			
		List<Site> allSites = siteService.getSites(SelectionType.ANY, null, null, propertyCriteria, SortType.TITLE_ASC, null);		
		
		for(Site s: allSites) {
			//filter my workspace
			if(siteService.isUserSite(s.getId())){
				continue;
			}
			
			//filter special sites
			if(siteService.isSpecialSite(s.getId())){
				continue;
			}
			
			System.out.println("Site: " + s.getId());
			
			//otherwise add it
			sites.add(s);
		}
		
		return sites;
	}
	
	/**
	 * Get an eid for a given uuid
	 * @param uuid
	 * @return
	 */
	private String getUserEid(String uuid){
		try {
			return userDirectoryService.getUser(uuid).getEid();
		} catch (UserNotDefinedException e) {
			return null;
		}
	}
	
	/**
	 * Get the members of a site
	 * @param siteId
	 * @return
	 */
	private List<Member> getUsersInSite(String siteId) {
		
		try {
			String siteReference = siteService.siteReference(siteId);
			AuthzGroup authzGroup = authzGroupService.getAuthzGroup(siteReference);
			List<Member> maintainers = new ArrayList<Member>(authzGroup.getMembers());
			return maintainers;
		} catch (GroupNotDefinedException e) {
			return null;
		}
		
	}
	
	/**
	 * Helper to delete a file. Will only delete files, not directories.
	 * @param filePath	path to file to delete.
	 * @return
	 */
	private boolean deleteFile(String filePath) {
		try {
			File f = new File(filePath);
			
			//if doesn't exist, return true since we don't need to delete it
			if(!f.exists()) {
				return true;
			}
			
			//check it is a file and delete it
			if(f.isFile()) {
				return f.delete();
			}
			return false;
		} catch (Exception e) {
			return false;
		}
		
	}
	
	/**
	 * Get the most recent active term
	 * @return
	 */
	private String getMostRecentTerm() {
		
		List<AcademicSession> sessions = courseManagementService.getCurrentAcademicSessions();
		
		System.out.println("terms: " + sessions.size());

		for(AcademicSession as: sessions) {
			System.out.println("term: " + as.getEid());
		}
		
		return sessions.get(sessions.size()).getEid();

	}
	
	
	@Setter
	private SessionManager sessionManager;
	
	@Setter
	private UsageSessionService usageSessionService;
	
	@Setter
	private AuthzGroupService authzGroupService;
	
	@Setter
	private EventTrackingService eventTrackingService;
	
	@Setter
	private ServerConfigurationService serverConfigurationService;
	
	@Setter
	private SiteService siteService;
	
	@Setter
	private UserDirectoryService userDirectoryService;
	
	@Setter
	private GradebookService gradebookService;
	
	@Setter
	private CourseManagementService courseManagementService;
	
	
}
