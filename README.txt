###
### Gradebook Export Quartz Job
###
### Exports a csv for each site matching the criteria (see below)
### The CSV contains a single row for each user containing all grades for all assignments and course grade at the end
###
### NOTE: Requires opencsv in shared/lib
### Add this to sakai-src/deploy/shared/pom.xml

<dependency>
	<groupId>net.sf.opencsv</groupId>
	<artifactId>opencsv</artifactId>
	<version>2.3</version>
	<scope>compile</scope>
</dependency>

###
### sakai.properties
###

# gradebook export path
gradebook.export.path=/Users/steve/Desktop

# sites matching this term will be export. Leave blank to use the most current active term
gradebook.export.term=2013