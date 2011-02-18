package org.apache.jmeter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.security.Permission;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.jmeter.util.ShutdownClient;

/**
 * JMeter Maven plugin.
 *
 * @author Tim McCune
 * @goal jmeter
 */
public class JMeterMojo extends AbstractMojo {

    private static final Pattern PAT_ERROR = Pattern.compile(".*\\s+ERROR\\s+.*");
    // TODO Provide the same for excludes!
    /**
     * @parameter expression="${jmeter.includefiles}"
     */
    private String includeFiles;
    /**
     * @parameter 
     */
    private List<String> includes;
    /**
     * @parameter
     */
    private List<String> excludes;
    /**
     * @parameter expression="${basedir}/src/test/jmeter"
     */
    private File srcDir;
    /**
     * @parameter expression="jmeter-reports"
     */
    private File reportDir;
    /**
     * @parameter 
     */
    private File jmeterProps;
    /**
     * @parameter expression="${settings.localRepository}"
     */
    private File repoDir;
    /**
     * JMeter Properties to be overridden
     *
     * @parameter
     */
    private Map jmeterUserProperties;
    /**
     * JMeter Properties to be overridden
     *
     * @parameter
     */
    private Map jmeterJavaProperties;
    /**
     * @parameter
     */
    private boolean remote;
    /**
     * JMeter.log log level.
     * @parameter expression="INFO"
     */
    private String jmeterLogLevel;
    /**
     * @parameter expression="${project}"
     */
    private org.apache.maven.project.MavenProject mavenProject;
    /**
     * HTTP proxy host name.
     * @parameter
     */
    private String proxyHost;
    /**
     * HTTP proxy port.
     * @parameter expression="80"
     */
    private Integer proxyPort;
    /**
     * HTTP proxy username.
     * @parameter
     */
    private String proxyUsername;
    /**
     * HTTP proxy user password.
     * @parameter
     */
    private String proxyPassword;
    private File workDir;
    private File saveServiceProps;
    private File upgradeProps;
    private File jmeterLog;
    private DateFormat fmt = new SimpleDateFormat("yyMMdd");
    private JMeter jmeterInstance;

    /**
     * Run all JMeter tests.
     */
    public void execute() throws MojoExecutionException, MojoFailureException {
        initSystemProps();
        try {
            DirectoryScanner scanner = new DirectoryScanner();
            scanner.setBasedir(srcDir);
            if (null != includeFiles) {
                if (null != includes) {
                    getLog().debug("Overwriting configured includes list by includefiles = '" + includeFiles + "'");
                } else {
                    getLog().debug("Using includefiles = '" + includeFiles + "'");
                }
                scanner.setIncludes(new String[]{includeFiles});
            } else if (null == includes) {
                getLog().debug("Using default includes");
                scanner.setIncludes(new String[]{"**/*.jmx"});
            } else {
                getLog().debug("Using configured includes");
                scanner.setIncludes(includes.toArray(new String[]{}));
            }
            if (excludes != null) {
                scanner.setExcludes(excludes.toArray(new String[]{}));
            }
            scanner.scan();
            String[] finalIncludes = scanner.getIncludedFiles();
            getLog().debug("Finally using test files" + StringUtils.join(finalIncludes, ", "));
            for (String file : finalIncludes) {
                
                executeTest(new File(srcDir, file));
                
                try {
                    // Force shutdown
                    ShutdownClient.main(new String[]{"Shutdown"});
                } catch (IOException ex) {
                    getLog().error(ex);
                }
                
            }
            checkForErrors();
        } finally {
            saveServiceProps.delete();
            upgradeProps.delete();
        }
    }

    private void checkForErrors() throws MojoExecutionException, MojoFailureException {
        try {
            BufferedReader in = new BufferedReader(new FileReader(jmeterLog));
            String line;
            while ((line = in.readLine()) != null) {
                if (PAT_ERROR.matcher(line).find()) {
                    throw new MojoFailureException("There were test errors, see logfile '" + jmeterLog + "' for further information");
                }
            }
            in.close();
        } catch (IOException e) {
            throw new MojoExecutionException("Can't read log file", e);
        }
    }

    private void initSystemProps() throws MojoExecutionException {
        
        // Init JMeter
        jmeterInstance = new JMeter();
        
        workDir = new File("target" + File.separator + "jmeter");
        workDir.mkdirs();
        createSaveServiceProps();

        // now create lib dir for jmeter fallback mode
        File libDir = new File("target" + File.separator + "jmeter" + File.separator + "lib");
        if (!libDir.exists()) {
            libDir.mkdirs();
            libDir = new File("target" + File.separator + "jmeter"
                    + File.separator + "lib" + File.separator + "ext");
            if (!libDir.exists()) {
                libDir.mkdirs();
            }
            libDir = new File("target" + File.separator + "jmeter"
                    + File.separator + "lib" + File.separator + "junit");
            if (!libDir.exists()) {
                libDir.mkdirs();
            }
        }

        jmeterLog = new File(workDir, "jmeter.log");
        try {
            System.setProperty("log_file", jmeterLog.getCanonicalPath());
        } catch (IOException e) {
            throw new MojoExecutionException("Can't get canonical path for log file", e);
        }
    }

