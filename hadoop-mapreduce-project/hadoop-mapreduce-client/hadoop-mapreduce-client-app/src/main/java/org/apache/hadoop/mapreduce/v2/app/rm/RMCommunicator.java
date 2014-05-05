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

package org.apache.hadoop.mapreduce.v2.app.rm;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import log_events.org.apache.hadoop.MapreduceNamespace;
import org.ngmon.logger.core.SimpleLogger;
import org.ngmon.logger.logtranslator.LogGlobal;
import org.ngmon.logger.core.LoggerFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.JobID;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.TypeConverter;
import org.apache.hadoop.mapreduce.v2.api.records.JobId;
import org.apache.hadoop.mapreduce.v2.app.AppContext;
import org.apache.hadoop.mapreduce.v2.app.MRAppMaster.RunningAppContext;
import org.apache.hadoop.mapreduce.v2.app.client.ClientService;
import org.apache.hadoop.mapreduce.v2.app.job.Job;
import org.apache.hadoop.mapreduce.v2.app.job.JobStateInternal;
import org.apache.hadoop.mapreduce.v2.app.job.impl.JobImpl;
import org.apache.hadoop.mapreduce.v2.util.MRWebAppUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.service.AbstractService;
import org.apache.hadoop.yarn.api.ApplicationMasterProtocol;
import org.apache.hadoop.yarn.api.protocolrecords.FinishApplicationMasterRequest;
import org.apache.hadoop.yarn.api.protocolrecords.FinishApplicationMasterResponse;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterRequest;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterResponse;
import org.apache.hadoop.yarn.api.records.ApplicationAccessType;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.client.ClientRMProxy;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.exceptions.YarnRuntimeException;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;

import com.google.common.annotations.VisibleForTesting;

/**
 * Registers/unregisters to RM and sends heartbeats to RM.
 */
