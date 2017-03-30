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
package org.jboss.arquillian.daemon.server;

import java.net.InetSocketAddress;

import org.jboss.arquillian.daemon.protocol.wire.WireProtocol;

/**
 * Defines operations for the Arquillian Server Daemon; other non-Java API operations achieved via the
 * {@link WireProtocol}.
 *
 * @author <a href="mailto:alr@jboss.org">Andrew Lee Rubinger</a>
 */
public interface Server {

    // These exposed as part of the API to help callers using reflection (for instance to avoid CCE if accessing the
    // server from a different ClassLoader)
    String METHOD_NAME_START = "start";
    Class<?>[] METHOD_PARAMS_START = new Class<?>[] {};
    String METHOD_NAME_STOP = "stop";
    Class<?>[] METHOD_PARAMS_STOP = new Class<?>[] {};

    /**
     * Starts the server
     *
     * @throws ServerLifecycleException
     * @throws IllegalStateException
     *     If the server is already running
     */
    void start() throws ServerLifecycleException, IllegalStateException;

    /**
     * Stops the server
     *
     * @throws ServerLifecycleException
     * @throws IllegalStateException
     *     If the server is not running
     */
    void stop() throws ServerLifecycleException, IllegalStateException;

    /**
     * Returns whether or not the server is running
     */
    boolean isRunning();

    /**
     * Obtains the address to which the current running server is bound
     *
     * @throws IllegalStateException
     *     If {@link Server#isRunning()} is <code>false</code>
     */
    InetSocketAddress getBoundAddress() throws IllegalStateException;
}
