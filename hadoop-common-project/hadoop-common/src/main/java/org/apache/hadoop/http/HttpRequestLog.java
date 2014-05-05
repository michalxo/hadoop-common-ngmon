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
package org.apache.hadoop.http;

import java.util.HashMap;

import log_events.org.apache.hadoop.HttpNamespace;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.impl.Log4JLogger;
import org.ngmon.logger.core.SimpleLogger;
import org.ngmon.logger.logtranslator.LogGlobal;
import log_events.org.apache.hadoop.HttpNamespace;
import org.ngmon.logger.core.SimpleLogger;
import org.ngmon.logger.logtranslator.LogGlobal;
import org.apache.commons.logging.LogConfigurationException;
import org.ngmon.logger.core.LoggerFactory;
import org.apache.log4j.Appender;
import org.apache.log4j.Logger;
import org.mortbay.jetty.NCSARequestLog;
import org.mortbay.jetty.RequestLog;

/**
 * RequestLog object for use with Http
 */
public class HttpRequestLog {

  public static final HttpNamespace LOG = LoggerFactory.getLogger(HttpNamespace.class, new SimpleLogger());
  private static final HashMap<String, String> serverToComponent;

  static {
    serverToComponent = new HashMap<String, String>();
    serverToComponent.put("cluster", "resourcemanager");
    serverToComponent.put("hdfs", "namenode");
    serverToComponent.put("node", "nodemanager");
  }

  public static RequestLog getRequestLog(String name) {

    String lookup = serverToComponent.get(name);
    if (lookup != null) {
      name = lookup;
    }
    String loggerName = "http.requests." + name;
    String appenderName = name + "requestlog";
    Log logger = LogFactory.getLog(loggerName);

    boolean isLog4JLogger;;
    try {
      isLog4JLogger = logger instanceof Log4JLogger;
    } catch (NoClassDefFoundError err) {
      // In some dependent projects, log4j may not even be on the classpath at
      // runtime, in which case the above instanceof check will throw
      // NoClassDefFoundError.
      /* LOG.debug("Could not load Log4JLogger class",err) */
      LOG.could_not_load_logjlogger_class(err.toString()).debug();
      isLog4JLogger = false;
    }
    if (isLog4JLogger) {
      Log4JLogger httpLog4JLog = (Log4JLogger)logger;
      Logger httpLogger = httpLog4JLog.getLogger();
      Appender appender = null;

      try {
        appender = httpLogger.getAppender(appenderName);
      } catch (LogConfigurationException e) {
        /* LOG.warn("Http request log for "+loggerName+" could not be created") */
        LOG.http_request_log_for_could_not(loggerName).warn();
        throw e;
      }

      if (appender == null) {
        /* LOG.info("Http request log for "+loggerName+" is not defined") */
        LOG.http_request_log_for_not_defined(loggerName).info();
        return null;
      }

      if (appender instanceof HttpRequestLogAppender) {
        HttpRequestLogAppender requestLogAppender
          = (HttpRequestLogAppender)appender;
        NCSARequestLog requestLog = new NCSARequestLog();
        requestLog.setFilename(requestLogAppender.getFilename());
        requestLog.setRetainDays(requestLogAppender.getRetainDays());
        return requestLog;
      }
      else {
        /* LOG.warn("Jetty request log for "+loggerName+" was of the wrong class") */
        LOG.jetty_request_log_for_was_wrong(loggerName).warn();
        return null;
      }
    }
    else {
      /* LOG.warn("Jetty request log can only be enabled using Log4j") */
      LOG.jetty_request_log_can_only_enabled().warn();
      return null;
    }
  }
}