public abstract class RMCommunicator extends AbstractService
    implements RMHeartbeatHandler {
  private static final /* LogLOG=LogFactory.getLog(RMContainerAllocator.class); */
			MapreduceNamespace LOG = LoggerFactory.getLogger(MapreduceNamespace.class, new SimpleLogger());
  private int rmPollInterval;//millis
  protected ApplicationId applicationId;
  private final AtomicBoolean stopped;
  protected Thread allocatorThread;
  @SuppressWarnings("rawtypes")
  protected EventHandler eventHandler;
  protected ApplicationMasterProtocol scheduler;
  private final ClientService clientService;
  protected int lastResponseID;
  private Resource maxContainerCapability;
  protected Map<ApplicationAccessType, String> applicationACLs;
  private volatile long lastHeartbeatTime;
  private ConcurrentLinkedQueue<Runnable> heartbeatCallbacks;

  private final RecordFactory recordFactory =
      RecordFactoryProvider.getRecordFactory(null);

  private final AppContext context;
  private Job job;
  // Has a signal (SIGTERM etc) been issued?
  protected volatile boolean isSignalled = false;
  private volatile boolean shouldUnregister = true;
  private boolean isApplicationMasterRegistered = false;

  public RMCommunicator(ClientService clientService, AppContext context) {
    super("RMCommunicator");
    this.clientService = clientService;
    this.context = context;
    this.eventHandler = context.getEventHandler();
    this.applicationId = context.getApplicationID();
    this.stopped = new AtomicBoolean(false);
    this.heartbeatCallbacks = new ConcurrentLinkedQueue<Runnable>();
  }

  @Override
  protected void serviceInit(Configuration conf) throws Exception {
    super.serviceInit(conf);
    rmPollInterval =
        conf.getInt(MRJobConfig.MR_AM_TO_RM_HEARTBEAT_INTERVAL_MS,
            MRJobConfig.DEFAULT_MR_AM_TO_RM_HEARTBEAT_INTERVAL_MS);
  }

  @Override
  protected void serviceStart() throws Exception {
    scheduler= createSchedulerProxy();
    JobID id = TypeConverter.fromYarn(this.applicationId);
    JobId jobId = TypeConverter.toYarn(id);
    job = context.getJob(jobId);
    register();
    startAllocatorThread();
    super.serviceStart();
  }

  protected AppContext getContext() {
    return context;
  }

  protected Job getJob() {
    return job;
  }

  /**
   * Get the appProgress. Can be used only after this component is started.
   * @return the appProgress.
   */
  protected float getApplicationProgress() {
    // For now just a single job. In future when we have a DAG, we need an
    // aggregate progress.
    return this.job.getProgress();
  }

  protected void register() {
    //Register
    InetSocketAddress serviceAddr = null;
    if (clientService != null ) {
      serviceAddr = clientService.getBindAddress();
    }
    try {
      RegisterApplicationMasterRequest request =
        recordFactory.newRecordInstance(RegisterApplicationMasterRequest.class);
      if (serviceAddr != null) {
        request.setHost(serviceAddr.getHostName());
        request.setRpcPort(serviceAddr.getPort());
        request.setTrackingUrl(MRWebAppUtil
            .getAMWebappScheme(getConfig())
            + serviceAddr.getHostName() + ":" + clientService.getHttpPort());
      }
      RegisterApplicationMasterResponse response =
        scheduler.registerApplicationMaster(request);
      isApplicationMasterRegistered = true;
      maxContainerCapability = response.getMaximumResourceCapability();
      this.context.getClusterInfo().setMaxContainerCapability(
          maxContainerCapability);
      if (UserGroupInformation.isSecurityEnabled()) {
        setClientToAMToken(response.getClientToAMTokenMasterKey());        
      }
      this.applicationACLs = response.getApplicationACLs();
      /* LOG.info("maxContainerCapability: "+maxContainerCapability.getMemory()) */
      LOG.maxcontainercapability(String.valueOf(maxContainerCapability.getMemory())).tag("methodCall").info();
      String queue = response.getQueue();
      /* LOG.info("queue: "+queue) */
      LOG.queue(queue).info();
      job.setQueueName(queue);
    } catch (Exception are) {
      /* LOG.error("Exception while registering",are) */
      LOG.exception_while_registering(are.toString()).error();
      throw new YarnRuntimeException(are);
    }
  }

  private void setClientToAMToken(ByteBuffer clientToAMTokenMasterKey) {
    byte[] key = clientToAMTokenMasterKey.array();
    context.getClientToAMTokenSecretManager().setMasterKey(key);
  }

  protected void unregister() {
    try {
      doUnregistration();
    } catch(Exception are) {
      /* LOG.error("Exception while unregistering ",are) */
      LOG.exception_while_unregistering(are.toString()).error();
      // if unregistration failed, isLastAMRetry needs to be recalculated
      // to see whether AM really has the chance to retry
      RunningAppContext raContext = (RunningAppContext) context;
      raContext.computeIsLastAMRetry();
    }
  }

  @VisibleForTesting
  protected void doUnregistration()
      throws YarnException, IOException, InterruptedException {
    FinalApplicationStatus finishState = FinalApplicationStatus.UNDEFINED;
    JobImpl jobImpl = (JobImpl)job;
    if (jobImpl.getInternalState() == JobStateInternal.SUCCEEDED) {
      finishState = FinalApplicationStatus.SUCCEEDED;
    } else if (jobImpl.getInternalState() == JobStateInternal.KILLED
        || (jobImpl.getInternalState() == JobStateInternal.RUNNING && isSignalled)) {
      finishState = FinalApplicationStatus.KILLED;
    } else if (jobImpl.getInternalState() == JobStateInternal.FAILED
        || jobImpl.getInternalState() == JobStateInternal.ERROR) {
      finishState = FinalApplicationStatus.FAILED;
    }
    StringBuffer sb = new StringBuffer();
    for (String s : job.getDiagnostics()) {
      sb.append(s).append("\n");
    }
    /* LOG.info("Setting job diagnostics to "+sb.toString()) */
    LOG.setting_job_diagnostics(String.valueOf(sb.toString())).tag("methodCall").info();

    String historyUrl =
        MRWebAppUtil.getApplicationWebURLOnJHSWithScheme(getConfig(),
            context.getApplicationID());
    /* LOG.info("History url is "+historyUrl) */
    LOG.history_url(historyUrl).info();
    FinishApplicationMasterRequest request =
        FinishApplicationMasterRequest.newInstance(finishState,
          sb.toString(), historyUrl);
    while (true) {
      FinishApplicationMasterResponse response =
          scheduler.finishApplicationMaster(request);
      if (response.getIsUnregistered()) {
        // When excepting ClientService, other services are already stopped,
        // it is safe to let clients know the final states. ClientService
        // should wait for some time so clients have enough time to know the
        // final states.
        RunningAppContext raContext = (RunningAppContext) context;
        raContext.markSuccessfulUnregistration();
        break;
      }
      /* LOG.info("Waiting for application to be successfully unregistered.") */
      LOG.waiting_for_application_successfully_unr().info();
      Thread.sleep(rmPollInterval);
    }
  }

  protected Resource getMaxContainerCapability() {
    return maxContainerCapability;
  }

  @Override
  protected void serviceStop() throws Exception {
    if (stopped.getAndSet(true)) {
      // return if already stopped
      return;
    }
    if (allocatorThread != null) {
      allocatorThread.interrupt();
      try {
        allocatorThread.join();
      } catch (InterruptedException ie) {
        /* LOG.warn("InterruptedException while stopping",ie) */
        LOG.interruptedexception_while_stopping(ie.toString()).warn();
      }
    }
    if (isApplicationMasterRegistered && shouldUnregister) {
      unregister();
    }
    super.serviceStop();
  }

  protected void startAllocatorThread() {
    allocatorThread = new Thread(new Runnable() {
      @Override
      public void run() {
        while (!stopped.get() && !Thread.currentThread().isInterrupted()) {
          try {
            Thread.sleep(rmPollInterval);
            try {
              heartbeat();
            } catch (YarnRuntimeException e) {
              /* LOG.error("Error communicating with RM: "+e.getMessage(),e) */
              LOG.error_communicating_with(String.valueOf(e.getMessage()), e.toString()).tag("methodCall").error();
              return;
            } catch (Exception e) {
              /* LOG.error("ERROR IN CONTACTING RM. ",e) */
              LOG.error_contacting(e.toString()).error();
              continue;
              // TODO: for other exceptions
            }

            lastHeartbeatTime = context.getClock().getTime();
            executeHeartbeatCallbacks();
          } catch (InterruptedException e) {
            if (!stopped.get()) {
              /* LOG.warn("Allocated thread interrupted. Returning.") */
              LOG.allocated_thread_interrupted_returning().warn();
            }
            return;
          }
        }
      }
    });
    allocatorThread.setName("RMCommunicator Allocator");
    allocatorThread.start();
  }

  protected ApplicationMasterProtocol createSchedulerProxy() {
    final Configuration conf = getConfig();

    try {
      return ClientRMProxy.createRMProxy(conf, ApplicationMasterProtocol.class);
    } catch (IOException e) {
      throw new YarnRuntimeException(e);
    }
  }

  protected abstract void heartbeat() throws Exception;

  private void executeHeartbeatCallbacks() {
    Runnable callback = null;
    while ((callback = heartbeatCallbacks.poll()) != null) {
      callback.run();
    }
  }

  @Override
  public long getLastHeartbeatTime() {
    return lastHeartbeatTime;
  }

  @Override
  public void runOnNextHeartbeat(Runnable callback) {
    heartbeatCallbacks.add(callback);
  }

  public void setShouldUnregister(boolean shouldUnregister) {
    this.shouldUnregister = shouldUnregister;
    /* LOG.info("RMCommunicator notified that shouldUnregistered is: "+shouldUnregister) */
    LOG.rmcommunicator_notified_that_shouldunreg(shouldUnregister).info();
  }
  
  public void setSignalled(boolean isSignalled) {
    this.isSignalled = isSignalled;
    /* LOG.info("RMCommunicator notified that iSignalled is: "+isSignalled) */
    LOG.rmcommunicator_notified_that_isignalled(isSignalled).info();
  }

  @VisibleForTesting
  protected boolean isApplicationMasterRegistered() {
    return isApplicationMasterRegistered;
  }
}
