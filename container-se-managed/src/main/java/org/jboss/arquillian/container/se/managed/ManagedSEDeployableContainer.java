/*
 * JBoss, Home of Professional Open Source
 * Copyright 2015, Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.arquillian.container.se.managed;

import java.io.BufferedReader;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import org.jboss.arquillian.container.se.api.ClassPath;
import org.jboss.arquillian.container.se.api.ClassPathDirectory;
import org.jboss.arquillian.container.se.managed.jmx.CustomJMXProtocol;
import org.jboss.arquillian.container.se.managed.util.Await;
import org.jboss.arquillian.container.se.managed.util.FileDeploymentUtils;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.JMXContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.protocol.jmx.JMXTestRunnerMBean;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.asset.ArchiveAsset;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.ClassAsset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;

public class ManagedSEDeployableContainer implements DeployableContainer<ManagedSEContainerConfiguration> {

    private static final Logger LOGGER = Logger.getLogger(ManagedSEDeployableContainer.class.getName());
    private static final String SYSPROP_KEY_JAVA_HOME = "java.home";
    private static final String DEBUG_AGENT_STRING = "-agentlib:jdwp=transport=dt_socket,address=8787,server=y,suspend=y";
    private static final String TARGET = "target";
    private static final String SERVER_MAIN_CLASS_FQN = "org.jboss.arquillian.container.se.server.Main";
    private static final String SYSTEM_PROPERTY_SWITCH = "-D";
    private static final String EQUALS = "=";

    private boolean debugModeEnabled;
    private boolean keepDeploymentArchives;
    private Process process;
    private List<File> materializedFiles;
    private Set<File> dependenciesJars;
    private String host;
    private int port;
    private String librariesPath;
    private List<String> additionalJavaOpts;
    private int waitTime;

    @Override
    public Class<ManagedSEContainerConfiguration> getConfigurationClass() {
        return ManagedSEContainerConfiguration.class;
    }

    public void setup(ManagedSEContainerConfiguration configuration) {
        debugModeEnabled = configuration.isDebug();
        host = configuration.getHost();
        port = configuration.getPort();
        materializedFiles = new ArrayList<>();
        librariesPath = configuration.getLibrariesPath();
        dependenciesJars = readJarFilesFromDirectory();
        keepDeploymentArchives = configuration.isKeepDeploymentArchives();
        additionalJavaOpts = initAdditionalJavaOpts(configuration.getAdditionalJavaOpts());
        configureLogging(configuration);
        waitTime = configuration.getWaitTime() > 0 ? configuration.getWaitTime() : 10;
    }

    private List<String> initAdditionalJavaOpts(String opts) {
        if (opts == null || opts.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> additionalOpts = new ArrayList<>();
        // TODO It may make sense to validate each option
        for (String option : opts.split("\\s+")) {
            additionalOpts.add(option);
        }
        return additionalOpts;
    }

    private void configureLogging(ManagedSEContainerConfiguration configuration) {
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(configuration.getLogLevel());
        LOGGER.setUseParentHandlers(false);
        LOGGER.addHandler(consoleHandler);
        LOGGER.setLevel(configuration.getLogLevel());
    }

    @Override
    public void start() throws LifecycleException {
    }

    @Override
    public void stop() throws LifecycleException {
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription(CustomJMXProtocol.NAME);
    }

    @Override
    public void deploy(Descriptor descriptor) throws DeploymentException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void undeploy(Descriptor descriptor) throws DeploymentException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        LOGGER.fine("Undeploying " + archive.getName());
        if (!keepDeploymentArchives) {
            for (File materializedFile : materializedFiles) {
                if (materializedFile.isDirectory()) {
                    try {
                        FileDeploymentUtils.deleteRecursively(materializedFile.toPath());
                    } catch (IOException e) {
                        LOGGER.warning("Could not delete materialized directory: " + materializedFile);
                    }
                } else {
                    materializedFile.delete();
                }
            }
        }
        // Kill the subprocess (test JVM)
        if (process != null) {
            process.destroy();
            try {
                process.waitFor();
            } catch (final InterruptedException e) {
                Thread.interrupted();
                throw new RuntimeException("Interrupted while awaiting server daemon process termination", e);
            }
        }
    }

    @Override
    public ProtocolMetaData deploy(final Archive<?> archive) throws DeploymentException {
        LOGGER.fine("Deploying " + archive.getName());

        // First of all clear the list of previously materialized deployments - otherwise the class path would grow indefinitely
        materializedFiles.clear();

        if (ClassPath.isRepresentedBy(archive)) {
            for (Node child : archive.get(ClassPath.ROOT_ARCHIVE_PATH).getChildren()) {
                Asset asset = child.getAsset();
                if (asset instanceof ArchiveAsset) {
                    Archive<?> assetArchive = ((ArchiveAsset) asset).getArchive();
                    if (ClassPathDirectory.isRepresentedBy(assetArchive)) {
                        materializeDirectory(assetArchive);
                    } else
                        materializeArchive(assetArchive);
                }
            }
        } else {
            materializeArchive(archive);
        }

        List<String> processCommand = buildProcessCommand(getSystemProperties(archive), getFileClassPathEntries(archive));
        logExecutedCommand(processCommand);
        // Launch the process
        final ProcessBuilder processBuilder = new ProcessBuilder(processCommand);
        processBuilder.redirectErrorStream(true);

        try {
            process = processBuilder.start();
            new Thread(new ConsoleConsumer()).start();
        } catch (final IOException e) {
            throw new DeploymentException("Could not start process", e);
        }

        int finalWaitTime = debugModeEnabled ? (3 * waitTime) : waitTime;

        // Wait for socket connection
        if (!isServerStarted(host, port, finalWaitTime)) {
            throw new DeploymentException("Child JVM process failed to start within " + finalWaitTime + " seconds.");
        }
        if (!isJMXTestRunnerMBeanRegistered(host, port, finalWaitTime)) {
            throw new DeploymentException("JMXTestRunnerMBean not registered within " + finalWaitTime + " seconds.");
        }

        ProtocolMetaData protocolMetaData = new ProtocolMetaData();
        protocolMetaData.addContext(new JMXContext(host, port));
        return protocolMetaData;
    }

    private Set<File> getFileClassPathEntries(Archive<?> archive) {
        Node node = archive.get(ClassPath.FILE_CLASSPATH_ENTRIES_ARCHIVE_PATH);
        if (node != null && (node.getAsset() instanceof StringAsset)) {
            Set<File> entries = new HashSet<>();
            StringAsset asset = (StringAsset) node.getAsset();
            BufferedReader reader = new BufferedReader(new StringReader(asset.getSource()));
            String line;
            try {
                while ((line = reader.readLine()) != null) {
                    File file = new File(line);
                    if (file.canRead() && file.isFile()) {
                        entries.add(file);
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Unable to read file class path entries", e);
                return Collections.emptySet();
            }
            return entries;
        }
        return Collections.emptySet();
    }

    private Properties getSystemProperties(final Archive<?> archive) throws DeploymentException {
        Node systemPropertiesNode = archive.get(ClassPath.SYSTEM_PROPERTIES_ARCHIVE_PATH);
        if (systemPropertiesNode != null) {
            try (InputStream in = systemPropertiesNode.getAsset().openStream()) {
                Properties systemProperties = new Properties();
                systemProperties.load(in);
                return systemProperties;
            } catch (IOException e) {
                throw new DeploymentException("Could not load system properties", e);
            }
        }
        return null;
    }

    private boolean isServerStarted(final String host, final int port, int waitTime) {
        return new Await(waitTime, new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                try (Socket ignored = new Socket(host, port)) {
                    return true;
                }
            }
        }).start();
    }

    private boolean isJMXTestRunnerMBeanRegistered(final String host, final int port, int waitTime) throws DeploymentException {
        // Taken from org.jboss.arquillian.container.spi.client.protocol.metadata.JMXContext
        final String jmxServiceUrl = "service:jmx:rmi:///jndi/rmi://" + host + ":" + port + "/jmxrmi";
        try (JMXConnector jmxc = JMXConnectorFactory.connect(new JMXServiceURL(jmxServiceUrl), null)) {
            final MBeanServerConnection mbsc = jmxc.getMBeanServerConnection();
            return new Await(waitTime, new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    mbsc.getObjectInstance(new ObjectName(JMXTestRunnerMBean.OBJECT_NAME));
                    LOGGER.fine("JMXTestRunnerMBean registered with the remote MBean server at: " + jmxServiceUrl);
                    return true;
                }
            }).start();
        } catch (IOException e) {
            throw new DeploymentException("Could not verify JMXTestRunnerMBean registration", e);
        }
    }

    private void materializeArchive(Archive<?> archive) {
        File deploymentFile = new File(TARGET.concat(File.separator).concat(archive.getName()));
        archive.as(ZipExporter.class).exportTo(deploymentFile, true);
        materializedFiles.add(deploymentFile);
    }

    private void materializeDirectory(Archive<?> archive) throws DeploymentException {
        if (archive.getContent().isEmpty()) {
            // Do not materialize an empty directory
            return;
        }
        File entryDirectory = new File(TARGET.concat(File.separator).concat(archive.getName()));
        try {
            if (entryDirectory.exists()) {
                // Always delete previous content
                FileDeploymentUtils.deleteContent(entryDirectory.toPath());
            } else {
                if (!entryDirectory.mkdirs()) {
                    throw new DeploymentException("Could not create class path directory: " + entryDirectory);
                }
            }
            for (Node child : archive.get(ClassPath.ROOT_ARCHIVE_PATH).getChildren()) {
                Asset asset = child.getAsset();
                if (asset instanceof ClassAsset) {
                    FileDeploymentUtils.materializeClass(entryDirectory, (ClassAsset) asset);
                } else if (asset == null) {
                    FileDeploymentUtils.materializeSubdirectories(entryDirectory, child);
                }
            }
        } catch (IOException e) {
            throw new DeploymentException("Could not materialize class path directory: " + archive.getName(), e);
        }
        materializedFiles.add(entryDirectory);
    }

    private List<String> buildProcessCommand(Properties properties, Set<File> fileClassPathEntries) {
        final List<String> command = new ArrayList<String>();
        final File javaHome = new File(System.getProperty(SYSPROP_KEY_JAVA_HOME));
        command.add(javaHome.getAbsolutePath() + File.separator + "bin" + File.separator + "java");
        command.add("-cp");
        StringBuilder builder = new StringBuilder();
        Set<File> classPathEntries = new HashSet<>(materializedFiles);
        classPathEntries.addAll(fileClassPathEntries);
        classPathEntries.addAll(dependenciesJars);
        for (Iterator<File> iterator = classPathEntries.iterator(); iterator.hasNext(); ) {
            builder.append(iterator.next().getPath());
            if (iterator.hasNext()) {
                builder.append(File.pathSeparator);
            }
        }
        command.add(builder.toString());
        command.add("-Dcom.sun.management.jmxremote");
        command.add("-Dcom.sun.management.jmxremote.port=" + port);
        command.add("-Dcom.sun.management.jmxremote.authenticate=false");
        command.add("-Dcom.sun.management.jmxremote.ssl=false");

        if (debugModeEnabled) {
            command.add(DEBUG_AGENT_STRING);
        }
        for (String option : additionalJavaOpts) {
            command.add(option);
        }
        if (properties != null) {
            for (Map.Entry<Object, Object> entry : properties.entrySet()) {
                addSystemProperty(command, entry.getKey().toString(), entry.getValue().toString());
            }
        }
        command.add(SERVER_MAIN_CLASS_FQN);
        return command;
    }

    private void addSystemProperty(List<String> command, String key, String value) {
        command.add(SYSTEM_PROPERTY_SWITCH + key + EQUALS + value);
    }

    private Set<File> readJarFilesFromDirectory() {
        if (librariesPath == null) {
            return Collections.emptySet();
        }
        LOGGER.info("Add libraries from: " + librariesPath);
        Set<File> libraries = new HashSet<>();
        File lib = new File(librariesPath);
        if (!lib.exists() || lib.isFile()) {
            throw new IllegalStateException("Cannot read files from " + librariesPath);
        }
        File[] files = lib.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".jar");
            }
        });
        Collections.addAll(libraries, files);
        return libraries;
    }

    private void logExecutedCommand(List<String> processCommand) {
        if (LOGGER.isLoggable(Level.FINE)) {
            StringBuilder builder = new StringBuilder();
            for (String s : processCommand) {
                builder.append(s);
                builder.append(" ");
            }
            LOGGER.log(Level.FINE, "Executing command: " + builder);
        }
    }

    // taken from org.jboss.as.arquillian.container.managed.ManagedDeployableContainer
    private class ConsoleConsumer implements Runnable {

        @Override
        public void run() {
            final InputStream stream = process.getInputStream();

            try {
                byte[] buf = new byte[32];
                int num;
                // Do not try reading a line cos it considers '\r' end of line
                while ((num = stream.read(buf)) != -1) {
                    System.out.write(buf, 0, num);
                }
            } catch (IOException e) {
            }
        }

    }

}
