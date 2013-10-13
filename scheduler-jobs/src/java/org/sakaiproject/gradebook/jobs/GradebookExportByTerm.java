package org.sakaiproject.gradebook.jobs;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.Setter;
import lombok.extern.apachecommons.CommonsLog;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.authz.api.AuthzGroupService;
import org.sakaiproject.authz.api.Member;
import org.sakaiproject.authz.api.SecurityService;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.coursemanagement.api.AcademicSession;
import org.sakaiproject.coursemanagement.api.CourseManagementService;
import org.sakaiproject.event.api.EventTrackingService;
import org.sakaiproject.event.api.UsageSessionService;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.gradebook.model.CSVHelper;
import org.sakaiproject.gradebook.model.StudentGrades;
import org.sakaiproject.service.gradebook.shared.Assignment;
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

	private final String JOB_NAME = "GradebookExportByTerm";
	private final long COURSE_GRADE_ASSIGNMENT_ID = -1; // becasue no gradeable object in Sakai should have this value

		
	public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
		
		log.info(JOB_NAME + " started.");
		
		//get admin session
		establishSession(JOB_NAME);
		
		//get all sites that match the criteria
		List<Site> sites = getSites();
		
		for(Site s:sites) {
			
			//get the grades for each site
			List<StudentGrades> grades = getGradesForSite(s);
		
			if(!grades.isEmpty()) {			
				//write out
				writeGradesToCsv(s.getId(), grades);
			}
		}
		
		
		log.info(JOB_NAME + " ended.");
	}
	
	
	/**
	 * Get gradebook data
	 * @return
	 */
	private List<StudentGrades> getGradesForSite(Site s) {
		
		List<StudentGrades> grades = new ArrayList<StudentGrades>();
		
		log.debug("Processing site: " + s.getId() + " - " + s.getTitle());
			
		//get members, skip if none
		List<Member> members = getUsersInSite(s.getId());
		Collections.sort(members);
		
		if(members == null || members.isEmpty()) {
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
		
		//get list of assignments in gradebook, skip if none
		List<Assignment> assignments = gradebookService.getAssignments(gradebook.getUid());
		
		if(assignments == null || assignments.isEmpty()) {
			log.info("No assignments for site: " + s.getId() + ", skipping.");
			return grades;
		}
		
		log.debug("Assignments size: " + assignments.size());

		//get course grades and use entered grades preferentially
        Map<String, String> courseGrades = gradebookService.getCalculatedCourseGrade(gradebook.getUid()); 
        Map<String, String> enteredGrades = gradebookService.getEnteredCourseGrade(gradebook.getUid());
        courseGrades.putAll(enteredGrades);
        
		//for each member, get the assignment results for each assignment, with course grade at the end
		for(Member m: members) {
			
			if(!isValidMember(s, m)) {
				log.debug("INVALID!!!!");
			}
			
			
			StudentGrades g = new StudentGrades(m.getUserEid());

			log.debug("Member: " + m.getUserEid());
			
			//if a user has no grade for the assignment ensure it is not missed
			for(Assignment a: assignments) {
				
				log.debug("Assignment: " + a.getId() + ": " + a.getName());
				
				String points = gradebookService.getAssignmentScoreString(gradebook.getUid(), a.getId(), m.getUserId());
				g.addGrade(a.getId(), points);

				log.debug("Points: " + points);
			}
			
			//add the course grade
			g.addGrade(COURSE_GRADE_ASSIGNMENT_ID, courseGrades.get(m.getUserId()));
			
			log.debug("Course Grade: " + courseGrades.get(m.getUserId()));
			
			grades.add(g);
		}
		
		return grades;
	}
	
	
	
	
	
	/**
	 * Write the list of grades to CSV for the site
	 * @param siteId - id of site, used for filename
	 * @param grades - list of Grades
	 * @return
	 */
	private void writeGradesToCsv(String siteId, List<StudentGrades> grades) {
		
		String file;
		if (StringUtils.endsWith(getOutputPath(), File.pathSeparator)) {
			file = getOutputPath() + siteId + ".csv";
		} else {
			file = getOutputPath() + File.pathSeparator + siteId + ".csv";
		}
				
		//delete existing file so we know the data is current
		if(deleteFile(file)) {
			log.debug("New file: " + file);
		}
		
		CSVWriter writer;
		try {
			writer = new CSVWriter(new FileWriter(file), ',');
	   
			CSVHelper csv = new CSVHelper();
			
			//set the CSV header from the assignment titles and add additional fields
			List<Assignment> assignments = gradebookService.getAssignments(siteId);
			
			List<String> header = new ArrayList<String>();
			header.add("Student ID");
			header.add("Student Name");
			
			for(Assignment a: assignments) {
				header.add(a.getName());
			}
			
			header.add("Course Grade");
			
			csv.setHeader(header.toArray(new String[header.size()]));
			
			//create a formatted list of data using the grade records info and user info, using the order of the assignment list
			//this puts it in the order we need for the CSV
			for(StudentGrades sg: grades) {
				
				List<String> row = new ArrayList<String>();
				
				//add name details
				row.add(sg.getUserEid());
				row.add(getDisplayName(sg.getUserEid()));		
				
				//add grades
				Map<Long,String> g = sg.getGrades();
				for(Assignment a: assignments) {
					row.add(g.get(a.getId()));
				}
				
				//add course grade
				row.add(g.get(COURSE_GRADE_ASSIGNMENT_ID));
				
				log.debug("Row: " + row);

				csv.addRow(row.toArray(new String[row.size()]));
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
	 * Get configurable output path. Defaults to /tmp
	 * @return
	 */
	private String getOutputPath() {
		return serverConfigurationService.getString("gradebook.export.path", FileUtils.getTempDirectoryPath());
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
		
		log.debug("Sites match: " + allSites.size());

		for(Site s: allSites) {
			//filter my workspace
			if(siteService.isUserSite(s.getId())){
				continue;
			}
			
			//filter special sites
			if(siteService.isSpecialSite(s.getId())){
				continue;
			}
			
			log.debug("Site: " + s.getId());
			
			//otherwise add it
			sites.add(s);
		}
		
		return sites;
	}
	
	/**
	 * Get a display name given an eid
	 * @param eid	eid of the user
	 * @return
	 */
	private String getDisplayName(String eid){
		try {
			return userDirectoryService.getUserByEid(eid).getDisplayName();
		} catch (UserNotDefinedException e) {
			return null;
		}
	}
	
	/**
	 * Get the members of a site
	 * @param siteId
	 * @return list or null if site is bad
	 */
	private List<Member> getUsersInSite(String siteId) {
		
		try {
			return new ArrayList<Member>(siteService.getSite(siteId).getMembers());
		} catch (IdUnusedException e) {
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
		
		log.debug("terms: " + sessions.size());

		if(sessions.isEmpty()) {
			return null;
		}
				
		for(AcademicSession as: sessions) {
			log.debug("term: " + as.getEid());
		}
		
		return sessions.get(sessions.size()-1).getEid();

	}
	
	/**
	 * Checks if a member is valid. Sometimes the AuthzGroupService can return phantom users that no longer exist.
	 * This will ensure that list is scrubbed.
	 * @param s		the Site
	 * @param m		the Member to check
	 * @return
	 */
	private boolean isValidMember(Site s, Member m) {
		
		System.out.println("MEMBER ROLE: " + m.getRole());
		
		//if(securityService.unlock(m.getUserId(), SiteService.S, site.getReference()))
		
		
		return true;
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
	
	@Setter
	private SecurityService securityService;
	
	
	
}
