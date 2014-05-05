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

package org.apache.hadoop.mapreduce.v2.security.client;

import java.util.Collection;

import log_events.org.apache.hadoop.MapreduceNamespace;
import org.ngmon.logger.core.SimpleLogger;
import org.ngmon.logger.logtranslator.LogGlobal;
import org.ngmon.logger.core.LoggerFactory;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.v2.api.MRDelegationTokenIdentifier;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.security.token.TokenSelector;

public class ClientHSTokenSelector implements
    TokenSelector<MRDelegationTokenIdentifier> {

  private static final /* LogLOG=LogFactory.getLog(ClientHSTokenSelector.class); */
			MapreduceNamespace LOG = LoggerFactory.getLogger(MapreduceNamespace.class, new SimpleLogger());

  @SuppressWarnings("unchecked")
  public Token<MRDelegationTokenIdentifier> selectToken(Text service,
      Collection<Token<? extends TokenIdentifier>> tokens) {
    if (service == null) {
      return null;
    }
    /* LOG.debug("Looking for a token with service "+service.toString()) */
    LOG.looking_for_token_with_service(String.valueOf(service.toString())).tag("methodCall").debug();
    for (Token<? extends TokenIdentifier> token : tokens) {
      if (LogGlobal.isDebugEnabled()) {
        /* LOG.debug("Token kind is "+token.getKind().toString()+" and the token's service name is "+token.getService()) */
        LOG.token_kind_and_tokens_service_name(String.valueOf(token.getKind().toString()), String.valueOf(token.getService())).tag("methodCall").debug();
      }
      if (MRDelegationTokenIdentifier.KIND_NAME.equals(token.getKind())
          && service.equals(token.getService())) {
        return (Token<MRDelegationTokenIdentifier>) token;
      }
    }
    return null;
  }
}
