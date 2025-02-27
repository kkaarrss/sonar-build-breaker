/*
 * SonarQube Build Breaker Plugin
 * Copyright (C) 2009-2016 Matthew DeTullio and contributors
 * mailto:sonarqube@googlegroups.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.plugins.buildbreaker;

import com.google.common.base.Strings;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.Files;
import org.sonar.api.CoreProperties;
import org.sonar.api.batch.AnalysisMode;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.postjob.PostJob;
import org.sonar.api.batch.postjob.PostJobContext;
import org.sonar.api.batch.postjob.PostJobDescriptor;
import org.sonar.api.config.Settings;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.Metric;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonarqube.ws.Ce.TaskStatus;
import org.sonarqube.ws.Ce.TaskResponse;
import org.sonarqube.ws.MediaTypes;
import org.sonarqube.ws.Qualitygates.ProjectStatusResponse;
import org.sonarqube.ws.Qualitygates.ProjectStatusResponse.Comparator;
import org.sonarqube.ws.Qualitygates.ProjectStatusResponse.Condition;
import org.sonarqube.ws.Qualitygates.ProjectStatusResponse.ProjectStatus;
import org.sonarqube.ws.Qualitygates.ProjectStatusResponse.Status;
import org.sonarqube.ws.client.*;
import org.sonarqube.ws.client.qualitygates.ProjectStatusRequest;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Properties;

/**
 * Retrieves the ID of the server-side Compute Engine task, waits for task completion, then checks
 * the project's quality gate. Breaks the build if the quality gate has failed.
 */
public class QualityGateBreaker implements PostJob {
  private static final String CLASSNAME = QualityGateBreaker.class.getSimpleName();
  private static final Logger LOGGER = Loggers.get(QualityGateBreaker.class);

  private final AnalysisMode analysisMode;
  private final FileSystem fileSystem;
  private final Settings settings;

  /**
   * Constructor used to inject dependencies.
   *
   * @param analysisMode the analysis mode
   * @param fileSystem the analysis' file system
   * @param settings the project settings
   */
  public QualityGateBreaker(AnalysisMode analysisMode, FileSystem fileSystem, Settings settings) {
    this.analysisMode = analysisMode;
    this.fileSystem = fileSystem;
    this.settings = settings;
  }

  @VisibleForTesting
  static int logConditions(List<Condition> conditionsList) {
    int errors = 0;

    for (Condition condition : conditionsList) {
      if (Status.WARN.equals(condition.getStatus())) {
        LOGGER.warn(
            "{}: {} {} {}",
            getMetricName(condition.getMetricKey()),
            condition.getActualValue(),
            getComparatorSymbol(condition.getComparator()),
            condition.getWarningThreshold());
      } else if (Status.ERROR.equals(condition.getStatus())) {
        errors++;
        LOGGER.error(
            "{}: {} {} {}",
            getMetricName(condition.getMetricKey()),
            condition.getActualValue(),
            getComparatorSymbol(condition.getComparator()),
            condition.getErrorThreshold());
      }
    }

    return errors;
  }

  private static String getMetricName(String metricKey) {
    try {
      Metric metric = CoreMetrics.getMetric(metricKey);
      return metric.getName();
    } catch (NoSuchElementException e) {
      LOGGER.trace("Using key as name for custom metric '{}' due to '{}'", metricKey, e);
    }
    return metricKey;
  }

  private static String getComparatorSymbol(Comparator comparator) {
    switch (comparator) {
      case GT:
        return ">";
      case LT:
        return "<";
      case EQ:
        return "=";
      case NE:
        return "!=";
      default:
        return comparator.toString();
    }
  }

  public boolean shouldExecuteOnProject() {
    if (!analysisMode.isPublish()) {
      LOGGER.debug(
          "{} is disabled",
          CLASSNAME);
      return false;
    }
    if (settings.getBoolean(BuildBreakerPlugin.SKIP_KEY)) {
      LOGGER.debug("{} is disabled ({} = true)", CLASSNAME, BuildBreakerPlugin.SKIP_KEY);
      return false;
    }
    return true;
  }

  private String getServerUrl(Properties reportTaskProps) {
    String altServerUrl = settings.getString(BuildBreakerPlugin.ALTERNATIVE_SERVER_URL_KEY);
    if (Strings.isNullOrEmpty(altServerUrl)) {
      return reportTaskProps.getProperty("serverUrl");
    } else {
      LOGGER.debug(
          "Using alternative server URL ({}): {}",
          BuildBreakerPlugin.ALTERNATIVE_SERVER_URL_KEY,
          altServerUrl);
      return altServerUrl;
    }
  }

