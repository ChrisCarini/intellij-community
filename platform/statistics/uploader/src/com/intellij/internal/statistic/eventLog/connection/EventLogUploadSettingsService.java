// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.internal.statistic.eventLog.connection;

import com.intellij.internal.statistic.config.EventLogExternalSendSettings;
import com.intellij.internal.statistic.config.bean.EventLogSendConfiguration;
import com.intellij.internal.statistic.config.eventLog.EventLogBuildType;
import com.intellij.internal.statistic.eventLog.DataCollectorDebugLogger;
import com.intellij.internal.statistic.eventLog.EventLogApplicationInfo;
import com.intellij.internal.statistic.eventLog.EventLogBuild;
import com.intellij.internal.statistic.eventLog.connection.metadata.EventGroupsFilterRules;
import com.intellij.internal.statistic.eventLog.connection.metadata.EventLogMetadataUtils;
import com.intellij.internal.statistic.eventLog.filters.*;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;

@ApiStatus.Internal
public class EventLogUploadSettingsService extends SettingsConnectionService implements EventLogSettingsService {
  private static final String SEND = "send";
  private static final String METADATA = "metadata";
  private static final String DICTIONARY = "dictionary";

  private final @NotNull EventLogApplicationInfo myApplicationInfo;

  public EventLogUploadSettingsService(@NotNull String recorderId, @NotNull EventLogApplicationInfo appInfo) {
    this(recorderId, appInfo, TimeUnit.MINUTES.toMillis(10));
  }

  public EventLogUploadSettingsService(@NotNull String recorderId,
                                       @NotNull EventLogApplicationInfo appInfo,
                                       long settingsCacheTimeoutMs) {
    super(
      () -> getConfigUrl(recorderId, appInfo.getProductCode(), appInfo.getTemplateUrl(), appInfo.isTestConfig()),
      recorderId, appInfo, settingsCacheTimeoutMs
    );
    myApplicationInfo = appInfo;
  }

  private static @NotNull String getConfigUrl(@NotNull String recorderId, @NotNull String productCode, @NotNull String templateUrl, boolean isTestConfig) {
    if (isTestConfig) {
      return String.format(templateUrl, "test/" + recorderId, productCode);
    }
    return String.format(templateUrl, recorderId, productCode);
  }

  @Override
  public @Nullable String getServiceUrl() {
    return getEndpointValue(SEND);
  }

  @Override
  public @Nullable String getDictionaryServiceUrl() {
    return getEndpointValue(DICTIONARY);
  }

  @Override
  public boolean isSettingsReachable() {
    return getExternalSettings() != null;
  }

  @Override
  public boolean isSendEnabled() {
    final EventLogExternalSendSettings settings = getExternalSettings();
    return settings != null && settings.isSendEnabled();
  }

  @Override
  public @NotNull LogEventFilter getBaseEventFilter() {
    return new LogEventMetadataFilter(notNull(loadApprovedGroupsRules(), EventGroupsFilterRules.empty()));
  }

  @Override
  public @NotNull LogEventFilter getEventFilter(@NotNull LogEventFilter base, @NotNull EventLogBuildType type) {
    final EventLogSendConfiguration configuration = getConfiguration(type);
    if (configuration == null) {
      DataCollectorDebugLogger logger = myApplicationInfo.getLogger();
      if (logger.isTraceEnabled()) {
        logger.trace("Cannot find send configuration for '" + type + "' -> clean up log file");
      }
      return LogEventFalseFilter.INSTANCE;
    }

    return new LogEventCompositeFilter(
      new LogEventBucketsFilter(configuration.getBuckets()),
      base, LogEventSnapshotBuildFilter.INSTANCE
    );
  }

  private static EventGroupsFilterRules<EventLogBuild> notNull(@Nullable EventGroupsFilterRules<EventLogBuild> groupFilterConditions,
                                                               @NotNull EventGroupsFilterRules<EventLogBuild> defaultValue) {
    return groupFilterConditions != null ? groupFilterConditions : defaultValue;
  }

  @Override
  public @NotNull EventLogApplicationInfo getApplicationInfo() {
    return myApplicationInfo;
  }

  protected @Nullable EventGroupsFilterRules<EventLogBuild> loadApprovedGroupsRules() {
    final String productUrl = getMetadataProductUrl();
    if (productUrl == null) return null;
    EventLogConnectionSettings settings = myApplicationInfo.getConnectionSettings();
    return EventLogMetadataUtils.loadAndParseGroupsFilterRules(productUrl, settings);
  }

  public @NonNls @Nullable String getMetadataProductUrl() {
    String baseMetadataUrl = getEndpointValue(METADATA);
    if (baseMetadataUrl == null) return null;
    return baseMetadataUrl + myApplicationInfo.getBaselineVersion() + "/" + myApplicationInfo.getProductCode() + ".json";
  }
}
