package org.jboss.arquillian.daemon.container.managed;

import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ManagedDaemonDeployableContainerTest {

    private final ManagedDaemonDeployableContainer managedDaemonDeployableContainer = new ManagedDaemonDeployableContainer();

    @Test
    public void should_generate_cli_command_with_defined_host_and_port() {
        // given
        final ManagedDaemonContainerConfiguration managedDaemonContainerConfiguration = ManagedDaemonContainerConfiguration.create()
                .withHost("custom-host")
                .withPort(9999)
                .withServerJarFile("arq-daemon.jar")
                .build();

        managedDaemonDeployableContainer.setup(managedDaemonContainerConfiguration);

        // when
        final List<String> commands = managedDaemonDeployableContainer.buildCommand();

        // then
        assertThat(commands)
                .containsSubsequence("-jar", "custom-host", "9999")
                .doesNotContain("-agentlib:jdwp=transport=dt_socket,address=8181,server=y,suspend=y");
    }

    @Test
    public void should_generate_cli_command_with_debug_agent() {
        // given
        final ManagedDaemonContainerConfiguration managedDaemonContainerConfiguration = ManagedDaemonContainerConfiguration.create()
                .withHost("localhost")
                .withPort(8080)
                .withServerJarFile("arq-daemon.jar")
                .withDebug()
                .withSuspend()
                .withDebugPort(8181)
                .build();

        managedDaemonDeployableContainer.setup(managedDaemonContainerConfiguration);

        // when
        final List<String> commands = managedDaemonDeployableContainer.buildCommand();

        // then
        assertThat(commands).containsSequence("-agentlib:jdwp=transport=dt_socket,address=8181,server=y,suspend=y", "-jar");
    }

}