    /**
     * This mess is necessary because JMeter must load this info from a file.
     * Do the same for the upgrade.properties and jmeter.properties
     * Resources won't work.
     */
    private void createSaveServiceProps() throws MojoExecutionException {
        
        File binDir = new File("target" + File.separator + "jmeter" + File.separator + "bin");
        if (!binDir.exists()) {
            binDir.mkdirs();
        }
        saveServiceProps = new File(binDir, "saveservice.properties");
        upgradeProps = new File(binDir, "upgrade.properties");
        
        FileWriter out;
       
        try {
            
            out = new FileWriter(saveServiceProps);
            IOUtils.copy(Thread.currentThread().getContextClassLoader().getResourceAsStream("saveservice.properties"), out);
            out.flush();
            out.close();
            System.setProperty("saveservice_properties",
                    File.separator + "bin" + File.separator
                    + "saveservice.properties");
        } catch (IOException e) {
            throw new MojoExecutionException("Could not create temporary saveservice.properties", e);
        }
        
        try{
            
            out = new FileWriter(upgradeProps);
            IOUtils.copy(Thread.currentThread().getContextClassLoader().getResourceAsStream("upgrade.properties"), out);
            out.flush();
            out.close();
            System.setProperty("upgrade_properties",
                    File.separator + "bin" + File.separator
                    + "upgrade.properties");
            
        } catch (IOException e) {
            throw new MojoExecutionException("Could not create temporary upgrade.properties", e);
        }
        
        try{
            
             // if the properties file is not specified in the papameters
            if(jmeterProps == null){
                
                getLog().info("Loading default jmeter.properties...");
                
                jmeterProps = new File(binDir, "jmeter.properties");
            
                out = new FileWriter(jmeterProps);
                IOUtils.copy(Thread.currentThread().getContextClassLoader().getResourceAsStream("jmeter.properties"), out);
                out.flush();
                out.close();
                System.setProperty("jmeter_properties",
                        File.separator + "bin" + File.separator
                        + "jmeter.properties");
                
            }
            
        } catch (IOException e) {
            throw new MojoExecutionException("Could not create temporary upgrade.properties", e);
        }
        
        // TODO: retrieve classpath from pom.xml dependencies
        String searchPaths = new StringBuilder().append(repoDir.toString()).append("/org/apache/jmeter/ApacheJMeter_components/2.4/ApacheJMeter_components-2.4.jar;").append(repoDir.toString()).append("/org/apache/jmeter/ApacheJMeter_core/2.4/ApacheJMeter_core-2.4.jar;").append(repoDir.toString()).append("/org/apache/jmeter/ApacheJMeter_ftp/2.4/ApacheJMeter_ftp-2.4.jar;").append(repoDir.toString()).append("/org/apache/jmeter/ApacheJMeter_functions/2.4/ApacheJMeter_functions-2.4.jar;").append(repoDir.toString()).append("/org/apache/jmeter/ApacheJMeter_http/2.4/ApacheJMeter_http-2.4.jar;").append(repoDir.toString()).append("/org/apache/jmeter/ApacheJMeter_java/2.4/ApacheJMeter_java-2.4.jar;").append(repoDir.toString()).append("/org/apache/jmeter/ApacheJMeter_jdbc/2.4/ApacheJMeter_jdbc-2.4.jar;").append(repoDir.toString()).append("/org/apache/jmeter/ApacheJMeter_jms/2.4/ApacheJMeter_jms-2.4.jar;") // FIXME: Missing libs?
                .append(repoDir.toString()).append("/org/apache/jmeter/ApacheJMeter_junit/2.4/ApacheJMeter_junit-2.4.jar;")
                .append(repoDir.toString()).append("/org/apache/jmeter/ApacheJMeter_monitor/2.4/ApacheJMeter_monitor-2.4.jar;")
                .append(repoDir.toString()).append("/org/apache/jmeter/ApacheJMeter_ldap/2.4/ApacheJMeter_ldap-2.4.jar;").append(repoDir.toString()).append("/org/apache/jmeter/ApacheJMeter_mail/2.4/ApacheJMeter_mail-2.4.jar;").append(repoDir.toString()).append("/org/apache/jmeter/ApacheJMeter_reports/2.4/ApacheJMeter_reports-2.4.jar;").append(repoDir.toString()).append("/org/apache/jmeter/ApacheJMeter_tcp/2.4/ApacheJMeter_tcp-2.4.jar").toString();
        System.setProperty("search_paths", searchPaths);
    }

