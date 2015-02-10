# Sakai Gradebook Export
A Quartz job for exporting gradebook data from Sakai to a CSV file.

Once CSV file is generated per site, and each file contains a single row for each user containing all grades for all assignments, and the course grade at the end. The sites to be exported can be configured in ``sakai.properties``.

## Building
```
mvn clean install sakai:deploy -Dmaven.tomcat.home=/path/to/tomcat
```

## Configuring

In ``sakai.properties`` set the following options:

The [ath where the exported CSV files will be saved:
```
gradebook.export.path=/Users/steve/Desktop
```

The sites matching this academic term will be exported. Leave this blank to use the most current active term
```
gradebook.export.term=2013
```
