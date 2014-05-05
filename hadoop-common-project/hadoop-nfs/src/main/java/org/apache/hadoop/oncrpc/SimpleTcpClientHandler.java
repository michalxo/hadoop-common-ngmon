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
package org.apache.hadoop.oncrpc;

import log_events.org.apache.hadoop.OncrpcNamespace;
import org.ngmon.logger.core.SimpleLogger;
import org.ngmon.logger.logtranslator.LogGlobal;
import org.ngmon.logger.core.LoggerFactory;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;

/**
 * A simple TCP based RPC client handler used by {@link SimpleTcpServer}.
 */
public class SimpleTcpClientHandler extends SimpleChannelHandler {
  public static final /* LogLOG=LogFactory.getLog(SimpleTcpClient.class); */
			OncrpcNamespace LOG = LoggerFactory.getLogger(OncrpcNamespace.class, new SimpleLogger());
  protected final XDR request;

  public SimpleTcpClientHandler(XDR request) {
    this.request = request;
  }

  @Override
  public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
    // Send the request
    if (LogGlobal.isDebugEnabled()) {
      /* LOG.debug("sending PRC request") */
      LOG.sending_prc_request().debug();
    }
    ChannelBuffer outBuf = XDR.writeMessageTcp(request, true);
    e.getChannel().write(outBuf);
  }

  /**
   * Shutdown connection by default. Subclass can override this method to do
   * more interaction with the server.
   */
  @Override
  public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) {
    e.getChannel().close();
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
    /* LOG.warn("Unexpected exception from downstream: ",e.getCause()) */
    LOG.unexpected_exception_from_downstream(String.valueOf(e.getCause())).tag("methodCall").warn();
    e.getChannel().close();
  }
}
