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
package org.apache.hadoop.nfs.nfs3;

import log_events.org.apache.hadoop.NfsNamespace;
import org.ngmon.logger.core.SimpleLogger;
import org.ngmon.logger.logtranslator.LogGlobal;
import org.ngmon.logger.core.LoggerFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.oncrpc.RpcProgram;
import org.apache.hadoop.oncrpc.SimpleTcpServer;
import org.apache.hadoop.portmap.PortmapMapping;
import org.apache.hadoop.util.ShutdownHookManager;

/**
 * Nfs server. Supports NFS v3 using {@link RpcProgram}.
 * Currently Mountd program is also started inside this class.
 * Only TCP server is supported and UDP is not supported.
 */
public abstract class Nfs3Base {
  public static final /* LogLOG=LogFactory.getLog(Nfs3Base.class); */
			NfsNamespace LOG = LoggerFactory.getLogger(NfsNamespace.class, new SimpleLogger());
  private final RpcProgram rpcProgram;
  private final int nfsPort;
  private int nfsBoundPort; // Will set after server starts
    
  public RpcProgram getRpcProgram() {
    return rpcProgram;
  }

  protected Nfs3Base(RpcProgram rpcProgram, Configuration conf) {
    this.rpcProgram = rpcProgram;
    this.nfsPort = conf.getInt(Nfs3Constant.NFS3_SERVER_PORT,
        Nfs3Constant.NFS3_SERVER_PORT_DEFAULT);
    /* LOG.info("NFS server port set to: "+nfsPort) */
    LOG.nfs_server_port_set(nfsPort).info();
  }

  public void start(boolean register) {
    startTCPServer(); // Start TCP server
    
    if (register) {
      ShutdownHookManager.get().addShutdownHook(new Unregister(),
          SHUTDOWN_HOOK_PRIORITY);
      rpcProgram.register(PortmapMapping.TRANSPORT_TCP, nfsBoundPort);
    }
  }

  private void startTCPServer() {
    SimpleTcpServer tcpServer = new SimpleTcpServer(nfsPort,
        rpcProgram, 0);
    rpcProgram.startDaemons();
    tcpServer.run();
    nfsBoundPort = tcpServer.getBoundPort();
  }
  
  /**
   * Priority of the nfsd shutdown hook.
   */
  public static final int SHUTDOWN_HOOK_PRIORITY = 10;

  private class Unregister implements Runnable {
    @Override
    public synchronized void run() {
      rpcProgram.unregister(PortmapMapping.TRANSPORT_TCP, nfsBoundPort);
    }
  }
}
