/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.ha;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import log_events.org.apache.hadoop.HaNamespace;
import org.ngmon.logger.core.SimpleLogger;
import org.ngmon.logger.logtranslator.LogGlobal;
import org.ngmon.logger.core.LoggerFactory;
import org.apache.hadoop.conf.Configured;

import com.google.common.annotations.VisibleForTesting;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

/**
 * This fencing implementation sshes to the target node and uses 
 * <code>fuser</code> to kill the process listening on the service's
 * TCP port. This is more accurate than using "jps" since it doesn't 
 * require parsing, and will work even if there are multiple service
 * processes running on the same machine.<p>
 * It returns a successful status code if:
 * <ul>
 * <li><code>fuser</code> indicates it successfully killed a process, <em>or</em>
 * <li><code>nc -z</code> indicates that nothing is listening on the target port
 * </ul>
 * <p>
 * This fencing mechanism is configured as following in the fencing method
 * list:
 * <code>sshfence([[username][:ssh-port]])</code>
 * where the optional argument specifies the username and port to use
 * with ssh.
 * <p>
 * In order to achieve passwordless SSH, the operator must also configure
 * <code>dfs.ha.fencing.ssh.private-key-files<code> to point to an
 * SSH key that has passphrase-less access to the given username and host.
 */
public class SshFenceByTcpPort extends Configured
  implements FenceMethod {

  static final /* LogLOG=LogFactory.getLog(SshFenceByTcpPort.class); */
			HaNamespace LOG = LoggerFactory.getLogger(HaNamespace.class, new SimpleLogger());
  
  static final String CONF_CONNECT_TIMEOUT_KEY =
    "dfs.ha.fencing.ssh.connect-timeout";
  private static final int CONF_CONNECT_TIMEOUT_DEFAULT =
    30*1000;
  static final String CONF_IDENTITIES_KEY =
    "dfs.ha.fencing.ssh.private-key-files";

  /**
   * Verify that the argument, if given, in the conf is parseable.
   */
  @Override
  public void checkArgs(String argStr) throws BadFencingConfigurationException {
    if (argStr != null) {
      new Args(argStr);
    }
  }

  @Override
  public boolean tryFence(HAServiceTarget target, String argsStr)
      throws BadFencingConfigurationException {

    Args args = new Args(argsStr);
    InetSocketAddress serviceAddr = target.getAddress();
    String host = serviceAddr.getHostName();
    
    Session session;
    try {
      session = createSession(serviceAddr.getHostName(), args);
    } catch (JSchException e) {
      /* LOG.warn("Unable to create SSH session",e) */
      LOG.unable_create_ssh_session(e.toString()).warn();
      return false;
    }

    /* LOG.info("Connecting to "+host+"...") */
    LOG.connecting(host).info();
    
    try {
      session.connect(getSshConnectTimeout());
    } catch (JSchException e) {
      /* LOG.warn("Unable to connect to "+host+" as user "+args.user,e) */
      LOG.unable_connect_user(host, String.valueOf(args.user), e.toString()).tag("methodCall").warn();
      return false;
    }
    /* LOG.info("Connected to "+host) */
    LOG.connected(host).info();

    try {
      return doFence(session, serviceAddr);
    } catch (JSchException e) {
      /* LOG.warn("Unable to achieve fencing on remote host",e) */
      LOG.unable_achieve_fencing_remote_host(e.toString()).warn();
      return false;
    } finally {
      session.disconnect();
    }
  }


  private Session createSession(String host, Args args) throws JSchException {
    JSch jsch = new JSch();
    for (String keyFile : getKeyFiles()) {
      jsch.addIdentity(keyFile);
    }
    JSch.setLogger(new LogAdapter());

    Session session = jsch.getSession(args.user, host, args.sshPort);
    session.setConfig("StrictHostKeyChecking", "no");
    return session;
  }

  private boolean doFence(Session session, InetSocketAddress serviceAddr)
      throws JSchException {
    int port = serviceAddr.getPort();
    try {
      /* LOG.info("Looking for process running on port "+port) */
      LOG.looking_for_process_running_port(port).info();
      int rc = execCommand(session,
          "PATH=$PATH:/sbin:/usr/sbin fuser -v -k -n tcp " + port);
      if (rc == 0) {
        /* LOG.info("Successfully killed process that was "+"listening on port "+port) */
        LOG.successfully_killed_process_that_was_lis(port).info();
        // exit code 0 indicates the process was successfully killed.
        return true;
      } else if (rc == 1) {
        // exit code 1 indicates either that the process was not running
        // or that fuser didn't have root privileges in order to find it
        // (eg running as a different user)
        /* LOG.info("Indeterminate response from trying to kill service. "+"Verifying whether it is running using nc...") */
        LOG.indeterminate_response_from_trying_kill_().info();
        rc = execCommand(session, "nc -z " + serviceAddr.getHostName() +
            " " + serviceAddr.getPort());
        if (rc == 0) {
          // the service is still listening - we are unable to fence
          /* LOG.warn("Unable to fence - it is running but we cannot kill it") */
          LOG.unable_fence_running_but_cannot_kill().warn();
          return false;
        } else {
          /* LOG.info("Verified that the service is down.") */
          LOG.verified_that_service_down().info();
          return true;          
        }
      } else {
        // other 
      }
      /* LOG.info("rc: "+rc) */
      LOG.autogenerated(rc).info();
      return rc == 0;
    } catch (InterruptedException e) {
      /* LOG.warn("Interrupted while trying to fence via ssh",e) */
      LOG.interrupted_while_trying_fence_via_ssh(e.toString()).warn();
      return false;
    } catch (IOException e) {
      /* LOG.warn("Unknown failure while trying to fence via ssh",e) */
      LOG.unknown_failure_while_trying_fence_via(e.toString()).warn();
      return false;
    }
  }
  
  /**
   * Execute a command through the ssh session, pumping its
   * stderr and stdout to our own logs.
   */
  private int execCommand(Session session, String cmd)
      throws JSchException, InterruptedException, IOException {
    /* LOG.debug("Running cmd: "+cmd) */
    LOG.running_cmd(cmd).debug();
    ChannelExec exec = null;
    try {
      exec = (ChannelExec)session.openChannel("exec");
      exec.setCommand(cmd);
      exec.setInputStream(null);
      exec.connect();

      // Pump stdout of the command to our WARN logs
//      StreamPumper outPumper = new StreamPumper(LOG, cmd + " via ssh",
//          exec.getInputStream(), StreamPumper.StreamType.STDOUT);
//      outPumper.start();
      
      // Pump stderr of the command to our WARN logs
//      StreamPumper errPumper = new StreamPumper(LOG, cmd + " via ssh",
//          exec.getErrStream(), StreamPumper.StreamType.STDERR);
//      errPumper.start();
      
//      outPumper.join();
//      errPumper.join();
      return exec.getExitStatus();
    } finally {
      cleanup(exec);
    }
  }

  private static void cleanup(ChannelExec exec) {
    if (exec != null) {
      try {
        exec.disconnect();
      } catch (Throwable t) {
        /* LOG.warn("Couldn't disconnect ssh channel",t) */
        LOG.couldnt_disconnect_ssh_channel(t.toString()).warn();
      }
    }
  }

  private int getSshConnectTimeout() {
    return getConf().getInt(
        CONF_CONNECT_TIMEOUT_KEY, CONF_CONNECT_TIMEOUT_DEFAULT);
  }

  private Collection<String> getKeyFiles() {
    return getConf().getTrimmedStringCollection(CONF_IDENTITIES_KEY);
  }
  
  /**
   * Container for the parsed arg line for this fencing method.
   */
  @VisibleForTesting
  static class Args {
    private static final Pattern USER_PORT_RE = Pattern.compile(
      "([^:]+?)?(?:\\:(\\d+))?");

    private static final int DEFAULT_SSH_PORT = 22;

    String user;
    int sshPort;
    
    public Args(String arg) 
        throws BadFencingConfigurationException {
      user = System.getProperty("user.name");
      sshPort = DEFAULT_SSH_PORT;

      // Parse optional user and ssh port
      if (arg != null && !arg.isEmpty()) {
        Matcher m = USER_PORT_RE.matcher(arg);
        if (!m.matches()) {
          throw new BadFencingConfigurationException(
              "Unable to parse user and SSH port: "+ arg);
        }
        if (m.group(1) != null) {
          user = m.group(1);
        }
        if (m.group(2) != null) {
          sshPort = parseConfiggedPort(m.group(2));
        }
      }
    }

    private Integer parseConfiggedPort(String portStr)
        throws BadFencingConfigurationException {
      try {
        return Integer.valueOf(portStr);
      } catch (NumberFormatException nfe) {
        throw new BadFencingConfigurationException(
            "Port number '" + portStr + "' invalid");
      }
    }
  }

  /**
   * Adapter from JSch's logger interface to our log4j
   */
  private static class LogAdapter implements com.jcraft.jsch.Logger {
    static final /* LogLOG=LogFactory.getLog(SshFenceByTcpPort.class.getName()+".jsch"); */
			HaNamespace LOG = LoggerFactory.getLogger(HaNamespace.class, new SimpleLogger());

    @Override
    public boolean isEnabled(int level) {
      switch (level) {
      case com.jcraft.jsch.Logger.DEBUG:
        return LogGlobal.isDebugEnabled();
      case com.jcraft.jsch.Logger.INFO:
        return LogGlobal.isInfoEnabled();
      case com.jcraft.jsch.Logger.WARN:
        return LogGlobal.isWarnEnabled();
      case com.jcraft.jsch.Logger.ERROR:
        return LogGlobal.isErrorEnabled();
      case com.jcraft.jsch.Logger.FATAL:
        return LogGlobal.isFatalEnabled();
      default:
        return false;
      }
    }
      
    @Override
    public void log(int level, String message) {
      switch (level) {
      case com.jcraft.jsch.Logger.DEBUG:
        /* LOG.debug(message) */
        LOG.message(message).debug();
        break;
      case com.jcraft.jsch.Logger.INFO:
        /* LOG.info(message) */
        LOG.message(message).info();
        break;
      case com.jcraft.jsch.Logger.WARN:
        /* LOG.warn(message) */
        LOG.message(message).warn();
        break;
      case com.jcraft.jsch.Logger.ERROR:
        /* LOG.error(message) */
        LOG.message(message).error();
        break;
      case com.jcraft.jsch.Logger.FATAL:
        /* LOG.fatal(message) */
        LOG.message(message).fatal();
        break;
      }
    }
  }
}
