/*
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

package tajo.master.event;

/**
 * Event types handled by TaskAttempt.
 */
public enum TaskAttemptEventType {

  //Producer:Task
  TA_SCHEDULE,
  TA_RESCHEDULE,

  //Producer:Client, Task
  TA_KILL,

  //Producer:Scheduler
  TA_ASSIGNED,

  //Producer:Scheduler
  TA_LAUNCHED,

  //Producer:TaskAttemptListener
  TA_DIAGNOSTICS_UPDATE,
  TA_COMMIT_PENDING,
  TA_DONE,
  TA_FATAL_ERROR,
  TA_UPDATE,
  TA_TIMED_OUT,

  //Producer:TaskCleaner
  TA_CLEANUP_DONE,

  //Producer:Job
  TA_TOO_MANY_FETCH_FAILURE,
}
