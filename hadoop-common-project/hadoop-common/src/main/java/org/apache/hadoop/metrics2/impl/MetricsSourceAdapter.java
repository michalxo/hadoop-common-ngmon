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

package org.apache.hadoop.metrics2.impl;

import java.util.HashMap;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.InvalidAttributeValueException;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.ObjectName;
import javax.management.ReflectionException;

import static com.google.common.base.Preconditions.*;
import com.google.common.collect.Maps;
import log_events.org.apache.hadoop.Metrics2Namespace;
import org.ngmon.logger.core.SimpleLogger;
import org.ngmon.logger.logtranslator.LogGlobal;
import org.ngmon.logger.core.LoggerFactory;

import org.apache.hadoop.metrics2.AbstractMetric;
import org.apache.hadoop.metrics2.MetricsFilter;
import org.apache.hadoop.metrics2.MetricsSource;
import org.apache.hadoop.metrics2.MetricsTag;
import static org.apache.hadoop.metrics2.impl.MetricsConfig.*;
import org.apache.hadoop.metrics2.util.MBeans;
import org.apache.hadoop.util.Time;

import static org.apache.hadoop.metrics2.util.Contracts.*;

/**
 * An adapter class for metrics source and associated filter and jmx impl
 */
class MetricsSourceAdapter implements DynamicMBean {

  private static final /* LogLOG=LogFactory.getLog(MetricsSourceAdapter.class); */
			Metrics2Namespace LOG = LoggerFactory.getLogger(Metrics2Namespace.class, new SimpleLogger());

  private final String prefix, name;
  private final MetricsSource source;
  private final MetricsFilter recordFilter, metricFilter;
  private final HashMap<String, Attribute> attrCache;
  private final MBeanInfoBuilder infoBuilder;
  private final Iterable<MetricsTag> injectedTags;

  private Iterable<MetricsRecordImpl> lastRecs;
  private long jmxCacheTS = 0;
  private int jmxCacheTTL;
  private MBeanInfo infoCache;
  private ObjectName mbeanName;
  private final boolean startMBeans;

  MetricsSourceAdapter(String prefix, String name, String description,
                       MetricsSource source, Iterable<MetricsTag> injectedTags,
                       MetricsFilter recordFilter, MetricsFilter metricFilter,
                       int jmxCacheTTL, boolean startMBeans) {
    this.prefix = checkNotNull(prefix, "prefix");
    this.name = checkNotNull(name, "name");
    this.source = checkNotNull(source, "source");
    attrCache = Maps.newHashMap();
    infoBuilder = new MBeanInfoBuilder(name, description);
    this.injectedTags = injectedTags;
    this.recordFilter = recordFilter;
    this.metricFilter = metricFilter;
    this.jmxCacheTTL = checkArg(jmxCacheTTL, jmxCacheTTL > 0, "jmxCacheTTL");
    this.startMBeans = startMBeans;
  }

  MetricsSourceAdapter(String prefix, String name, String description,
                       MetricsSource source, Iterable<MetricsTag> injectedTags,
                       int period, MetricsConfig conf) {
    this(prefix, name, description, source, injectedTags,
         conf.getFilter(RECORD_FILTER_KEY),
         conf.getFilter(METRIC_FILTER_KEY),
         period + 1, // hack to avoid most of the "innocuous" races.
         conf.getBoolean(START_MBEANS_KEY, true));
  }

  void start() {
    if (startMBeans) startMBeans();
  }

  @Override
  public Object getAttribute(String attribute)
      throws AttributeNotFoundException, MBeanException, ReflectionException {
    updateJmxCache();
    synchronized(this) {
      Attribute a = attrCache.get(attribute);
      if (a == null) {
        throw new AttributeNotFoundException(attribute +" not found");
      }
      if (LogGlobal.isDebugEnabled()) {
        /* LOG.debug(attribute+": "+a) */
        LOG.attributea(attribute, a.toString()).debug();
      }
      return a.getValue();
    }
  }

  @Override
  public void setAttribute(Attribute attribute)
      throws AttributeNotFoundException, InvalidAttributeValueException,
             MBeanException, ReflectionException {
    throw new UnsupportedOperationException("Metrics are read-only.");
  }

  @Override
  public AttributeList getAttributes(String[] attributes) {
    updateJmxCache();
    synchronized(this) {
      AttributeList ret = new AttributeList();
      for (String key : attributes) {
        Attribute attr = attrCache.get(key);
        if (LogGlobal.isDebugEnabled()) {
          /* LOG.debug(key+": "+attr) */
          LOG.keyattr(key, attr.toString()).debug();
        }
        ret.add(attr);
      }
      return ret;
    }
  }

  @Override
  public AttributeList setAttributes(AttributeList attributes) {
    throw new UnsupportedOperationException("Metrics are read-only.");
  }

