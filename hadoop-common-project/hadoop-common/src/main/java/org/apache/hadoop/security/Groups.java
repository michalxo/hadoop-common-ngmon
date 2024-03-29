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
package org.apache.hadoop.security;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.hadoop.HadoopIllegalArgumentException;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.Time;

import log_events.org.apache.hadoop.SecurityNamespace;
import org.ngmon.logger.core.SimpleLogger;
import org.ngmon.logger.logtranslator.LogGlobal;
import org.ngmon.logger.core.LoggerFactory;

/**
 * A user-to-groups mapping service.
 * 
 * {@link Groups} allows for server to get the various group memberships
 * of a given user via the {@link #getGroups(String)} call, thus ensuring 
 * a consistent user-to-groups mapping and protects against vagaries of 
 * different mappings on servers and clients in a Hadoop cluster. 
 */
@InterfaceAudience.LimitedPrivate({"HDFS", "MapReduce"})
@InterfaceStability.Evolving
public class Groups {
  private static final /* LogLOG=LogFactory.getLog(Groups.class); */
			SecurityNamespace LOG = LoggerFactory.getLogger(SecurityNamespace.class, new SimpleLogger());
  
  private final GroupMappingServiceProvider impl;
  
  private final Map<String, CachedGroups> userToGroupsMap = 
    new ConcurrentHashMap<String, CachedGroups>();
  private final Map<String, List<String>> staticUserToGroupsMap = 
      new HashMap<String, List<String>>();
  private final long cacheTimeout;
  private final long warningDeltaMs;

  public Groups(Configuration conf) {
    impl = 
      ReflectionUtils.newInstance(
          conf.getClass(CommonConfigurationKeys.HADOOP_SECURITY_GROUP_MAPPING, 
                        ShellBasedUnixGroupsMapping.class, 
                        GroupMappingServiceProvider.class), 
          conf);
    
    cacheTimeout = 
      conf.getLong(CommonConfigurationKeys.HADOOP_SECURITY_GROUPS_CACHE_SECS, 
          CommonConfigurationKeys.HADOOP_SECURITY_GROUPS_CACHE_SECS_DEFAULT) * 1000;
    warningDeltaMs =
      conf.getLong(CommonConfigurationKeys.HADOOP_SECURITY_GROUPS_CACHE_WARN_AFTER_MS,
        CommonConfigurationKeys.HADOOP_SECURITY_GROUPS_CACHE_WARN_AFTER_MS_DEFAULT);
    parseStaticMapping(conf);

    if(LogGlobal.isDebugEnabled())
      /* LOG.debug("Group mapping impl="+impl.getClass().getName()+"; cacheTimeout="+cacheTimeout+"; warningDeltaMs="+warningDeltaMs) */
      LOG.group_mapping_impl_cachetimeout_warningd(String.valueOf(impl.getClass().getName()), cacheTimeout, warningDeltaMs).tag("methodCall").debug();
  }

  /*
   * Parse the hadoop.user.group.static.mapping.overrides configuration to
   * staticUserToGroupsMap
   */
  private void parseStaticMapping(Configuration conf) {
    String staticMapping = conf.get(
        CommonConfigurationKeys.HADOOP_USER_GROUP_STATIC_OVERRIDES,
        CommonConfigurationKeys.HADOOP_USER_GROUP_STATIC_OVERRIDES_DEFAULT);
    Collection<String> mappings = StringUtils.getStringCollection(
        staticMapping, ";");
    for (String users : mappings) {
      Collection<String> userToGroups = StringUtils.getStringCollection(users,
          "=");
      if (userToGroups.size() < 1 || userToGroups.size() > 2) {
        throw new HadoopIllegalArgumentException("Configuration "
            + CommonConfigurationKeys.HADOOP_USER_GROUP_STATIC_OVERRIDES
            + " is invalid");
      }
      String[] userToGroupsArray = userToGroups.toArray(new String[userToGroups
          .size()]);
      String user = userToGroupsArray[0];
      List<String> groups = Collections.emptyList();
      if (userToGroupsArray.length == 2) {
        groups = (List<String>) StringUtils
            .getStringCollection(userToGroupsArray[1]);
      }
      staticUserToGroupsMap.put(user, groups);
    }
  }
  
