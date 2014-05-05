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

package org.apache.hadoop.mapreduce.v2.hs;

import java.io.IOException;
import java.util.Map.Entry;

import log_events.org.apache.hadoop.MapreduceNamespace;
import org.ngmon.logger.core.SimpleLogger;
import org.ngmon.logger.logtranslator.LogGlobal;
import org.ngmon.logger.core.LoggerFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.mapreduce.v2.api.MRDelegationTokenIdentifier;
import org.apache.hadoop.mapreduce.v2.hs.HistoryServerStateStoreService.HistoryServerState;
import org.apache.hadoop.security.token.delegation.AbstractDelegationTokenSecretManager;
import org.apache.hadoop.security.token.delegation.DelegationKey;

/**
 * A MapReduce specific delegation token secret manager.
 * The secret manager is responsible for generating and accepting the password
 * for each token.
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable
public class JHSDelegationTokenSecretManager
    extends AbstractDelegationTokenSecretManager<MRDelegationTokenIdentifier> {

  private static final /* LogLOG=LogFactory.getLog(JHSDelegationTokenSecretManager.class); */
			MapreduceNamespace LOG = LoggerFactory.getLogger(MapreduceNamespace.class, new SimpleLogger());

  private HistoryServerStateStoreService store;

  /**
   * Create a secret manager
   * @param delegationKeyUpdateInterval the number of seconds for rolling new
   *        secret keys.
   * @param delegationTokenMaxLifetime the maximum lifetime of the delegation
   *        tokens
   * @param delegationTokenRenewInterval how often the tokens must be renewed
   * @param delegationTokenRemoverScanInterval how often the tokens are scanned
   *        for expired tokens
   * @param store history server state store for persisting state
   */
  public JHSDelegationTokenSecretManager(long delegationKeyUpdateInterval,
                                      long delegationTokenMaxLifetime, 
                                      long delegationTokenRenewInterval,
                                      long delegationTokenRemoverScanInterval,
                                      HistoryServerStateStoreService store) {
    super(delegationKeyUpdateInterval, delegationTokenMaxLifetime,
          delegationTokenRenewInterval, delegationTokenRemoverScanInterval);
    this.store = store;
  }

  @Override
  public MRDelegationTokenIdentifier createIdentifier() {
    return new MRDelegationTokenIdentifier();
  }

  @Override
  protected void storeNewMasterKey(DelegationKey key) throws IOException {
    if (LogGlobal.isDebugEnabled()) {
      /* LOG.debug("Storing master key "+key.getKeyId()) */
      LOG.storing_master_key(String.valueOf(key.getKeyId())).tag("methodCall").debug();
    }
    try {
      store.storeTokenMasterKey(key);
    } catch (IOException e) {
      /* LOG.error("Unable to store master key "+key.getKeyId(),e) */
      LOG.unable_store_master_key(String.valueOf(key.getKeyId()), e.toString()).tag("methodCall").error();
    }
  }

  @Override
  protected void removeStoredMasterKey(DelegationKey key) {
    if (LogGlobal.isDebugEnabled()) {
      /* LOG.debug("Removing master key "+key.getKeyId()) */
      LOG.removing_master_key(String.valueOf(key.getKeyId())).tag("methodCall").debug();
    }
    try {
      store.removeTokenMasterKey(key);
    } catch (IOException e) {
      /* LOG.error("Unable to remove master key "+key.getKeyId(),e) */
      LOG.unable_remove_master_key(String.valueOf(key.getKeyId()), e.toString()).tag("methodCall").error();
    }
  }

  @Override
  protected void storeNewToken(MRDelegationTokenIdentifier tokenId,
      long renewDate) {
    if (LogGlobal.isDebugEnabled()) {
      /* LOG.debug("Storing token "+tokenId.getSequenceNumber()) */
      LOG.storing_token(String.valueOf(tokenId.getSequenceNumber())).tag("methodCall").debug();
    }
    try {
      store.storeToken(tokenId, renewDate);
    } catch (IOException e) {
      /* LOG.error("Unable to store token "+tokenId.getSequenceNumber(),e) */
      LOG.unable_store_token(String.valueOf(tokenId.getSequenceNumber()), e.toString()).tag("methodCall").error();
    }
  }

  @Override
  protected void removeStoredToken(MRDelegationTokenIdentifier tokenId)
      throws IOException {
    if (LogGlobal.isDebugEnabled()) {
      /* LOG.debug("Storing token "+tokenId.getSequenceNumber()) */
      LOG.storing_token(String.valueOf(tokenId.getSequenceNumber())).tag("methodCall").debug();
    }
    try {
      store.removeToken(tokenId);
    } catch (IOException e) {
      /* LOG.error("Unable to remove token "+tokenId.getSequenceNumber(),e) */
      LOG.unable_remove_token(String.valueOf(tokenId.getSequenceNumber()), e.toString()).tag("methodCall").error();
    }
  }

  @Override
  protected void updateStoredToken(MRDelegationTokenIdentifier tokenId,
      long renewDate) {
    if (LogGlobal.isDebugEnabled()) {
      /* LOG.debug("Updating token "+tokenId.getSequenceNumber()) */
      LOG.updating_token(String.valueOf(tokenId.getSequenceNumber())).tag("methodCall").debug();
    }
    try {
      store.updateToken(tokenId, renewDate);
    } catch (IOException e) {
      /* LOG.error("Unable to update token "+tokenId.getSequenceNumber(),e) */
      LOG.unable_update_token(String.valueOf(tokenId.getSequenceNumber()), e.toString()).tag("methodCall").error();
    }
  }

  public void recover(HistoryServerState state) throws IOException {
    /* LOG.info("Recovering "+getClass().getSimpleName()) */
    LOG.recovering(getClass().getSimpleName()).tag("methodCall").info();
    for (DelegationKey key : state.tokenMasterKeyState) {
      addKey(key);
    }
    for (Entry<MRDelegationTokenIdentifier, Long> entry :
        state.tokenState.entrySet()) {
      addPersistedDelegationToken(entry.getKey(), entry.getValue());
    }
  }
}
