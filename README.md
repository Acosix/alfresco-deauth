[![Build Status](https://travis-ci.org/Acosix/alfresco-deauth.svg?branch=master)](https://travis-ci.org/Acosix/alfresco-deauth)

# About

This addon aims to provide functionality to simplify deauthorisation of users so Alfresco Enterprise Edition customers can reduce the effort to comply with their specific subscription terms and keep user counts within the allowed range.

## Compatbility

This addon has been built to be compatible with Alfresco Enterprise Edition 5.1+ and Java 8.

## Features

### Inactive user deauthorisation
Based on the (_acosix-audit_ module)[https://github.com/Acosix/alfresco-audit] this addon provides the means to bulk-deauthorise users that have been inactive for a specific duration of time. Inactivity is determined using the same logic as in the Audit-based web scripts to query active / inactive users of the _acosix-audit_ module. The Repository-tier web script at URL _/alfresco/s/acosix/api/deauth/inactiveUsers_ may be called with a POST request to trigger bulk-deauthorisation.

Parameters:
- lookBackMode - mode/unit for defining the time frame; default value: months, allowed values: days, months, years
- lookBackAmount - number of units for defining the time frame, default value: 1 (mode=years), 3 (mode=months), 90 (mode=days)
- workerThreads - the amount of parallel execution for the inactive user query phase, default value: 4
- batchSize  - the size of individual batches, default value: 20

Note that the actual bulk-deauthorisation work will be done in single-threaded batches to avoid conflicts and inconsistent state in the Alfresco AuthorisationService, which has been found in practice to not behave properly in a highly concurrent scenario.

By default, the web script will use the exact same configuration as the inactive user query web script of the _acosix-audit_ module. This means that it will also by default use the _acosix-audit-activeUsers_ audit application as the source of data. This can be reconfigured to use any audit application, e.g. the default _alfresco-access_. All configuration properties share the same prefix of _acosix-deauth.web.script.deauthoriseInactiveUser._. The following properties are supported:

- _auditApplicationName_ - the name of the audit application to use (default: _acosix-audit-activeUsers_)
- _userAuditPath_ - the path to the user name within the audit data to filter queries against; if empty, the user name associated with the audit entry will be used to query
- _dateFromAuditPath_ - the path to a date or ISO 8601 string value within the audit data that denotes the start of a timeframe in which the user was active; must be set together with _dateToAuditPath_ (default: _/acosix-audit-activeUsers/timeframeStart_)
- _dateToAuditPath_ - the path to a date or ISO 8601 string value within the audit data that denotes the end of a timeframe in which the user was active; must be set together with _dateFromAuditPath_ (default: _/acosix-audit-activeUsers/timeframeEnd_)
- _dateAuditPath_ - the path to a date or ISO 8601 string value within the audit data that denotes an effective date at which the user was active

If none of the date-related configuration properties are set to a valid constellation, the date of the audit entries will be used as input to the report of the web scripts.

Reports are provided in JSON or CSV format, with JSON being the default if a specific format is not reqeusted by using the URL parameter _?format=xxx_ or adding a file extension to the URL.

# Maven usage

This addon is being built using the [Acosix Alfresco Maven framework](https://github.com/Acosix/alfresco-maven) and produces both AMP and installable JAR artifacts. Depending on the setup of a project that wants to include the addon, different approaches can be used to include it in the build.

## Build

This project can be build simply by executing the standard Maven build lifecycles for package, install or deploy depending on the intent for further processing. A Java Development Kit (JDK) version 8 or higher is required for the build.

## Dependency in Alfresco SDK

The simplest option to include the addon in an All-in-One project is by declaring a dependency to the installable JAR artifact. Alternatively, the AMP package may be included which typically requires additional configuration in addition to the dependency.

### Using SNAPSHOT builds

In order to use a pre-built SNAPSHOT artifact published to the Open Source Sonatype Repository Hosting site, the artifact repository may need to be added to the POM, global settings.xml or an artifact repository proxy server. The following is the XML snippet for inclusion in a POM file.

```xml
<repositories>
    <repository>
        <id>ossrh</id>
        <url>https://oss.sonatype.org/content/repositories/snapshots</url>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>
</repositories>
```

### Repository

```xml
<dependency>
    <groupId>de.acosix.alfresco.utility</groupId>
    <artifactId>de.acosix.alfresco.utility.common</artifactId>
    <version>1.0.2.0</version>
    <type>jar</type>
</dependency>

<dependency>
    <groupId>de.acosix.alfresco.utility</groupId>
    <artifactId>de.acosix.alfresco.utility.repo</artifactId>
    <version>1.0.2.0</version>
    <type>jar</type>
    <classifier>installable</classifier>
</dependency>

<dependency>
    <groupId>de.acosix.alfresco.audit</groupId>
    <artifactId>de.acosix.alfresco.audit.repo</artifactId>
    <version>1.0.0.0</version>
    <type>jar</type>
    <classifier>installable</classifier>
</dependency>

<dependency>
    <groupId>de.acosix.alfresco.deauth</groupId>
    <artifactId>de.acosix.alfresco.deauth.repo</artifactId>
    <version>1.0.0.0-SNAPSHOT</version>
    <type>jar</type>
    <classifier>installable</classifier>
</dependency>

<!-- OR -->

<!-- AMP packaging -->
<dependency>
    <groupId>de.acosix.alfresco.utility</groupId>
    <artifactId>de.acosix.alfresco.utility.repo</artifactId>
    <version>1.0.2.0</version>
    <type>amp</type>
</dependency>

<dependency>
    <groupId>de.acosix.alfresco.audit</groupId>
    <artifactId>de.acosix.alfresco.audit.repo</artifactId>
    <version>1.0.0.0</version>
    <type>amp</type>
</dependency>

<dependency>
    <groupId>de.acosix.alfresco.deauth</groupId>
    <artifactId>de.acosix.alfresco.deauth.repo</artifactId>
    <version>1.0.0.0-SNAPSHOT</version>
    <type>amp</type>
</dependency>

<plugin>
    <artifactId>maven-war-plugin</artifactId>
    <configuration>
        <overlays>
            <overlay />
            <overlay>
                <groupId>${alfresco.groupId}</groupId>
                <artifactId>${alfresco.repo.artifactId}</artifactId>
                <type>war</type>
                <excludes />
            </overlay>
            <!-- other AMPs -->
            <overlay>
                <groupId>de.acosix.alfresco.utility</groupId>
                <artifactId>de.acosix.alfresco.utility.repo</artifactId>
                <type>amp</type>
            </overlay>
            <overlay>
                <groupId>de.acosix.alfresco.audit</groupId>
                <artifactId>de.acosix.alfresco.audit.repo</artifactId>
                <type>amp</type>
            </overlay>
            <overlay>
                <groupId>de.acosix.alfresco.deauth</groupId>
                <artifactId>de.acosix.alfresco.deauth.repo</artifactId>
                <type>amp</type>
            </overlay>
        </overlays>
    </configuration>
</plugin>
```

For Alfresco SDK 3 beta users:

```xml
<platformModules>
    <moduleDependency>
        <groupId>de.acosix.alfresco.utility</groupId>
        <artifactId>de.acosix.alfresco.utility.repo</artifactId>
        <version>1.0.2.0</version>
        <type>amp</type>
    </moduleDependency>
    <moduleDependency>
        <groupId>de.acosix.alfresco.audit</groupId>
        <artifactId>de.acosix.alfresco.audit.repo</artifactId>
        <version>1.0.0.0</version>
        <type>amp</type>
    <moduleDependency>
        <groupId>de.acosix.alfresco.deauth</groupId>
        <artifactId>de.acosix.alfresco.deauth.repo</artifactId>
        <version>1.0.0.0-SNAPSHOT</version>
        <type>amp</type>
    </moduleDependency>
</platformModules>
```

# Other installation methods

Using Maven to build the Alfresco WAR is the **recommended** approach to install this module. As an alternative it can be installed manually.

## alfresco-mmt.jar / apply_amps

The default Alfresco installer creates folders *amps* and *amps_share* where you can place AMP files for modules which Alfresco will install when you use the apply_amps script. Place the AMP for the *de.acosix.alfresco.deauth.repo* module in the *amps* directory, the *de.acosix.alfresco.deauth.share* module in the *amps_share* directory, and execute the script to install them. You must restart Alfresco for the installation to take effect.

Alternatively you can use the alfresco-mmt.jar to install the modules as [described in the documentation](http://docs.alfresco.com/5.1/concepts/dev-extensions-modules-management-tool.html).

## Manual "installation" using JAR files

Some addons and some other sources on the net suggest that you can install **any** addon by putting their JARs in a path like &lt;tomcat&gt;/lib, &lt;tomcat&gt;/shared or &lt;tomcat&gt;/shared/lib. This is **not** correct. Only the most trivial addons / extensions can be installed that way - "trivial" in this case means that these addons have no Java class-level dependencies on any component that Alfresco ships, e.g. addons that only consist of static resources, configuration files or web scripts using pure JavaScript / Freemarker.

The only way to manually install an addon using JARs that is **guaranteed** not to cause Java classpath issues is by dropping the JAR files directly into the &lt;tomcat&gt;/webapps/alfresco/WEB-INF/lib (Repository-tier) or &lt;tomcat&gt;/webapps/share/WEB-INF/lib (Share-tier) folders.

For this addon the following JARs need to be dropped into &lt;tomcat&gt;/webapps/alfresco/WEB-INF/lib:

 - de.acosix.alfresco.utility.common-&lt;version&gt;.jar
 - de.acosix.alfresco.utility.repo-&lt;version&gt;-installable.jar
 - de.acosix.alfresco.audit.repo-&lt;version&gt;-installable.jar
 - de.acosix.alfresco.deauth.repo-&lt;version&gt;-installable.jar
 
If Alfresco has been setup by using the official installer, another, **explicitly recommended** way to install the module manually would be by dropping the JAR(s) into the &lt;alfresco&gt;/modules/platform (Repository-tier) or &lt;alfresco&gt;/modules/share (Share-tier) folders.
