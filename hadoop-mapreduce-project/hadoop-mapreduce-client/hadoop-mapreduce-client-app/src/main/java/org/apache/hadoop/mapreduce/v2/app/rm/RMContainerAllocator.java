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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import log_events.org.apache.hadoop.MapreduceNamespace;
import org.ngmon.logger.core.SimpleLogger;
import org.ngmon.logger.logtranslator.LogGlobal;
import org.ngmon.logger.core.LoggerFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.JobCounter;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.jobhistory.JobHistoryEvent;
import org.apache.hadoop.mapreduce.jobhistory.NormalizedResourceEvent;
import org.apache.hadoop.mapreduce.v2.api.records.JobId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskType;
import org.apache.hadoop.mapreduce.v2.app.AppContext;
import org.apache.hadoop.mapreduce.v2.app.client.ClientService;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobCounterUpdateEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobDiagnosticsUpdateEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobEventType;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobUpdatedNodesEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptContainerAssignedEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptDiagnosticsUpdateEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptEventType;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptKillEvent;
import org.apache.hadoop.mapreduce.v2.app.rm.preemption.AMPreemptionPolicy;
import org.apache.hadoop.util.StringInterner;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerExitStatus;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.NMToken;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.NodeReport;
import org.apache.hadoop.yarn.api.records.NodeState;
import org.apache.hadoop.yarn.api.records.PreemptionMessage;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.client.api.NMTokenCache;
import org.apache.hadoop.yarn.exceptions.YarnRuntimeException;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.util.RackResolver;

import com.google.common.annotations.VisibleForTesting;

/**
 * Allocates the container from the ResourceManager scheduler.
 */