    /**
     * Executes a single JMeter test by building up a list of command line
     * parameters to pass to JMeter.start().
     */
    private void executeTest(File test) throws MojoExecutionException {
        try {
            getLog().info("Executing test: " + test.getCanonicalPath());
            String reportFileName = test.getName().substring(0,
                    test.getName().lastIndexOf(".")) + "-"
                    + fmt.format(new Date()) + ".xml";
            List<String> argsTmp = Arrays.asList("-n",
                    "-t", test.getCanonicalPath(),
                    "-l", reportDir.toString() + File.separator + reportFileName,
                    "-p", jmeterProps.toString(),
                    "-d", System.getProperty("user.dir") + File.separator
                    + "target" + File.separator + "jmeter",
                    "-L", "jorphan=" + jmeterLogLevel,
                    "-L", "jmeter.util=" + jmeterLogLevel);

            List<String> args = new ArrayList<String>();
            args.addAll(argsTmp);
            args.addAll(getUserProperties());
            args.addAll(getJavaProperties());

            if (remote) {
                args.add("-r");
            }

            if (proxyHost != null && !proxyHost.equals("")) {
                args.add("-H");
                args.add(proxyHost);
                args.add("-P");
                args.add(proxyPort.toString());
                getLog().info("Setting HTTP proxy to " + proxyHost + ":" + proxyPort);
            }

            if (proxyUsername != null && !proxyUsername.equals("")) {
                args.add("-u");
                args.add(proxyUsername);
                args.add("-a");
                args.add(proxyPassword);
                getLog().info("Logging with " + proxyUsername + ":" + proxyPassword);
            }

            // This mess is necessary because JMeter likes to use System.exit.
            // We need to trap the exit call.
            SecurityManager oldManager = System.getSecurityManager();
            System.setSecurityManager(new SecurityManager()  {

                @Override
                public void checkExit(int status) {
                    throw new ExitException(status);
                }

                @Override
                public void checkPermission(Permission perm, Object context) {
                }

                @Override
                public void checkPermission(Permission perm) {
                }
            });
            UncaughtExceptionHandler oldHandler = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler()  {

                public void uncaughtException(Thread t, Throwable e) {
                    if (e instanceof ExitException && ((ExitException) e).getCode() == 0) {
                        return;    //Ignore
                    }
                    getLog().error("Error in thread " + t.getName());
                }
            });

            try {
                // This mess is necessary because the only way to know when JMeter
                // is done is to wait for its test end message!
                logParamsAndProps(args);
                jmeterInstance.start(args.toArray(new String[]{}));
                BufferedReader in = new BufferedReader(new FileReader(jmeterLog));
                while (!checkForEndOfTest(in)) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                
            } catch (ExitException e) {
                if (e.getCode() != 0) {
                    throw new MojoExecutionException("Test failed", e);
                }
            } finally {
                System.setSecurityManager(oldManager);
                Thread.setDefaultUncaughtExceptionHandler(oldHandler);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Can't execute test", e);
        }
    }

    private void logParamsAndProps(List<String> args) {
        getLog().debug("Starting JMeter with the following parameters:");
        for (String arg : args) {
            getLog().debug(arg);
        }
        Properties props = System.getProperties();
        Set<Object> keysUnsorted = props.keySet();
        SortedSet<Object> keys = new TreeSet<Object>(keysUnsorted);
        getLog().debug("... and the following properties:");
        for (Object k : keys) {
            String key = (String) k;
            String value = props.getProperty(key);
            getLog().debug(key + " = " + value);
        }
    }

    private boolean checkForEndOfTest(BufferedReader in) throws MojoExecutionException {
        boolean testEnded = false;
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.indexOf("Test has ended") != -1) {
                    testEnded = true;
                    break;
                }
            }

        } catch (IOException e) {
            throw new MojoExecutionException("Can't read log file", e);
        }
        return testEnded;
    }

    private ArrayList<String> getUserProperties() {
        ArrayList<String> propsList = new ArrayList<String>();
        if (jmeterUserProperties == null) {
            return propsList;
        }
        Set<String> keySet = (Set<String>) jmeterUserProperties.keySet();

        for (String key : keySet) {

            propsList.add("-J");
            propsList.add(key + "=" + jmeterUserProperties.get(key));
        }

        return propsList;
    }

    private ArrayList<String> getJavaProperties() {
        ArrayList<String> propsList = new ArrayList<String>();
        if (jmeterJavaProperties == null) {
            return propsList;
        }
        Set<String> keySet = (Set<String>) jmeterJavaProperties.keySet();

        for (String key : keySet) {

            propsList.add("-D");
            propsList.add(key + "=" + jmeterJavaProperties.get(key));
        }

        return propsList;
    }

    private static class ExitException extends SecurityException {

        private static final long serialVersionUID = 5544099211927987521L;
        public int _rc;

        public ExitException(int rc) {
            super(Integer.toString(rc));
            _rc = rc;
        }

        public int getCode() {
            return _rc;
        }
    }
}
