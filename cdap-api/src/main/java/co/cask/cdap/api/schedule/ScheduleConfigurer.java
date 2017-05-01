/*
 * Copyright © 2017 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.api.schedule;

import java.util.Map;
import java.util.TimeZone;

/**
 * Configurer for scheduling.
 */
public interface ScheduleConfigurer {

  ScheduleConfigurer setDescription(String description);

  ScheduleConfigurer setProperties(Map<String, String> properties);

  ScheduleConfigurer limitConcurrentRuns(int max);

  ScheduleConfigurer delayRun(long delayMillis);

  ScheduleConfigurer setTimeRange(int startHour, int endHour);

  ScheduleConfigurer setTimeRange(int startHour, int endHour, TimeZone timeZone);

  ScheduleConfigurer setDurationSinceLastRun(long delayMillis);

  void triggerByTime(String cronExpression);

  void triggerOnPartitions(String datasetName, int numPartitions);
}