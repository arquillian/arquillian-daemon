/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012, Red Hat Middleware LLC, and individual contributors
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
package org.jboss.arquillian.daemon.container.managed;

import org.jboss.arquillian.container.spi.ConfigurationException;
import org.jboss.arquillian.container.spi.client.container.ContainerConfiguration;
import org.jboss.arquillian.daemon.container.common.DaemonContainerConfigurationBase;

import java.io.File;
import java.security.AccessController;
import java.security.PrivilegedAction;

/**
 * {@link ContainerConfiguration} implementation for Managed Containers
 *
 * @author <a href="mailto:alr@jboss.org">Andrew Lee Rubinger</a>
 */
public class ManagedDaemonContainerConfiguration extends DaemonContainerConfigurationBase implements
        ContainerConfiguration {

    private String javaHome = SecurityActions.getSystemProperty("java.home");

    private boolean debug;

    private boolean suspend = true;

    private int debugPort = 54321;

    private String serverJarFile;

    @Override
    public void validate() throws ConfigurationException {
        super.validate();
        if (serverJarFile == null || serverJarFile.length() == 0) {
            throw new ConfigurationException("\"serverJarFile\" must be specified");
        }

        if (debugPort < 1 || debugPort > 65536) {
            throw new ConfigurationException("port must be specified within the range of [1-65563]");
        }

        final File file = new File(serverJarFile);
        if (!file.exists() || file.isDirectory()) {
            throw new ConfigurationException("Server JAR file must exist and not be a directory: "
                    + file.getAbsolutePath());
        }
    }

    public String getServerJarFile() {
        return serverJarFile;
    }

    public void setServerJarFile(final String serverJarFile) {
        this.serverJarFile = serverJarFile;
    }

    public String getJavaHome() {
        return javaHome;
    }

    public void setJavaHome(String javaHome) {
        this.javaHome = javaHome;
    }

    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    public boolean isSuspend() {
        return suspend;
    }

    public void setSuspend(boolean suspend) {
        this.suspend = suspend;
    }

    public int getDebugPort() {
        return debugPort;
    }

    public void setDebugPort(int debugPort) {
        this.debugPort = debugPort;
    }

    private static final class SecurityActions {
        private SecurityActions() {
            throw new UnsupportedOperationException("No instance permitted");
        }

        static String getSystemProperty(final String key) {
            assert key != null && key.length() > 0 : "key must be specified";
            if (System.getSecurityManager() == null) {
                return System.getProperty(key);
            }
            return AccessController.doPrivileged(new PrivilegedAction<String>() {
                @Override
                public String run() {
                    return System.getProperty(key);
                }
            });
        }
    }

    public static ManagedDaemonContainerConfigurationBuilder create() {
        return new ManagedDaemonContainerConfigurationBuilder();
    }

    public static final class ManagedDaemonContainerConfigurationBuilder {
        private String host;
        private Integer port;
        private String javaHome = SecurityActions.getSystemProperty("java.home");
        private boolean debug;
        private boolean suspend = true;
        private int debugPort = 54321;
        private String serverJarFile;

        private ManagedDaemonContainerConfigurationBuilder() {
        }


        public ManagedDaemonContainerConfigurationBuilder withHost(String host) {
            this.host = host;
            return this;
        }

        public ManagedDaemonContainerConfigurationBuilder withPort(Integer port) {
            this.port = port;
            return this;
        }

        public ManagedDaemonContainerConfigurationBuilder withJavaHome(String javaHome) {
            this.javaHome = javaHome;
            return this;
        }

        public ManagedDaemonContainerConfigurationBuilder withDebug() {
            this.debug = true;
            return this;
        }

        public ManagedDaemonContainerConfigurationBuilder withSuspend() {
            this.suspend = true;
            return this;
        }

        public ManagedDaemonContainerConfigurationBuilder withDebugPort(int debugPort) {
            this.debugPort = debugPort;
            return this;
        }

        public ManagedDaemonContainerConfigurationBuilder withServerJarFile(String serverJarFile) {
            this.serverJarFile = serverJarFile;
            return this;
        }

        public ManagedDaemonContainerConfiguration build() {
            ManagedDaemonContainerConfiguration managedDaemonContainerConfiguration = new ManagedDaemonContainerConfiguration();
            managedDaemonContainerConfiguration.setHost(host);
            managedDaemonContainerConfiguration.setPort(port);
            managedDaemonContainerConfiguration.setJavaHome(javaHome);
            managedDaemonContainerConfiguration.setDebug(debug);
            managedDaemonContainerConfiguration.setSuspend(suspend);
            managedDaemonContainerConfiguration.setDebugPort(debugPort);
            managedDaemonContainerConfiguration.setServerJarFile(serverJarFile);
            return managedDaemonContainerConfiguration;
        }
    }
}