  /**
   * Get the group memberships of a given user.
   * @param user User's name
   * @return the group memberships of the user
   * @throws IOException
   */
  public List<String> getGroups(String user) throws IOException {
    // No need to lookup for groups of static users
    List<String> staticMapping = staticUserToGroupsMap.get(user);
    if (staticMapping != null) {
      return staticMapping;
    }
    // Return cached value if available
    CachedGroups groups = userToGroupsMap.get(user);
    long startMs = Time.monotonicNow();
    // if cache has a value and it hasn't expired
    if (groups != null && (groups.getTimestamp() + cacheTimeout > startMs)) {
      if(LogGlobal.isDebugEnabled()) {
        /* LOG.debug("Returning cached groups for '"+user+"'") */
        LOG.returning_cached_groups_for(user).debug();
      }
      return groups.getGroups();
    }

    // Create and cache user's groups
    List<String> groupList = impl.getGroups(user);
    long endMs = Time.monotonicNow();
    long deltaMs = endMs - startMs ;
    UserGroupInformation.metrics.addGetGroups(deltaMs);
    if (deltaMs > warningDeltaMs) {
      /* LOG.warn("Potential performance problem: getGroups(user="+user+") "+"took "+deltaMs+" milliseconds.") */
      LOG.potential_performance_problem_getgroups_(user, deltaMs).warn();
    }
    groups = new CachedGroups(groupList, endMs);
    if (groups.getGroups().isEmpty()) {
      throw new IOException("No groups found for user " + user);
    }
    userToGroupsMap.put(user, groups);
    if(LogGlobal.isDebugEnabled()) {
      /* LOG.debug("Returning fetched groups for '"+user+"'") */
      LOG.returning_fetched_groups_for(user).debug();
    }
    return groups.getGroups();
  }
  
  /**
   * Refresh all user-to-groups mappings.
   */
  public void refresh() {
    /* LOG.info("clearing userToGroupsMap cache") */
    LOG.clearing_usertogroupsmap_cache().info();
    try {
      impl.cacheGroupsRefresh();
    } catch (IOException e) {
      /* LOG.warn("Error refreshing groups cache",e) */
      LOG.error_refreshing_groups_cache(e.toString()).warn();
    }
    userToGroupsMap.clear();
  }

  /**
   * Add groups to cache
   *
   * @param groups list of groups to add to cache
   */
  public void cacheGroupsAdd(List<String> groups) {
    try {
      impl.cacheGroupsAdd(groups);
    } catch (IOException e) {
      /* LOG.warn("Error caching groups",e) */
      LOG.error_caching_groups(e.toString()).warn();
    }
  }

  /**
   * Class to hold the cached groups
   */
  private static class CachedGroups {
    final long timestamp;
    final List<String> groups;
    
    /**
     * Create and initialize group cache
     */
    CachedGroups(List<String> groups, long timestamp) {
      this.groups = groups;
      this.timestamp = timestamp;
    }

    /**
     * Returns time of last cache update
     *
     * @return time of last cache update
     */
    public long getTimestamp() {
      return timestamp;
    }

    /**
     * Get list of cached groups
     *
     * @return cached groups
     */
    public List<String> getGroups() {
      return groups;
    }
  }

  private static Groups GROUPS = null;
  
  /**
   * Get the groups being used to map user-to-groups.
   * @return the groups being used to map user-to-groups.
   */
  public static Groups getUserToGroupsMappingService() {
    return getUserToGroupsMappingService(new Configuration()); 
  }

  /**
   * Get the groups being used to map user-to-groups.
   * @param conf
   * @return the groups being used to map user-to-groups.
   */
  public static synchronized Groups getUserToGroupsMappingService(
    Configuration conf) {

    if(GROUPS == null) {
      if(LogGlobal.isDebugEnabled()) {
        /* LOG.debug(" Creating new Groups object") */
        LOG.creating_new_groups_object().debug();
      }
      GROUPS = new Groups(conf);
    }
    return GROUPS;
  }

  /**
   * Create new groups used to map user-to-groups with loaded configuration.
   * @param conf
   * @return the groups being used to map user-to-groups.
   */
  @Private
  public static synchronized Groups
      getUserToGroupsMappingServiceWithLoadedConfiguration(
          Configuration conf) {

    GROUPS = new Groups(conf);
    return GROUPS;
  }
}
