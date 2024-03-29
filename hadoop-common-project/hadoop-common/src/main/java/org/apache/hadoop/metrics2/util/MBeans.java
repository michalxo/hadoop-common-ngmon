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
package org.apache.hadoop.metrics2.util;

import java.lang.management.ManagementFactory;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import log_events.org.apache.hadoop.Metrics2Namespace;
import org.ngmon.logger.core.SimpleLogger;
import org.ngmon.logger.logtranslator.LogGlobal;
import org.ngmon.logger.core.LoggerFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.metrics2.lib.DefaultMetricsSystem;

/**
 * This util class provides a method to register an MBean using
 * our standard naming convention as described in the doc
 *  for {link {@link #register(String, String, Object)}
 */
@InterfaceAudience.Public
@InterfaceStability.Stable
public class MBeans {
  private static final /* LogLOG=LogFactory.getLog(MBeans.class); */
			Metrics2Namespace LOG = LoggerFactory.getLogger(Metrics2Namespace.class, new SimpleLogger());

  /**
   * Register the MBean using our standard MBeanName format
   * "hadoop:service=<serviceName>,name=<nameName>"
   * Where the <serviceName> and <nameName> are the supplied parameters
   *
   * @param serviceName
   * @param nameName
   * @param theMbean - the MBean to register
   * @return the named used to register the MBean
   */
  static public ObjectName register(String serviceName, String nameName,
                                    Object theMbean) {
    final MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
    ObjectName name = getMBeanName(serviceName, nameName);
    if (name != null) {
      try {
        mbs.registerMBean(theMbean, name);
        /* LOG.debug("Registered "+name) */
        LOG.registered(name.toString()).debug();
        return name;
      } catch (InstanceAlreadyExistsException iaee) {
        if (LogGlobal.isTraceEnabled()) {
          /* LOG.trace("Failed to register MBean \""+name+"\"",iaee) */
          LOG.failed_register_mbean(name.toString(), iaee.toString()).trace();
        } else {
          /* LOG.warn("Failed to register MBean \""+name+"\": Instance already exists.") */
          LOG.failed_register_mbean_instance_already_e(name.toString()).warn();
        }
      } catch (Exception e) {
        /* LOG.warn("Failed to register MBean \""+name+"\"",e) */
        LOG.failed_register_mbean(name.toString(), e.toString()).warn();
      }
    }
    return null;
  }

  static public void unregister(ObjectName mbeanName) {
    /* LOG.debug("Unregistering "+mbeanName) */
    LOG.unregistering(mbeanName.toString()).debug();
    final MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
    if (mbeanName == null) {
      /* LOG.debug("Stacktrace: ",newThrowable()) */
      LOG.stacktrace(new Throwable().toString()).tag("methodCall").debug();
      return;
    }
    try {
      mbs.unregisterMBean(mbeanName);
    } catch (Exception e) {
      /* LOG.warn("Error unregistering "+mbeanName,e) */
      LOG.error_unregistering(mbeanName.toString(), e.toString()).warn();
    }
  }

  static private ObjectName getMBeanName(String serviceName, String nameName) {
    ObjectName name = null;
    String nameStr = "Hadoop:service="+ serviceName +",name="+ nameName;
    try {
      name = DefaultMetricsSystem.newMBeanName(nameStr);
    } catch (Exception e) {
      /* LOG.warn("Error creating MBean object name: "+nameStr,e) */
      LOG.error_creating_mbean_object_name(nameStr, e.toString()).warn();
    }
    return name;
  }
}