public class RMContainerAllocator extends RMContainerRequestor
    implements ContainerAllocator {

  static final /* LogLOG=LogFactory.getLog(RMContainerAllocator.class); */
			MapreduceNamespace LOG = LoggerFactory.getLogger(MapreduceNamespace.class, new SimpleLogger());
  
  public static final 
  float DEFAULT_COMPLETED_MAPS_PERCENT_FOR_REDUCE_SLOWSTART = 0.05f;
  
  private static final Priority PRIORITY_FAST_FAIL_MAP;
  private static final Priority PRIORITY_REDUCE;
  private static final Priority PRIORITY_MAP;

  @VisibleForTesting
  public static final String RAMPDOWN_DIAGNOSTIC = "Reducer preempted "
      + "to make room for pending map attempts";

  private Thread eventHandlingThread;
  private final AtomicBoolean stopped;

  static {
    PRIORITY_FAST_FAIL_MAP = RecordFactoryProvider.getRecordFactory(null).newRecordInstance(Priority.class);
    PRIORITY_FAST_FAIL_MAP.setPriority(5);
    PRIORITY_REDUCE = RecordFactoryProvider.getRecordFactory(null).newRecordInstance(Priority.class);
    PRIORITY_REDUCE.setPriority(10);
    PRIORITY_MAP = RecordFactoryProvider.getRecordFactory(null).newRecordInstance(Priority.class);
    PRIORITY_MAP.setPriority(20);
  }
  
  /*
  Vocabulary Used: 
  pending -> requests which are NOT yet sent to RM
  scheduled -> requests which are sent to RM but not yet assigned
  assigned -> requests which are assigned to a container
  completed -> request corresponding to which container has completed
  
  Lifecycle of map
  scheduled->assigned->completed
  
  Lifecycle of reduce
  pending->scheduled->assigned->completed
  
  Maps are scheduled as soon as their requests are received. Reduces are 
  added to the pending and are ramped up (added to scheduled) based 
  on completed maps and current availability in the cluster.
  */
  
  //reduces which are not yet scheduled
  private final LinkedList<ContainerRequest> pendingReduces = 
    new LinkedList<ContainerRequest>();

  //holds information about the assigned containers to task attempts
  private final AssignedRequests assignedRequests = new AssignedRequests();
  
  //holds scheduled requests to be fulfilled by RM
  private final ScheduledRequests scheduledRequests = new ScheduledRequests();
  
  private int containersAllocated = 0;
  private int containersReleased = 0;
  private int hostLocalAssigned = 0;
  private int rackLocalAssigned = 0;
  private int lastCompletedTasks = 0;
  
  private boolean recalculateReduceSchedule = false;
  private int mapResourceReqt;//memory
  private int reduceResourceReqt;//memory
  
  private boolean reduceStarted = false;
  private float maxReduceRampupLimit = 0;
  private float maxReducePreemptionLimit = 0;
  private float reduceSlowStart = 0;
  private long retryInterval;
  private long retrystartTime;

  private final AMPreemptionPolicy preemptionPolicy;

  @VisibleForTesting
  protected BlockingQueue<ContainerAllocatorEvent> eventQueue
    = new LinkedBlockingQueue<ContainerAllocatorEvent>();

  private ScheduleStats scheduleStats = new ScheduleStats();

  public RMContainerAllocator(ClientService clientService, AppContext context,
      AMPreemptionPolicy preemptionPolicy) {
    super(clientService, context);
    this.preemptionPolicy = preemptionPolicy;
    this.stopped = new AtomicBoolean(false);
  }

  @Override
  protected void serviceInit(Configuration conf) throws Exception {
    super.serviceInit(conf);
    reduceSlowStart = conf.getFloat(
        MRJobConfig.COMPLETED_MAPS_FOR_REDUCE_SLOWSTART, 
        DEFAULT_COMPLETED_MAPS_PERCENT_FOR_REDUCE_SLOWSTART);
    maxReduceRampupLimit = conf.getFloat(
        MRJobConfig.MR_AM_JOB_REDUCE_RAMPUP_UP_LIMIT, 
        MRJobConfig.DEFAULT_MR_AM_JOB_REDUCE_RAMP_UP_LIMIT);
    maxReducePreemptionLimit = conf.getFloat(
        MRJobConfig.MR_AM_JOB_REDUCE_PREEMPTION_LIMIT,
        MRJobConfig.DEFAULT_MR_AM_JOB_REDUCE_PREEMPTION_LIMIT);
    RackResolver.init(conf);
    retryInterval = getConfig().getLong(MRJobConfig.MR_AM_TO_RM_WAIT_INTERVAL_MS,
                                MRJobConfig.DEFAULT_MR_AM_TO_RM_WAIT_INTERVAL_MS);
    // Init startTime to current time. If all goes well, it will be reset after
    // first attempt to contact RM.
    retrystartTime = System.currentTimeMillis();
  }

  @Override
  protected void serviceStart() throws Exception {
    this.eventHandlingThread = new Thread() {
      @SuppressWarnings("unchecked")
      @Override
      public void run() {

        ContainerAllocatorEvent event;

        while (!stopped.get() && !Thread.currentThread().isInterrupted()) {
          try {
            event = RMContainerAllocator.this.eventQueue.take();
          } catch (InterruptedException e) {
            if (!stopped.get()) {
              /* LOG.error("Returning, interrupted : "+e) */
              LOG.returning_interrupted(e.toString()).error();
            }
            return;
          }

          try {
            handleEvent(event);
          } catch (Throwable t) {
            /* LOG.error("Error in handling event type "+event.getType()+" to the ContainreAllocator",t) */
            LOG.error_handling_event_type_containrealloc(String.valueOf(event.getType()), t.toString()).tag("methodCall").error();
            // Kill the AM
            eventHandler.handle(new JobEvent(getJob().getID(),
              JobEventType.INTERNAL_ERROR));
            return;
          }
        }
      }
    };
    this.eventHandlingThread.start();
    super.serviceStart();
  }

  @Override
  protected synchronized void heartbeat() throws Exception {
    scheduleStats.updateAndLogIfChanged("Before Scheduling: ");
    List<Container> allocatedContainers = getResources();
    if (allocatedContainers.size() > 0) {
      scheduledRequests.assign(allocatedContainers);
    }

    int completedMaps = getJob().getCompletedMaps();
    int completedTasks = completedMaps + getJob().getCompletedReduces();
    if ((lastCompletedTasks != completedTasks) ||
          (scheduledRequests.maps.size() > 0)) {
      lastCompletedTasks = completedTasks;
      recalculateReduceSchedule = true;
    }

    if (recalculateReduceSchedule) {
      preemptReducesIfNeeded();
      scheduleReduces(
          getJob().getTotalMaps(), completedMaps,
          scheduledRequests.maps.size(), scheduledRequests.reduces.size(), 
          assignedRequests.maps.size(), assignedRequests.reduces.size(),
          mapResourceReqt, reduceResourceReqt,
          pendingReduces.size(), 
          maxReduceRampupLimit, reduceSlowStart);
      recalculateReduceSchedule = false;
    }

    scheduleStats.updateAndLogIfChanged("After Scheduling: ");
  }

  @Override
  protected void serviceStop() throws Exception {
    if (stopped.getAndSet(true)) {
      // return if already stopped
      return;
    }
    if (eventHandlingThread != null) {
      eventHandlingThread.interrupt();
    }
    super.serviceStop();
    scheduleStats.log("Final Stats: ");
  }

  public boolean getIsReduceStarted() {
    return reduceStarted;
  }
  
  public void setIsReduceStarted(boolean reduceStarted) {
    this.reduceStarted = reduceStarted; 
  }

  @Override
  public void handle(ContainerAllocatorEvent event) {
    int qSize = eventQueue.size();
    if (qSize != 0 && qSize % 1000 == 0) {
      /* LOG.info("Size of event-queue in RMContainerAllocator is "+qSize) */
      LOG.size_event_queue_rmcontainerallocator(qSize).info();
    }
    int remCapacity = eventQueue.remainingCapacity();
    if (remCapacity < 1000) {
      /* LOG.warn("Very low remaining capacity in the event-queue "+"of RMContainerAllocator: "+remCapacity) */
      LOG.very_low_remaining_capacity_event_queue_(remCapacity).warn();
    }
    try {
      eventQueue.put(event);
    } catch (InterruptedException e) {
      throw new YarnRuntimeException(e);
    }
  }

  @SuppressWarnings({ "unchecked" })
  protected synchronized void handleEvent(ContainerAllocatorEvent event) {
    recalculateReduceSchedule = true;
    if (event.getType() == ContainerAllocator.EventType.CONTAINER_REQ) {
      ContainerRequestEvent reqEvent = (ContainerRequestEvent) event;
      JobId jobId = getJob().getID();
      int supportedMaxContainerCapability =
          getMaxContainerCapability().getMemory();
      if (reqEvent.getAttemptID().getTaskId().getTaskType().equals(TaskType.MAP)) {
        if (mapResourceReqt == 0) {
          mapResourceReqt = reqEvent.getCapability().getMemory();
          eventHandler.handle(new JobHistoryEvent(jobId, 
              new NormalizedResourceEvent(org.apache.hadoop.mapreduce.TaskType.MAP,
              mapResourceReqt)));
          /* LOG.info("mapResourceReqt:"+mapResourceReqt) */
          LOG.mapresourcereqt(mapResourceReqt).info();
          if (mapResourceReqt > supportedMaxContainerCapability) {
            String diagMsg = "MAP capability required is more than the supported " +
            "max container capability in the cluster. Killing the Job. mapResourceReqt: " + 
            mapResourceReqt + " maxContainerCapability:" + supportedMaxContainerCapability;
            /* LOG.info(diagMsg) */
            LOG.diagmsg(diagMsg).info();
            eventHandler.handle(new JobDiagnosticsUpdateEvent(
                jobId, diagMsg));
            eventHandler.handle(new JobEvent(jobId, JobEventType.JOB_KILL));
          }
        }
        //set the rounded off memory
        reqEvent.getCapability().setMemory(mapResourceReqt);
        scheduledRequests.addMap(reqEvent);//maps are immediately scheduled
      } else {
        if (reduceResourceReqt == 0) {
          reduceResourceReqt = reqEvent.getCapability().getMemory();
          eventHandler.handle(new JobHistoryEvent(jobId, 
              new NormalizedResourceEvent(
                  org.apache.hadoop.mapreduce.TaskType.REDUCE,
              reduceResourceReqt)));
          /* LOG.info("reduceResourceReqt:"+reduceResourceReqt) */
          LOG.reduceresourcereqt(reduceResourceReqt).info();
          if (reduceResourceReqt > supportedMaxContainerCapability) {
            String diagMsg = "REDUCE capability required is more than the " +
            		"supported max container capability in the cluster. Killing the " +
            		"Job. reduceResourceReqt: " + reduceResourceReqt +
            		" maxContainerCapability:" + supportedMaxContainerCapability;
            /* LOG.info(diagMsg) */
            LOG.diagmsg(diagMsg).info();
            eventHandler.handle(new JobDiagnosticsUpdateEvent(
                jobId, diagMsg));
            eventHandler.handle(new JobEvent(jobId, JobEventType.JOB_KILL));
          }
        }
        //set the rounded off memory
        reqEvent.getCapability().setMemory(reduceResourceReqt);
        if (reqEvent.getEarlierAttemptFailed()) {
          //add to the front of queue for fail fast
          pendingReduces.addFirst(new ContainerRequest(reqEvent, PRIORITY_REDUCE));
        } else {
          pendingReduces.add(new ContainerRequest(reqEvent, PRIORITY_REDUCE));
          //reduces are added to pending and are slowly ramped up
        }
      }
      
    } else if (
      event.getType() == ContainerAllocator.EventType.CONTAINER_DEALLOCATE) {
  
      /* LOG.info("Processing the event "+event.toString()) */
      LOG.processing_event(String.valueOf(event.toString())).tag("methodCall").info();

      TaskAttemptId aId = event.getAttemptID();
      
      boolean removed = scheduledRequests.remove(aId);
      if (!removed) {
        ContainerId containerId = assignedRequests.get(aId);
        if (containerId != null) {
          removed = true;
          assignedRequests.remove(aId);
          containersReleased++;
          release(containerId);
        }
      }
      if (!removed) {
        /* LOG.error("Could not deallocate container for task attemptId "+aId) */
        LOG.could_not_deallocate_container_for_task(aId.toString()).error();
      }
      preemptionPolicy.handleCompletedContainer(event.getAttemptID());
    } else if (
        event.getType() == ContainerAllocator.EventType.CONTAINER_FAILED) {
      ContainerFailedEvent fEv = (ContainerFailedEvent) event;
      String host = getHost(fEv.getContMgrAddress());
      containerFailedOnHost(host);
      // propagate failures to preemption policy to discard checkpoints for
      // failed tasks
      preemptionPolicy.handleFailedContainer(event.getAttemptID());
    }
  }

  private static String getHost(String contMgrAddress) {
    String host = contMgrAddress;
    String[] hostport = host.split(":");
    if (hostport.length == 2) {
      host = hostport[0];
    }
    return host;
  }

  private void preemptReducesIfNeeded() {
    if (reduceResourceReqt == 0) {
      return; //no reduces
    }
    //check if reduces have taken over the whole cluster and there are 
    //unassigned maps
    if (scheduledRequests.maps.size() > 0) {
      int memLimit = getMemLimit();
      int availableMemForMap = memLimit - ((assignedRequests.reduces.size() -
          assignedRequests.preemptionWaitingReduces.size()) * reduceResourceReqt);
      //availableMemForMap must be sufficient to run atleast 1 map
      if (availableMemForMap < mapResourceReqt) {
        //to make sure new containers are given to maps and not reduces
        //ramp down all scheduled reduces if any
        //(since reduces are scheduled at higher priority than maps)
        /* LOG.info("Ramping down all scheduled reduces:"+scheduledRequests.reduces.size()) */
        LOG.ramping_down_all_scheduled_reduces(String.valueOf(scheduledRequests.reduces.size())).tag("methodCall").info();
        for (ContainerRequest req : scheduledRequests.reduces.values()) {
          pendingReduces.add(req);
        }
        scheduledRequests.reduces.clear();
        
        //preempt for making space for at least one map
        int premeptionLimit = Math.max(mapResourceReqt, 
            (int) (maxReducePreemptionLimit * memLimit));
        
        int preemptMem = Math.min(scheduledRequests.maps.size() * mapResourceReqt, 
            premeptionLimit);
        
        int toPreempt = (int) Math.ceil((float) preemptMem/reduceResourceReqt);
        toPreempt = Math.min(toPreempt, assignedRequests.reduces.size());
        
        /* LOG.info("Going to preempt "+toPreempt+" due to lack of space for maps") */
        LOG.going_preempt_due_lack_space_for(toPreempt).info();
        assignedRequests.preemptReduce(toPreempt);
      }
    }
  }
  
  @Private
  public void scheduleReduces(
      int totalMaps, int completedMaps,
      int scheduledMaps, int scheduledReduces,
      int assignedMaps, int assignedReduces,
      int mapResourceReqt, int reduceResourceReqt,
      int numPendingReduces,
      float maxReduceRampupLimit, float reduceSlowStart) {
    
    if (numPendingReduces == 0) {
      return;
    }
    
    int headRoom = getAvailableResources() != null ?
        getAvailableResources().getMemory() : 0;
    /* LOG.info("Recalculating schedule, headroom="+headRoom) */
    LOG.recalculating_schedule_headroom(headRoom).info();
    
    //check for slow start
    if (!getIsReduceStarted()) {//not set yet
      int completedMapsForReduceSlowstart = (int)Math.ceil(reduceSlowStart * 
                      totalMaps);
      if(completedMaps < completedMapsForReduceSlowstart) {
        /* LOG.info("Reduce slow start threshold not met. "+"completedMapsForReduceSlowstart "+completedMapsForReduceSlowstart) */
        LOG.reduce_slow_start_threshold_not_met_comp(completedMapsForReduceSlowstart).info();
        return;
      } else {
        /* LOG.info("Reduce slow start threshold reached. Scheduling reduces.") */
        LOG.reduce_slow_start_threshold_reached_sche().info();
        setIsReduceStarted(true);
      }
    }
    
    //if all maps are assigned, then ramp up all reduces irrespective of the
    //headroom
    if (scheduledMaps == 0 && numPendingReduces > 0) {
      /* LOG.info("All maps assigned. "+"Ramping up all remaining reduces:"+numPendingReduces) */
      LOG.all_maps_assigned_ramping_all_remaining(numPendingReduces).info();
      scheduleAllReduces();
      return;
    }

    float completedMapPercent = 0f;
    if (totalMaps != 0) {//support for 0 maps
      completedMapPercent = (float)completedMaps/totalMaps;
    } else {
      completedMapPercent = 1;
    }
    
    int netScheduledMapMem = 
        (scheduledMaps + assignedMaps) * mapResourceReqt;

    int netScheduledReduceMem = 
        (scheduledReduces + assignedReduces) * reduceResourceReqt;

    int finalMapMemLimit = 0;
    int finalReduceMemLimit = 0;
    
    // ramp up the reduces based on completed map percentage
    int totalMemLimit = getMemLimit();
    int idealReduceMemLimit = 
        Math.min(
            (int)(completedMapPercent * totalMemLimit),
            (int) (maxReduceRampupLimit * totalMemLimit));
    int idealMapMemLimit = totalMemLimit - idealReduceMemLimit;

    // check if there aren't enough maps scheduled, give the free map capacity
    // to reduce
    if (idealMapMemLimit > netScheduledMapMem) {
      int unusedMapMemLimit = idealMapMemLimit - netScheduledMapMem;
      finalReduceMemLimit = idealReduceMemLimit + unusedMapMemLimit;
      finalMapMemLimit = totalMemLimit - finalReduceMemLimit;
    } else {
      finalMapMemLimit = idealMapMemLimit;
      finalReduceMemLimit = idealReduceMemLimit;
    }
    
    /* LOG.info("completedMapPercent "+completedMapPercent+" totalMemLimit:"+totalMemLimit+" finalMapMemLimit:"+finalMapMemLimit+" finalReduceMemLimit:"+finalReduceMemLimit+" netScheduledMapMem:"+netScheduledMapMem+" netScheduledReduceMem:"+netScheduledReduceMem) */
    LOG.completedmappercent_totalmemlimit_finalm(completedMapPercent, totalMemLimit, finalMapMemLimit, finalReduceMemLimit, netScheduledMapMem, netScheduledReduceMem).info();
    
    int rampUp = 
        (finalReduceMemLimit - netScheduledReduceMem) / reduceResourceReqt;
    
    if (rampUp > 0) {
      rampUp = Math.min(rampUp, numPendingReduces);
      /* LOG.info("Ramping up "+rampUp) */
      LOG.ramping(rampUp).info();
      rampUpReduces(rampUp);
    } else if (rampUp < 0){
      int rampDown = -1 * rampUp;
      rampDown = Math.min(rampDown, scheduledReduces);
      /* LOG.info("Ramping down "+rampDown) */
      LOG.ramping_down(rampDown).info();
      rampDownReduces(rampDown);
    }
  }

  @Private
  public void scheduleAllReduces() {
    for (ContainerRequest req : pendingReduces) {
      scheduledRequests.addReduce(req);
    }
    pendingReduces.clear();
  }
  
  @Private
  public void rampUpReduces(int rampUp) {
    //more reduce to be scheduled
    for (int i = 0; i < rampUp; i++) {
      ContainerRequest request = pendingReduces.removeFirst();
      scheduledRequests.addReduce(request);
    }
  }
  
  @Private
  public void rampDownReduces(int rampDown) {
    //remove from the scheduled and move back to pending
    for (int i = 0; i < rampDown; i++) {
      ContainerRequest request = scheduledRequests.removeReduce();
      pendingReduces.add(request);
    }
  }
  
  @SuppressWarnings("unchecked")
  private List<Container> getResources() throws Exception {
    int headRoom = getAvailableResources() != null
        ? getAvailableResources().getMemory() : 0;//first time it would be null
    AllocateResponse response;
    /*
     * If contact with RM is lost, the AM will wait MR_AM_TO_RM_WAIT_INTERVAL_MS
     * milliseconds before aborting. During this interval, AM will still try
     * to contact the RM.
     */
    try {
      response = makeRemoteRequest();
      // Reset retry count if no exception occurred.
      retrystartTime = System.currentTimeMillis();
    } catch (Exception e) {
      // This can happen when the connection to the RM has gone down. Keep
      // re-trying until the retryInterval has expired.
      if (System.currentTimeMillis() - retrystartTime >= retryInterval) {
        /* LOG.error("Could not contact RM after "+retryInterval+" milliseconds.") */
        LOG.could_not_contact_after_milliseconds(retryInterval).error();
        eventHandler.handle(new JobEvent(this.getJob().getID(),
                                         JobEventType.INTERNAL_ERROR));
        throw new YarnRuntimeException("Could not contact RM after " +
                                retryInterval + " milliseconds.");
      }
      // Throw this up to the caller, which may decide to ignore it and
      // continue to attempt to contact the RM.
      throw e;
    }
    if (response.getAMCommand() != null) {
      switch(response.getAMCommand()) {
      case AM_RESYNC:
      case AM_SHUTDOWN:
        // This can happen if the RM has been restarted. If it is in that state,
        // this application must clean itself up.
        eventHandler.handle(new JobEvent(this.getJob().getID(),
                                         JobEventType.JOB_AM_REBOOT));
        throw new YarnRuntimeException("Resource Manager doesn't recognize AttemptId: " +
                                 this.getContext().getApplicationID());
      default:
        String msg =
              "Unhandled value of AMCommand: " + response.getAMCommand();
        /* LOG.error(msg) */
        LOG.msg(msg).error();
        throw new YarnRuntimeException(msg);
      }
    }
    int newHeadRoom = getAvailableResources() != null ? getAvailableResources().getMemory() : 0;
    List<Container> newContainers = response.getAllocatedContainers();
    // Setting NMTokens
    if (response.getNMTokens() != null) {
      for (NMToken nmToken : response.getNMTokens()) {
        NMTokenCache.setNMToken(nmToken.getNodeId().toString(),
            nmToken.getToken());
      }
    }
    
    List<ContainerStatus> finishedContainers = response.getCompletedContainersStatuses();

    // propagate preemption requests
    final PreemptionMessage preemptReq = response.getPreemptionMessage();
    if (preemptReq != null) {
      preemptionPolicy.preempt(
          new PreemptionContext(assignedRequests), preemptReq);
    }

    if (newContainers.size() + finishedContainers.size() > 0 || headRoom != newHeadRoom) {
      //something changed
      recalculateReduceSchedule = true;
      if (LogGlobal.isDebugEnabled() && headRoom != newHeadRoom) {
        /* LOG.debug("headroom="+newHeadRoom) */
        LOG.headroom(newHeadRoom).debug();
      }
    }

    if (LogGlobal.isDebugEnabled()) {
      for (Container cont : newContainers) {
        /* LOG.debug("Received new Container :"+cont) */
        LOG.received_new_container(cont.toString()).debug();
      }
    }

    //Called on each allocation. Will know about newly blacklisted/added hosts.
    computeIgnoreBlacklisting();

    handleUpdatedNodes(response);

    for (ContainerStatus cont : finishedContainers) {
      /* LOG.info("Received completed container "+cont.getContainerId()) */
      LOG.received_completed_container(String.valueOf(cont.getContainerId())).tag("methodCall").info();
      TaskAttemptId attemptID = assignedRequests.get(cont.getContainerId());
      if (attemptID == null) {
        /* LOG.error("Container complete event for unknown container id "+cont.getContainerId()) */
        LOG.container_complete_event_for_unknown_con(String.valueOf(cont.getContainerId())).tag("methodCall").error();
      } else {
        assignedRequests.remove(attemptID);
        
        // send the container completed event to Task attempt
        eventHandler.handle(createContainerFinishedEvent(cont, attemptID));
        
        // Send the diagnostics
        String diagnostics = StringInterner.weakIntern(cont.getDiagnostics());
        eventHandler.handle(new TaskAttemptDiagnosticsUpdateEvent(attemptID,
            diagnostics));

        preemptionPolicy.handleCompletedContainer(attemptID);
      }
    }
    return newContainers;
  }
  
  @VisibleForTesting
  public TaskAttemptEvent createContainerFinishedEvent(ContainerStatus cont,
      TaskAttemptId attemptID) {
    if (cont.getExitStatus() == ContainerExitStatus.ABORTED) {
      // killed by framework
      return new TaskAttemptEvent(attemptID,
          TaskAttemptEventType.TA_KILL);
    } else {
      return new TaskAttemptEvent(attemptID,
          TaskAttemptEventType.TA_CONTAINER_COMPLETED);
    }
  }
  
  @SuppressWarnings("unchecked")
  private void handleUpdatedNodes(AllocateResponse response) {
    // send event to the job about on updated nodes
    List<NodeReport> updatedNodes = response.getUpdatedNodes();
    if (!updatedNodes.isEmpty()) {

      // send event to the job to act upon completed tasks
      eventHandler.handle(new JobUpdatedNodesEvent(getJob().getID(),
          updatedNodes));

      // act upon running tasks
      HashSet<NodeId> unusableNodes = new HashSet<NodeId>();
      for (NodeReport nr : updatedNodes) {
        NodeState nodeState = nr.getNodeState();
        if (nodeState.isUnusable()) {
          unusableNodes.add(nr.getNodeId());
        }
      }
      for (int i = 0; i < 2; ++i) {
        HashMap<TaskAttemptId, Container> taskSet = i == 0 ? assignedRequests.maps
            : assignedRequests.reduces;
        // kill running containers
        for (Map.Entry<TaskAttemptId, Container> entry : taskSet.entrySet()) {
          TaskAttemptId tid = entry.getKey();
          NodeId taskAttemptNodeId = entry.getValue().getNodeId();
          if (unusableNodes.contains(taskAttemptNodeId)) {
            /* LOG.info("Killing taskAttempt:"+tid+" because it is running on unusable node:"+taskAttemptNodeId) */
            LOG.killing_taskattempt_because_running_unus(tid.toString(), taskAttemptNodeId.toString()).info();
            eventHandler.handle(new TaskAttemptKillEvent(tid,
                "TaskAttempt killed because it ran on unusable node"
                    + taskAttemptNodeId));
          }
        }
      }
    }
  }

  @Private
  public int getMemLimit() {
    int headRoom = getAvailableResources() != null ? getAvailableResources().getMemory() : 0;
    return headRoom + assignedRequests.maps.size() * mapResourceReqt + 
       assignedRequests.reduces.size() * reduceResourceReqt;
  }
  
  private class ScheduledRequests {
    
    private final LinkedList<TaskAttemptId> earlierFailedMaps = 
      new LinkedList<TaskAttemptId>();
    
    /** Maps from a host to a list of Map tasks with data on the host */
    private final Map<String, LinkedList<TaskAttemptId>> mapsHostMapping = 
      new HashMap<String, LinkedList<TaskAttemptId>>();
    private final Map<String, LinkedList<TaskAttemptId>> mapsRackMapping = 
      new HashMap<String, LinkedList<TaskAttemptId>>();
    private final Map<TaskAttemptId, ContainerRequest> maps = 
      new LinkedHashMap<TaskAttemptId, ContainerRequest>();
    
    private final LinkedHashMap<TaskAttemptId, ContainerRequest> reduces = 
      new LinkedHashMap<TaskAttemptId, ContainerRequest>();
    
    boolean remove(TaskAttemptId tId) {
      ContainerRequest req = null;
      if (tId.getTaskId().getTaskType().equals(TaskType.MAP)) {
        req = maps.remove(tId);
      } else {
        req = reduces.remove(tId);
      }
      
      if (req == null) {
        return false;
      } else {
        decContainerReq(req);
        return true;
      }
    }
    
    ContainerRequest removeReduce() {
      Iterator<Entry<TaskAttemptId, ContainerRequest>> it = reduces.entrySet().iterator();
      if (it.hasNext()) {
        Entry<TaskAttemptId, ContainerRequest> entry = it.next();
        it.remove();
        decContainerReq(entry.getValue());
        return entry.getValue();
      }
      return null;
    }
    
    void addMap(ContainerRequestEvent event) {
      ContainerRequest request = null;
      
      if (event.getEarlierAttemptFailed()) {
        earlierFailedMaps.add(event.getAttemptID());
        request = new ContainerRequest(event, PRIORITY_FAST_FAIL_MAP);
        /* LOG.info("Added "+event.getAttemptID()+" to list of failed maps") */
        LOG.added_list_failed_maps(String.valueOf(event.getAttemptID())).tag("methodCall").info();
      } else {
        for (String host : event.getHosts()) {
          LinkedList<TaskAttemptId> list = mapsHostMapping.get(host);
          if (list == null) {
            list = new LinkedList<TaskAttemptId>();
            mapsHostMapping.put(host, list);
          }
          list.add(event.getAttemptID());
          if (LogGlobal.isDebugEnabled()) {
            /* LOG.debug("Added attempt req to host "+host) */
            LOG.added_attempt_req_host(host).debug();
          }
       }
       for (String rack: event.getRacks()) {
         LinkedList<TaskAttemptId> list = mapsRackMapping.get(rack);
         if (list == null) {
           list = new LinkedList<TaskAttemptId>();
           mapsRackMapping.put(rack, list);
         }
         list.add(event.getAttemptID());
         if (LogGlobal.isDebugEnabled()) {
            /* LOG.debug("Added attempt req to rack "+rack) */
            LOG.added_attempt_req_rack(rack).debug();
         }
       }
       request = new ContainerRequest(event, PRIORITY_MAP);
      }
      maps.put(event.getAttemptID(), request);
      addContainerReq(request);
    }
    
    
    void addReduce(ContainerRequest req) {
      reduces.put(req.attemptID, req);
      addContainerReq(req);
    }
    
    // this method will change the list of allocatedContainers.
    private void assign(List<Container> allocatedContainers) {
      Iterator<Container> it = allocatedContainers.iterator();
      /* LOG.info("Got allocated containers "+allocatedContainers.size()) */
      LOG.got_allocated_containers(String.valueOf(allocatedContainers.size())).tag("methodCall").info();
      containersAllocated += allocatedContainers.size();
      while (it.hasNext()) {
        Container allocated = it.next();
        if (LogGlobal.isDebugEnabled()) {
          /* LOG.debug("Assigning container "+allocated.getId()+" with priority "+allocated.getPriority()+" to NM "+allocated.getNodeId()) */
          LOG.assigning_container_with_priority(String.valueOf(allocated.getId()), String.valueOf(allocated.getPriority()), String.valueOf(allocated.getNodeId())).tag("methodCall").debug();
        }
        
        // check if allocated container meets memory requirements 
        // and whether we have any scheduled tasks that need 
        // a container to be assigned
        boolean isAssignable = true;
        Priority priority = allocated.getPriority();
        int allocatedMemory = allocated.getResource().getMemory();
        if (PRIORITY_FAST_FAIL_MAP.equals(priority) 
            || PRIORITY_MAP.equals(priority)) {
          if (allocatedMemory < mapResourceReqt
              || maps.isEmpty()) {
            /* LOG.info("Cannot assign container "+allocated+" for a map as either "+" container memory less than required "+mapResourceReqt+" or no pending map tasks - maps.isEmpty="+maps.isEmpty()) */
            LOG.cannot_assign_container_for_map_either_c(allocated.toString(), mapResourceReqt, String.valueOf(maps.isEmpty())).tag("methodCall").info(); 
            isAssignable = false; 
          }
        } 
        else if (PRIORITY_REDUCE.equals(priority)) {
          if (allocatedMemory < reduceResourceReqt
              || reduces.isEmpty()) {
            /* LOG.info("Cannot assign container "+allocated+" for a reduce as either "+" container memory less than required "+reduceResourceReqt+" or no pending reduce tasks - reduces.isEmpty="+reduces.isEmpty()) */
            LOG.cannot_assign_container_for_reduce_eithe(allocated.toString(), reduceResourceReqt, String.valueOf(reduces.isEmpty())).tag("methodCall").info(); 
            isAssignable = false;
          }
        } else {
          /* LOG.warn("Container allocated at unwanted priority: "+priority+". Returning to RM...") */
          LOG.container_allocated_unwanted_priority_re(priority.toString()).warn();
          isAssignable = false;
        }
        
        if(!isAssignable) {
          // release container if we could not assign it 
          containerNotAssigned(allocated);
          it.remove();
          continue;
        }
        
        // do not assign if allocated container is on a  
        // blacklisted host
        String allocatedHost = allocated.getNodeId().getHost();
        if (isNodeBlacklisted(allocatedHost)) {
          // we need to request for a new container 
          // and release the current one
          /* LOG.info("Got allocated container on a blacklisted "+" host "+allocatedHost+". Releasing container "+allocated) */
          LOG.got_allocated_container_blacklisted_host(allocatedHost, allocated.toString()).info();

          // find the request matching this allocated container 
          // and replace it with a new one 
          ContainerRequest toBeReplacedReq = 
              getContainerReqToReplace(allocated);
          if (toBeReplacedReq != null) {
            /* LOG.info("Placing a new container request for task attempt "+toBeReplacedReq.attemptID) */
            LOG.placing_new_container_request_for_task(String.valueOf(toBeReplacedReq.attemptID)).tag("methodCall").info();
            ContainerRequest newReq = 
                getFilteredContainerRequest(toBeReplacedReq);
            decContainerReq(toBeReplacedReq);
            if (toBeReplacedReq.attemptID.getTaskId().getTaskType() ==
                TaskType.MAP) {
              maps.put(newReq.attemptID, newReq);
            }
            else {
              reduces.put(newReq.attemptID, newReq);
            }
            addContainerReq(newReq);
          }
          else {
            /* LOG.info("Could not map allocated container to a valid request."+" Releasing allocated container "+allocated) */
            LOG.could_not_map_allocated_container_valid_(allocated.toString()).info();
          }
          
          // release container if we could not assign it 
          containerNotAssigned(allocated);
          it.remove();
          continue;
        }
      }

      assignContainers(allocatedContainers);
       
      // release container if we could not assign it 
      it = allocatedContainers.iterator();
      while (it.hasNext()) {
        Container allocated = it.next();
        /* LOG.info("Releasing unassigned and invalid container "+allocated+". RM may have assignment issues") */
        LOG.releasing_unassigned_and_invalid_contain(allocated.toString()).info();
        containerNotAssigned(allocated);
      }
    }
    
    @SuppressWarnings("unchecked")
    private void containerAssigned(Container allocated, 
                                    ContainerRequest assigned) {
      // Update resource requests
      decContainerReq(assigned);

      // send the container-assigned event to task attempt
      eventHandler.handle(new TaskAttemptContainerAssignedEvent(
          assigned.attemptID, allocated, applicationACLs));

      assignedRequests.add(allocated, assigned.attemptID);

      if (LogGlobal.isDebugEnabled()) {
        /* LOG.info("Assigned container ("+allocated+") "+" to task "+assigned.attemptID+" on node "+allocated.getNodeId().toString()) */
        LOG.assigned_container_task_node(allocated.toString(), String.valueOf(assigned.attemptID), String.valueOf(allocated.getNodeId().toString())).tag("methodCall").info();
      }
    }
    
    private void containerNotAssigned(Container allocated) {
      containersReleased++;
      release(allocated.getId());      
    }
    
    private ContainerRequest assignWithoutLocality(Container allocated) {
      ContainerRequest assigned = null;
      
      Priority priority = allocated.getPriority();
      if (PRIORITY_FAST_FAIL_MAP.equals(priority)) {
        /* LOG.info("Assigning container "+allocated+" to fast fail map") */
        LOG.assigning_container_fast_fail_map(allocated.toString()).info();
        assigned = assignToFailedMap(allocated);
      } else if (PRIORITY_REDUCE.equals(priority)) {
        if (LogGlobal.isDebugEnabled()) {
          /* LOG.debug("Assigning container "+allocated+" to reduce") */
          LOG.assigning_container_reduce(allocated.toString()).debug();
        }
        assigned = assignToReduce(allocated);
      }
        
      return assigned;
    }
        
    private void assignContainers(List<Container> allocatedContainers) {
      Iterator<Container> it = allocatedContainers.iterator();
      while (it.hasNext()) {
        Container allocated = it.next();
        ContainerRequest assigned = assignWithoutLocality(allocated);
        if (assigned != null) {
          containerAssigned(allocated, assigned);
          it.remove();
        }
      }

      assignMapsWithLocality(allocatedContainers);
    }
    
    private ContainerRequest getContainerReqToReplace(Container allocated) {
      /* LOG.info("Finding containerReq for allocated container: "+allocated) */
      LOG.finding_containerreq_for_allocated_conta(allocated.toString()).info();
      Priority priority = allocated.getPriority();
      ContainerRequest toBeReplaced = null;
      if (PRIORITY_FAST_FAIL_MAP.equals(priority)) {
        /* LOG.info("Replacing FAST_FAIL_MAP container "+allocated.getId()) */
        LOG.replacing_fast_fail_map_container(String.valueOf(allocated.getId())).tag("methodCall").info();
        Iterator<TaskAttemptId> iter = earlierFailedMaps.iterator();
        while (toBeReplaced == null && iter.hasNext()) {
          toBeReplaced = maps.get(iter.next());
        }
        /* LOG.info("Found replacement: "+toBeReplaced) */
        LOG.found_replacement(toBeReplaced.toString()).info();
        return toBeReplaced;
      }
      else if (PRIORITY_MAP.equals(priority)) {
        /* LOG.info("Replacing MAP container "+allocated.getId()) */
        LOG.replacing_map_container(String.valueOf(allocated.getId())).tag("methodCall").info();
        // allocated container was for a map
        String host = allocated.getNodeId().getHost();
        LinkedList<TaskAttemptId> list = mapsHostMapping.get(host);
        if (list != null && list.size() > 0) {
          TaskAttemptId tId = list.removeLast();
          if (maps.containsKey(tId)) {
            toBeReplaced = maps.remove(tId);
          }
        }
        else {
          TaskAttemptId tId = maps.keySet().iterator().next();
          toBeReplaced = maps.remove(tId);          
        }        
      }
      else if (PRIORITY_REDUCE.equals(priority)) {
        TaskAttemptId tId = reduces.keySet().iterator().next();
        toBeReplaced = reduces.remove(tId);    
      }
      /* LOG.info("Found replacement: "+toBeReplaced) */
      LOG.found_replacement(toBeReplaced.toString()).info();
      return toBeReplaced;
    }
    
    
    @SuppressWarnings("unchecked")
    private ContainerRequest assignToFailedMap(Container allocated) {
      //try to assign to earlierFailedMaps if present
      ContainerRequest assigned = null;
      while (assigned == null && earlierFailedMaps.size() > 0) {
        TaskAttemptId tId = earlierFailedMaps.removeFirst();      
        if (maps.containsKey(tId)) {
          assigned = maps.remove(tId);
          JobCounterUpdateEvent jce =
            new JobCounterUpdateEvent(assigned.attemptID.getTaskId().getJobId());
          jce.addCounterUpdate(JobCounter.OTHER_LOCAL_MAPS, 1);
          eventHandler.handle(jce);
          /* LOG.info("Assigned from earlierFailedMaps") */
          LOG.assigned_from_earlierfailedmaps().info();
          break;
        }
      }
      return assigned;
    }
    
    private ContainerRequest assignToReduce(Container allocated) {
      ContainerRequest assigned = null;
      //try to assign to reduces if present
      if (assigned == null && reduces.size() > 0) {
        TaskAttemptId tId = reduces.keySet().iterator().next();
        assigned = reduces.remove(tId);
        /* LOG.info("Assigned to reduce") */
        LOG.assigned_reduce().info();
      }
      return assigned;
    }
    
    @SuppressWarnings("unchecked")
    private void assignMapsWithLocality(List<Container> allocatedContainers) {
      // try to assign to all nodes first to match node local
      Iterator<Container> it = allocatedContainers.iterator();
      while(it.hasNext() && maps.size() > 0){
        Container allocated = it.next();        
        Priority priority = allocated.getPriority();
        assert PRIORITY_MAP.equals(priority);
        // "if (maps.containsKey(tId))" below should be almost always true.
        // hence this while loop would almost always have O(1) complexity
        String host = allocated.getNodeId().getHost();
        LinkedList<TaskAttemptId> list = mapsHostMapping.get(host);
        while (list != null && list.size() > 0) {
          if (LogGlobal.isDebugEnabled()) {
            /* LOG.debug("Host matched to the request list "+host) */
            LOG.host_matched_request_list(host).debug();
          }
          TaskAttemptId tId = list.removeFirst();
          if (maps.containsKey(tId)) {
            ContainerRequest assigned = maps.remove(tId);
            containerAssigned(allocated, assigned);
            it.remove();
            JobCounterUpdateEvent jce =
              new JobCounterUpdateEvent(assigned.attemptID.getTaskId().getJobId());
            jce.addCounterUpdate(JobCounter.DATA_LOCAL_MAPS, 1);
            eventHandler.handle(jce);
            hostLocalAssigned++;
            if (LogGlobal.isDebugEnabled()) {
              /* LOG.debug("Assigned based on host match "+host) */
              LOG.assigned_based_host_match(host).debug();
            }
            break;
          }
        }
      }
      
      // try to match all rack local
      it = allocatedContainers.iterator();
      while(it.hasNext() && maps.size() > 0){
        Container allocated = it.next();
        Priority priority = allocated.getPriority();
        assert PRIORITY_MAP.equals(priority);
        // "if (maps.containsKey(tId))" below should be almost always true.
        // hence this while loop would almost always have O(1) complexity
        String host = allocated.getNodeId().getHost();
        String rack = RackResolver.resolve(host).getNetworkLocation();
        LinkedList<TaskAttemptId> list = mapsRackMapping.get(rack);
        while (list != null && list.size() > 0) {
          TaskAttemptId tId = list.removeFirst();
          if (maps.containsKey(tId)) {
            ContainerRequest assigned = maps.remove(tId);
            containerAssigned(allocated, assigned);
            it.remove();
            JobCounterUpdateEvent jce =
              new JobCounterUpdateEvent(assigned.attemptID.getTaskId().getJobId());
            jce.addCounterUpdate(JobCounter.RACK_LOCAL_MAPS, 1);
            eventHandler.handle(jce);
            rackLocalAssigned++;
            if (LogGlobal.isDebugEnabled()) {
              /* LOG.debug("Assigned based on rack match "+rack) */
              LOG.assigned_based_rack_match(rack).debug();
            }
            break;
          }
        }
      }
      
      // assign remaining
      it = allocatedContainers.iterator();
      while(it.hasNext() && maps.size() > 0){
        Container allocated = it.next();
        Priority priority = allocated.getPriority();
        assert PRIORITY_MAP.equals(priority);
        TaskAttemptId tId = maps.keySet().iterator().next();
        ContainerRequest assigned = maps.remove(tId);
        containerAssigned(allocated, assigned);
        it.remove();
        JobCounterUpdateEvent jce =
          new JobCounterUpdateEvent(assigned.attemptID.getTaskId().getJobId());
        jce.addCounterUpdate(JobCounter.OTHER_LOCAL_MAPS, 1);
        eventHandler.handle(jce);
        if (LogGlobal.isDebugEnabled()) {
          /* LOG.debug("Assigned based on * match") */
          LOG.assigned_based_match().debug();
        }
      }
    }
  }

  private class AssignedRequests {
    private final Map<ContainerId, TaskAttemptId> containerToAttemptMap =
      new HashMap<ContainerId, TaskAttemptId>();
    private final LinkedHashMap<TaskAttemptId, Container> maps = 
      new LinkedHashMap<TaskAttemptId, Container>();
    private final LinkedHashMap<TaskAttemptId, Container> reduces = 
      new LinkedHashMap<TaskAttemptId, Container>();
    private final Set<TaskAttemptId> preemptionWaitingReduces = 
      new HashSet<TaskAttemptId>();
    
    void add(Container container, TaskAttemptId tId) {
      /* LOG.info("Assigned container "+container.getId().toString()+" to "+tId) */
      LOG.assigned_container(String.valueOf(container.getId().toString()), tId.toString()).tag("methodCall").info();
      containerToAttemptMap.put(container.getId(), tId);
      if (tId.getTaskId().getTaskType().equals(TaskType.MAP)) {
        maps.put(tId, container);
      } else {
        reduces.put(tId, container);
      }
    }

    @SuppressWarnings("unchecked")
    void preemptReduce(int toPreempt) {
      List<TaskAttemptId> reduceList = new ArrayList<TaskAttemptId>
        (reduces.keySet());
      //sort reduces on progress
      Collections.sort(reduceList,
          new Comparator<TaskAttemptId>() {
        @Override
        public int compare(TaskAttemptId o1, TaskAttemptId o2) {
          return Float.compare(
              getJob().getTask(o1.getTaskId()).getAttempt(o1).getProgress(),
              getJob().getTask(o2.getTaskId()).getAttempt(o2).getProgress());
        }
      });
      
      for (int i = 0; i < toPreempt && reduceList.size() > 0; i++) {
        TaskAttemptId id = reduceList.remove(0);//remove the one on top
        /* LOG.info("Preempting "+id) */
        LOG.preempting(id.toString()).info();
        preemptionWaitingReduces.add(id);
        eventHandler.handle(new TaskAttemptKillEvent(id, RAMPDOWN_DIAGNOSTIC));
      }
    }
    
    boolean remove(TaskAttemptId tId) {
      ContainerId containerId = null;
      if (tId.getTaskId().getTaskType().equals(TaskType.MAP)) {
        containerId = maps.remove(tId).getId();
      } else {
        containerId = reduces.remove(tId).getId();
        if (containerId != null) {
          boolean preempted = preemptionWaitingReduces.remove(tId);
          if (preempted) {
            /* LOG.info("Reduce preemption successful "+tId) */
            LOG.reduce_preemption_successful(tId.toString()).info();
          }
        }
      }
      
      if (containerId != null) {
        containerToAttemptMap.remove(containerId);
        return true;
      }
      return false;
    }
    
    TaskAttemptId get(ContainerId cId) {
      return containerToAttemptMap.get(cId);
    }

    ContainerId get(TaskAttemptId tId) {
      Container taskContainer;
      if (tId.getTaskId().getTaskType().equals(TaskType.MAP)) {
        taskContainer = maps.get(tId);
      } else {
        taskContainer = reduces.get(tId);
      }

      if (taskContainer == null) {
        return null;
      } else {
        return taskContainer.getId();
      }
    }
  }

  private class ScheduleStats {
    int numPendingReduces;
    int numScheduledMaps;
    int numScheduledReduces;
    int numAssignedMaps;
    int numAssignedReduces;
    int numCompletedMaps;
    int numCompletedReduces;
    int numContainersAllocated;
    int numContainersReleased;

    public void updateAndLogIfChanged(String msgPrefix) {
      boolean changed = false;

      // synchronized to fix findbug warnings
      synchronized (RMContainerAllocator.this) {
        changed |= (numPendingReduces != pendingReduces.size());
        numPendingReduces = pendingReduces.size();
        changed |= (numScheduledMaps != scheduledRequests.maps.size());
        numScheduledMaps = scheduledRequests.maps.size();
        changed |= (numScheduledReduces != scheduledRequests.reduces.size());
        numScheduledReduces = scheduledRequests.reduces.size();
        changed |= (numAssignedMaps != assignedRequests.maps.size());
        numAssignedMaps = assignedRequests.maps.size();
        changed |= (numAssignedReduces != assignedRequests.reduces.size());
        numAssignedReduces = assignedRequests.reduces.size();
        changed |= (numCompletedMaps != getJob().getCompletedMaps());
        numCompletedMaps = getJob().getCompletedMaps();
        changed |= (numCompletedReduces != getJob().getCompletedReduces());
        numCompletedReduces = getJob().getCompletedReduces();
        changed |= (numContainersAllocated != containersAllocated);
        numContainersAllocated = containersAllocated;
        changed |= (numContainersReleased != containersReleased);
        numContainersReleased = containersReleased;
      }

      if (changed) {
        log(msgPrefix);
      }
    }

    public void log(String msgPrefix) {
        /* LOG.info(msgPrefix+"PendingReds:"+numPendingReduces+" ScheduledMaps:"+numScheduledMaps+" ScheduledReds:"+numScheduledReduces+" AssignedMaps:"+numAssignedMaps+" AssignedReds:"+numAssignedReduces+" CompletedMaps:"+numCompletedMaps+" CompletedReds:"+numCompletedReduces+" ContAlloc:"+numContainersAllocated+" ContRel:"+numContainersReleased+" HostLocal:"+hostLocalAssigned+" RackLocal:"+rackLocalAssigned) */
        LOG.pendingreds_scheduledmaps_scheduledreds_(msgPrefix, numPendingReduces, numScheduledMaps, numScheduledReduces, numAssignedMaps, numAssignedReduces, numCompletedMaps, numCompletedReduces, numContainersAllocated, numContainersReleased, hostLocalAssigned, rackLocalAssigned).info();
    }
  }

  static class PreemptionContext extends AMPreemptionPolicy.Context {
    final AssignedRequests reqs;

    PreemptionContext(AssignedRequests reqs) {
      this.reqs = reqs;
    }
    @Override
    public TaskAttemptId getTaskAttempt(ContainerId container) {
      return reqs.get(container);
    }

    @Override
    public List<Container> getContainers(TaskType t){
      if(TaskType.REDUCE.equals(t))
        return new ArrayList<Container>(reqs.reduces.values());
      if(TaskType.MAP.equals(t))
        return new ArrayList<Container>(reqs.maps.values());
      return null;
    }

  }

}
