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

package org.apache.hadoop.service;

import java.util.ArrayList;
import java.util.List;

import log_events.org.apache.hadoop.ServiceNamespace;
import org.apache.commons.logging.LogFactory;
import org.ngmon.logger.core.SimpleLogger;
import org.ngmon.logger.logtranslator.LogGlobal;
import org.ngmon.logger.core.LoggerFactory;
import org.apache.hadoop.classification.InterfaceAudience.Public;
import org.apache.hadoop.classification.InterfaceStability.Evolving;
import org.apache.hadoop.conf.Configuration;

/**
 * Composition of services.
 */
@Public
@Evolving
public class CompositeService extends AbstractService {

  private static final /* LogLOG=LogFactory.getLog(CompositeService.class); */
			ServiceNamespace LOG = LoggerFactory.getLogger(ServiceNamespace.class, new SimpleLogger());

  /**
   * Policy on shutdown: attempt to close everything (purest) or
   * only try to close started services (which assumes
   * that the service implementations may not handle the stop() operation
   * except when started.
   * Irrespective of this policy, if a child service fails during
   * its init() or start() operations, it will have stop() called on it.
   */
  protected static final boolean STOP_ONLY_STARTED_SERVICES = false;

  private final List<Service> serviceList = new ArrayList<Service>();

  public CompositeService(String name) {
    super(name);
  }

  /**
   * Get a cloned list of services
   * @return a list of child services at the time of invocation -
   * added services will not be picked up.
   */
  public List<Service> getServices() {
    synchronized (serviceList) {
      return new ArrayList<Service>(serviceList);
    }
  }

  /**
   * Add the passed {@link Service} to the list of services managed by this
   * {@link CompositeService}
   * @param service the {@link Service} to be added
   */
  protected void addService(Service service) {
    if (LogGlobal.isDebugEnabled()) {
      /* LOG.debug("Adding service "+service.getName()) */
      LOG.adding_service(String.valueOf(service.getName())).tag("methodCall").debug();
    }
    synchronized (serviceList) {
      serviceList.add(service);
    }
  }

  /**
   * If the passed object is an instance of {@link Service},
   * add it to the list of services managed by this {@link CompositeService}
   * @param object
   * @return true if a service is added, false otherwise.
   */
  protected boolean addIfService(Object object) {
    if (object instanceof Service) {
      addService((Service) object);
      return true;
    } else {
      return false;
    }
  }

  protected synchronized boolean removeService(Service service) {
    synchronized (serviceList) {
      return serviceList.remove(service);
    }
  }

  protected void serviceInit(Configuration conf) throws Exception {
    List<Service> services = getServices();
    if (LogGlobal.isDebugEnabled()) {
      /* LOG.debug(getName()+": initing services, size="+services.size()) */
      LOG.initing_services_size(getName(), String.valueOf(services.size())).tag("methodCall").debug();
    }
    for (Service service : services) {
      service.init(conf);
    }
    super.serviceInit(conf);
  }

  protected void serviceStart() throws Exception {
    List<Service> services = getServices();
    if (LogGlobal.isDebugEnabled()) {
      /* LOG.debug(getName()+": starting services, size="+services.size()) */
      LOG.starting_services_size(getName(), String.valueOf(services.size())).tag("methodCall").debug();
    }
    for (Service service : services) {
      // start the service. If this fails that service
      // will be stopped and an exception raised
      service.start();
    }
    super.serviceStart();
  }

  protected void serviceStop() throws Exception {
    //stop all services that were started
    int numOfServicesToStop = serviceList.size();
    if (LogGlobal.isDebugEnabled()) {
      /* LOG.debug(getName()+": stopping services, size="+numOfServicesToStop) */
      LOG.stopping_services_size(getName(), numOfServicesToStop).tag("methodCall").debug();
    }
    stop(numOfServicesToStop, STOP_ONLY_STARTED_SERVICES);
    super.serviceStop();
  }

  /**
   * Stop the services in reverse order
   *
   * @param numOfServicesStarted index from where the stop should work
   * @param stopOnlyStartedServices flag to say "only start services that are
   * started, not those that are NOTINITED or INITED.
   * @throws RuntimeException the first exception raised during the
   * stop process -<i>after all services are stopped</i>
   */
  private synchronized void stop(int numOfServicesStarted,
                                 boolean stopOnlyStartedServices) {
    // stop in reverse order of start
    Exception firstException = null;
    List<Service> services = getServices();
    for (int i = numOfServicesStarted - 1; i >= 0; i--) {
      Service service = services.get(i);
      if (LogGlobal.isDebugEnabled()) {
        /* LOG.debug("Stopping service #"+i+": "+service) */
        LOG.stopping_service(i, service.toString()).debug();
      }
      STATE state = service.getServiceState();
      //depending on the stop police
      if (state == STATE.STARTED 
         || (!stopOnlyStartedServices && state == STATE.INITED)) {
        Exception ex = ServiceOperations.stopQuietly(LogFactory.getLog(CompositeService.class), service);
        if (ex != null && firstException == null) {
          firstException = ex;
        }
      }
    }
    //after stopping all services, rethrow the first exception raised
    if (firstException != null) {
      throw ServiceStateException.convert(firstException);
    }
  }

  /**
   * JVM Shutdown hook for CompositeService which will stop the give
   * CompositeService gracefully in case of JVM shutdown.
   */
  public static class CompositeServiceShutdownHook implements Runnable {

    private CompositeService compositeService;

    public CompositeServiceShutdownHook(CompositeService compositeService) {
      this.compositeService = compositeService;
    }

    @Override
    public void run() {
      ServiceOperations.stopQuietly(compositeService);
    }
  }

}