  @Override
  public Object invoke(String actionName, Object[] params, String[] signature)
      throws MBeanException, ReflectionException {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  @Override
  public MBeanInfo getMBeanInfo() {
    updateJmxCache();
    return infoCache;
  }

  private void updateJmxCache() {
    boolean getAllMetrics = false;
    synchronized(this) {
      if (Time.now() - jmxCacheTS >= jmxCacheTTL) {
        // temporarilly advance the expiry while updating the cache
        jmxCacheTS = Time.now() + jmxCacheTTL;
        if (lastRecs == null) {
          getAllMetrics = true;
        }
      }
      else {
        return;
      }
    }

    if (getAllMetrics) {
      MetricsCollectorImpl builder = new MetricsCollectorImpl();
      getMetrics(builder, true);
    }

    synchronized(this) {
      int oldCacheSize = attrCache.size();
      int newCacheSize = updateAttrCache();
      if (oldCacheSize < newCacheSize) {
        updateInfoCache();
      }
      jmxCacheTS = Time.now();
      lastRecs = null;  // in case regular interval update is not running
    }
  }

  Iterable<MetricsRecordImpl> getMetrics(MetricsCollectorImpl builder,
                                         boolean all) {
    builder.setRecordFilter(recordFilter).setMetricFilter(metricFilter);
    synchronized(this) {
      if (lastRecs == null && jmxCacheTS == 0) {
        all = true; // Get all the metrics to populate the sink caches
      }
    }
    try {
      source.getMetrics(builder, all);
    } catch (Exception e) {
      /* LOG.error("Error getting metrics from source "+name,e) */
      LOG.error_getting_metrics_from_source(name, e.toString()).error();
    }
    for (MetricsRecordBuilderImpl rb : builder) {
      for (MetricsTag t : injectedTags) {
        rb.add(t);
      }
    }
    synchronized(this) {
      lastRecs = builder.getRecords();
      return lastRecs;
    }
  }

  synchronized void stop() {
    stopMBeans();
  }

  synchronized void startMBeans() {
    if (mbeanName != null) {
      /* LOG.warn("MBean "+name+" already initialized!") */
      LOG.mbean_already_initialized(name).warn();
      /* LOG.debug("Stacktrace: ",newThrowable()) */
      LOG.stacktrace(new Throwable().toString()).tag("methodCall").debug();
      return;
    }
    mbeanName = MBeans.register(prefix, name, this);
    /* LOG.debug("MBean for source "+name+" registered.") */
    LOG.mbean_for_source_registered(name).debug();
  }

  synchronized void stopMBeans() {
    if (mbeanName != null) {
      MBeans.unregister(mbeanName);
      mbeanName = null;
    }
  }

  private void updateInfoCache() {
    /* LOG.debug("Updating info cache...") */
    LOG.updating_info_cache().debug();
    infoCache = infoBuilder.reset(lastRecs).get();
    /* LOG.debug("Done") */
    LOG.done().debug();
  }

  private int updateAttrCache() {
    /* LOG.debug("Updating attr cache...") */
    LOG.updating_attr_cache().debug();
    int recNo = 0;
    int numMetrics = 0;
    for (MetricsRecordImpl record : lastRecs) {
      for (MetricsTag t : record.tags()) {
        setAttrCacheTag(t, recNo);
        ++numMetrics;
      }
      for (AbstractMetric m : record.metrics()) {
        setAttrCacheMetric(m, recNo);
        ++numMetrics;
      }
      ++recNo;
    }
    /* LOG.debug("Done. # tags & metrics="+numMetrics) */
    LOG.done_tags_metrics(numMetrics).debug();
    return numMetrics;
  }

  private static String tagName(String name, int recNo) {
    StringBuilder sb = new StringBuilder(name.length() + 16);
    sb.append("tag.").append(name);
    if (recNo > 0) {
      sb.append('.').append(recNo);
    }
    return sb.toString();
  }

  private void setAttrCacheTag(MetricsTag tag, int recNo) {
    String key = tagName(tag.name(), recNo);
    attrCache.put(key, new Attribute(key, tag.value()));
  }

  private static String metricName(String name, int recNo) {
    if (recNo == 0) {
      return name;
    }
    StringBuilder sb = new StringBuilder(name.length() + 12);
    sb.append(name);
    if (recNo > 0) {
      sb.append('.').append(recNo);
    }
    return sb.toString();
  }

  private void setAttrCacheMetric(AbstractMetric metric, int recNo) {
    String key = metricName(metric.name(), recNo);
    attrCache.put(key, new Attribute(key, metric.value()));
  }

  String name() {
    return name;
  }

  MetricsSource source() {
    return source;
  }
}