  private Properties loadReportTaskProps() {
    File reportTaskFile = new File(fileSystem.workDir(), "report-task.txt");

    Properties reportTaskProps = new Properties();

    try {
      reportTaskProps.load(Files.newReader(reportTaskFile, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException("Unable to load properties from file " + reportTaskFile, e);
    }

    return reportTaskProps;
  }

  @VisibleForTesting
  TaskResponse getTask(WsResponse wsResponse) throws IOException {
	  return TaskResponse.parseFrom(wsResponse.contentStream());
  }
  
  @VisibleForTesting
  String getAnalysisId(WsClient wsClient, String ceTaskId) {
    WsRequest ceTaskRequest =
        new GetRequest("api/ce/task").setParam("id", ceTaskId).setMediaType(MediaTypes.PROTOBUF);

    int queryMaxAttempts = settings.getInt(BuildBreakerPlugin.QUERY_MAX_ATTEMPTS_KEY);
    int queryInterval = settings.getInt(BuildBreakerPlugin.QUERY_INTERVAL_KEY);

    for (int attempts = 0; attempts < queryMaxAttempts; attempts++) {
      WsResponse wsResponse = wsClient.wsConnector().call(ceTaskRequest);

      try {
        TaskResponse taskResponse = getTask(wsResponse);
        TaskStatus taskStatus = taskResponse.getTask().getStatus();

        switch (taskStatus) {
          case IN_PROGRESS:
          case PENDING:
            // Wait the configured interval then retry
            LOGGER.info("Waiting for report processing to complete...");
            Thread.sleep(queryInterval);
            break;
          case SUCCESS:
            // Exit
            return taskResponse.getTask().getAnalysisId();
          default:
            throw new IllegalStateException(
                "Report processing did not complete successfully: " + taskStatus);
        }
      } catch (IOException | InterruptedException e) {
        throw new IllegalStateException(e.getMessage(), e);
      }
    }

    LOGGER.error(
        "{} API query limit ({}) reached.  Try increasing {}, {}, or both.",
        BuildBreakerPlugin.LOG_STAMP,
        queryMaxAttempts,
        BuildBreakerPlugin.QUERY_MAX_ATTEMPTS_KEY,
        BuildBreakerPlugin.QUERY_INTERVAL_KEY);

    throw new IllegalStateException(
        "Report processing is taking longer than the configured wait limit.");
  }

  @VisibleForTesting
  void checkQualityGate(WsClient wsClient, String analysisId) {
    LOGGER.debug("Requesting quality gate status for analysisId {}", analysisId);
    ProjectStatusResponse projectStatusResponse =
        wsClient.qualitygates().projectStatus(new ProjectStatusRequest().setAnalysisId(analysisId));

    ProjectStatus projectStatus = projectStatusResponse.getProjectStatus();

    Status status = projectStatus.getStatus();
    LOGGER.info("Quality gate status: {}", status);

    int errors = 0;
    if (Status.ERROR.equals(status) || Status.WARN.equals(status)) {
      errors = logConditions(projectStatus.getConditionsList());
    }

    if (Status.ERROR.equals(status)) {
      LOGGER.error("{} Project did not meet {} conditions", BuildBreakerPlugin.LOG_STAMP, errors);
      throw new IllegalStateException("Project does not pass the quality gate.");
    }
  }

  @Override
  public void describe(PostJobDescriptor descriptor) {
    descriptor.name("Quality Gate Breaker");
  }

  @Override
  public void execute(PostJobContext postJobContext) {
    if (shouldExecuteOnProject()) {
      Properties reportTaskProps = loadReportTaskProps();

      HttpConnector httpConnector =
          HttpConnector.newBuilder()
              .url(getServerUrl(reportTaskProps))
              .credentials(
                  settings.getString(CoreProperties.LOGIN),
                  settings.getString(CoreProperties.PASSWORD))
              .build();

      WsClient wsClient = WsClientFactories.getDefault().newClient(httpConnector);

      String analysisId = getAnalysisId(wsClient, reportTaskProps.getProperty("ceTaskId"));

      checkQualityGate(wsClient, analysisId);
    }
  }
}
