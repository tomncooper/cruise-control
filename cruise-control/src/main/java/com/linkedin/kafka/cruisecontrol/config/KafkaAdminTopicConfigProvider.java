/*
 * Copyright 2021 LinkedIn Corp. Licensed under the BSD 2-Clause License (the "License"). See License in the project root for license information.
 */

package com.linkedin.kafka.cruisecontrol.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import com.linkedin.kafka.cruisecontrol.monitor.LoadMonitor;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.common.config.ConfigResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.linkedin.cruisecontrol.common.utils.Utils.validateNotNull;


/**
 * The Kafka topic config provider implementation based on using the Kafka Admin Client for topic level configurations
 * and files for cluster level configurations. The format of the file is JSON, listing properties:
 * <pre>
 *   {
 *     "min.insync.replicas": 1,
 *     "an.example.cluster.config": false
 *   }
 * </pre>
 *
 */
public class KafkaAdminTopicConfigProvider extends JsonFileTopicConfigProvider {

  private static final Logger LOG = LoggerFactory.getLogger(KafkaAdminTopicConfigProvider.class);

  public static final String CLUSTER_CONFIGS_FILE = "cluster.configs.file";
  private Properties _clusterConfigs;
  private AdminClient _adminClient;

  @Override
  public Properties clusterConfigs() {
    return _clusterConfigs;
  }

  @Override
  public Properties topicConfigs(String topic) {
    Config topicConfig = null;
    ConfigResource topicResource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
    try {
      LOG.debug("Requesting details for topic '{}'", topic);
      topicConfig = _adminClient
              .describeConfigs(Collections.singletonList(topicResource))
              .all()
              .get()
              .get(topicResource);
    } catch (InterruptedException | ExecutionException e) {
        LOG.warn("Config check for topic {} failed due to failure to describe its configs.", topic, e);
    }

    if (topicConfig != null) {
      return convertTopicConfigToProperties(topicConfig);
    } else {
      LOG.error("The configuration for topic '{}' could not be retrieved", topic);
      return new Properties();
    }
  }

  @Override
  public Map<String, Properties> allTopicConfigs() {
    Map<ConfigResource, Config> topicConfigs = null;
    try {
      LOG.debug("Requesting configurations for all topics");
      topicConfigs = _adminClient
              .listTopics()
              .names()
              .thenApply(
                      topicNameSet -> _adminClient.describeConfigs(
                              topicNameSet.stream().map(name -> new ConfigResource(ConfigResource.Type.TOPIC, name)).collect(Collectors.toList())
                      ).all()
              )
              .get()
              .get();
    } catch (InterruptedException | ExecutionException e) {
      LOG.warn("Config check for all topics failed due to failure to describe their configs.", e);
    }

    Map<String, Properties> propsMap = new HashMap<>();
    if (topicConfigs != null) {
      LOG.debug("Converting {} Topic Configs into Properties", topicConfigs.size());
      for (Map.Entry<ConfigResource, Config> entry : topicConfigs.entrySet()) {
        propsMap.put(entry.getKey().name(), convertTopicConfigToProperties(entry.getValue()));
      }
      LOG.debug("Topic Config conversion complete");
    } else {
      LOG.error("Topic configurations for all topics on the cluster could not be retrieved");
    }
    return propsMap;
  }

  private static Properties convertTopicConfigToProperties(Config config) {
    Properties props = new Properties();
    for (ConfigEntry entry : config.entries()) {
      props.put(entry.name(), entry.value());
    }
    return props;
  }


  @Override
  public void configure(Map<String, ?> configs) {
    _adminClient = (AdminClient) validateNotNull(
            configs.get(LoadMonitor.KAFKA_ADMIN_CLIENT_OBJECT_CONFIG),
            () -> String.format("Missing %s when creating Kafka Admin Client based Topic Config Provider",
                    LoadMonitor.KAFKA_ADMIN_CLIENT_OBJECT_CONFIG));
    _clusterConfigs = loadClusterConfigs(configs, CLUSTER_CONFIGS_FILE);
  }

  @Override
  public void close() {
    //no-op
  }
}
