/*
 * Copyright © 2015-2017 Cask Data, Inc.
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

package co.cask.cdap.internal.app.services;

import co.cask.cdap.api.ProgramSpecification;
import co.cask.cdap.api.app.ApplicationSpecification;
import co.cask.cdap.api.artifact.ApplicationClass;
import co.cask.cdap.api.artifact.ArtifactId;
import co.cask.cdap.api.artifact.ArtifactRange;
import co.cask.cdap.api.artifact.ArtifactScope;
import co.cask.cdap.api.artifact.ArtifactSummary;
import co.cask.cdap.api.artifact.ArtifactVersion;
import co.cask.cdap.api.artifact.ArtifactVersionRange;
import co.cask.cdap.api.flow.FlowSpecification;
import co.cask.cdap.api.metrics.MetricDeleteQuery;
import co.cask.cdap.api.metrics.MetricStore;
import co.cask.cdap.api.plugin.Plugin;
import co.cask.cdap.api.service.ServiceSpecification;
import co.cask.cdap.api.workflow.WorkflowSpecification;
import co.cask.cdap.app.deploy.Manager;
import co.cask.cdap.app.deploy.ManagerFactory;
import co.cask.cdap.app.store.Store;
import co.cask.cdap.common.ApplicationNotFoundException;
import co.cask.cdap.common.ArtifactAlreadyExistsException;
import co.cask.cdap.common.ArtifactNotFoundException;
import co.cask.cdap.common.CannotBeDeletedException;
import co.cask.cdap.common.InvalidArtifactException;
import co.cask.cdap.common.NotFoundException;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.id.Id;
import co.cask.cdap.config.PreferencesService;
import co.cask.cdap.data2.metadata.store.MetadataStore;
import co.cask.cdap.data2.registry.UsageRegistry;
import co.cask.cdap.data2.transaction.queue.QueueAdmin;
import co.cask.cdap.data2.transaction.stream.StreamConsumerFactory;
import co.cask.cdap.internal.app.deploy.ProgramTerminator;
import co.cask.cdap.internal.app.deploy.pipeline.AppDeploymentInfo;
import co.cask.cdap.internal.app.deploy.pipeline.ApplicationWithPrograms;
import co.cask.cdap.internal.app.runtime.artifact.ArtifactDetail;
import co.cask.cdap.internal.app.runtime.artifact.ArtifactRepository;
import co.cask.cdap.internal.app.runtime.artifact.Artifacts;
import co.cask.cdap.internal.app.runtime.flow.FlowUtils;
import co.cask.cdap.internal.app.store.RunRecordMeta;
import co.cask.cdap.internal.profile.AdminEventPublisher;
import co.cask.cdap.messaging.MessagingService;
import co.cask.cdap.messaging.context.MultiThreadMessagingContext;
import co.cask.cdap.proto.ApplicationDetail;
import co.cask.cdap.proto.ApplicationRecord;
import co.cask.cdap.proto.DatasetDetail;
import co.cask.cdap.proto.PluginInstanceDetail;
import co.cask.cdap.proto.ProgramRecord;
import co.cask.cdap.proto.ProgramType;
import co.cask.cdap.proto.ProgramTypes;
import co.cask.cdap.proto.StreamDetail;
import co.cask.cdap.proto.artifact.AppRequest;
import co.cask.cdap.proto.artifact.ArtifactSortOrder;
import co.cask.cdap.proto.id.ApplicationId;
import co.cask.cdap.proto.id.EntityId;
import co.cask.cdap.proto.id.KerberosPrincipalId;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.proto.id.ProgramId;
import co.cask.cdap.proto.id.ProgramRunId;
import co.cask.cdap.proto.security.Action;
import co.cask.cdap.proto.security.Principal;
import co.cask.cdap.route.store.RouteStore;
import co.cask.cdap.scheduler.Scheduler;
import co.cask.cdap.security.authorization.AuthorizationUtil;
import co.cask.cdap.security.impersonation.Impersonator;
import co.cask.cdap.security.impersonation.OwnerAdmin;
import co.cask.cdap.security.impersonation.SecurityUtil;
import co.cask.cdap.security.spi.authentication.AuthenticationContext;
import co.cask.cdap.security.spi.authorization.AuthorizationEnforcer;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Predicates;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.gson.Gson;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;

/**
 * Service that manage lifecycle of Applications.
 */
public class ApplicationLifecycleService extends AbstractIdleService {
  private static final Logger LOG = LoggerFactory.getLogger(ApplicationLifecycleService.class);

  /**
   * Store manages non-runtime lifecycle.
   */
  private final Store store;
  private final Scheduler scheduler;
  private final QueueAdmin queueAdmin;
  private final StreamConsumerFactory streamConsumerFactory;
  private final UsageRegistry usageRegistry;
  private final PreferencesService preferencesService;
  private final MetricStore metricStore;
  private final OwnerAdmin ownerAdmin;
  private final ArtifactRepository artifactRepository;
  private final ManagerFactory<AppDeploymentInfo, ApplicationWithPrograms> managerFactory;
  private final MetadataStore metadataStore;
  private final AuthorizationEnforcer authorizationEnforcer;
  private final AuthenticationContext authenticationContext;
  private final Impersonator impersonator;
  private final RouteStore routeStore;
  private final boolean appUpdateSchedules;
  private final AdminEventPublisher adminEventPublisher;

  @Inject
  ApplicationLifecycleService(CConfiguration cConfiguration,
                              Store store,
                              Scheduler scheduler, QueueAdmin queueAdmin,
                              StreamConsumerFactory streamConsumerFactory, UsageRegistry usageRegistry,
                              PreferencesService preferencesService, MetricStore metricStore, OwnerAdmin ownerAdmin,
                              ArtifactRepository artifactRepository,
                              ManagerFactory<AppDeploymentInfo, ApplicationWithPrograms> managerFactory,
                              MetadataStore metadataStore,
                              AuthorizationEnforcer authorizationEnforcer, AuthenticationContext authenticationContext,
                              Impersonator impersonator, RouteStore routeStore,
                              MessagingService messagingService) {
    this.appUpdateSchedules = cConfiguration.getBoolean(Constants.AppFabric.APP_UPDATE_SCHEDULES,
                                                        Constants.AppFabric.DEFAULT_APP_UPDATE_SCHEDULES);
    this.store = store;
    this.scheduler = scheduler;
    this.queueAdmin = queueAdmin;
    this.streamConsumerFactory = streamConsumerFactory;
    this.usageRegistry = usageRegistry;
    this.preferencesService = preferencesService;
    this.metricStore = metricStore;
    this.artifactRepository = artifactRepository;
    this.managerFactory = managerFactory;
    this.metadataStore = metadataStore;
    this.ownerAdmin = ownerAdmin;
    this.authorizationEnforcer = authorizationEnforcer;
    this.authenticationContext = authenticationContext;
    this.impersonator = impersonator;
    this.routeStore = routeStore;
    this.adminEventPublisher = new AdminEventPublisher(cConfiguration,
                                                       new MultiThreadMessagingContext(messagingService));
  }

  @Override
  protected void startUp() throws Exception {
    LOG.info("Starting ApplicationLifecycleService");
  }

  @Override
  protected void shutDown() throws Exception {
    LOG.info("Shutting down ApplicationLifecycleService");
  }

  /**
   * Get all applications in the specified namespace, filtered to only include applications with an artifact name
   * in the set of specified names and an artifact version equal to the specified version. If the specified set
   * is empty, no filtering is performed on artifact name. If the specified version is null, no filtering is done
   * on artifact version.
   *
   * @param namespace the namespace to get apps from
   * @param artifactNames the set of valid artifact names. If empty, all artifact names are valid
   * @param artifactVersion the artifact version to match. If null, all artifact versions are valid
   * @return list of all applications in the namespace that match the specified artifact names and version
   */
  public List<ApplicationRecord> getApps(NamespaceId namespace,
                                         Set<String> artifactNames,
                                         @Nullable String artifactVersion) throws Exception {
    return getApps(namespace, getAppPredicate(artifactNames, artifactVersion));
  }

  /**
   * Get all applications in the specified namespace that satisfy the specified predicate.
   *
   * @param namespace the namespace to get apps from
   * @param predicate the predicate that must be satisfied in order to be returned
   * @return list of all applications in the namespace that satisfy the specified predicate
   */
  public List<ApplicationRecord> getApps(final NamespaceId namespace,
                                         com.google.common.base.Predicate<ApplicationRecord> predicate)
    throws Exception {
    List<ApplicationRecord> appRecords = new ArrayList<>();
    Map<ApplicationId, ApplicationSpecification> appSpecs = new HashMap<>();
    for (ApplicationSpecification appSpec : store.getAllApplications(namespace)) {
      appSpecs.put(namespace.app(appSpec.getName(), appSpec.getAppVersion()), appSpec);
    }
    appSpecs.keySet().retainAll(authorizationEnforcer.isVisible(appSpecs.keySet(),
                                                                authenticationContext.getPrincipal()));

    for (ApplicationId appId : appSpecs.keySet()) {
      ApplicationSpecification appSpec = appSpecs.get(appId);
      if (appSpec == null) {
        continue;
      }

      // possible if this particular app was deploy prior to v3.2 and upgrade failed for some reason.
      ArtifactId artifactId = appSpec.getArtifactId();
      ArtifactSummary artifactSummary = artifactId == null ?
        new ArtifactSummary(appSpec.getName(), null) : ArtifactSummary.from(artifactId);
      ApplicationRecord record = new ApplicationRecord(artifactSummary, appId, appSpec.getDescription(),
                                                       ownerAdmin.getOwnerPrincipal(appId));
      if (predicate.apply(record)) {
        appRecords.add(record);
      }
    }
    return appRecords;
  }

  /**
   * Get detail about the specified application
   *
   * @param appId the id of the application to get
   * @return detail about the specified application
   * @throws ApplicationNotFoundException if the specified application does not exist
   */
  public ApplicationDetail getAppDetail(ApplicationId appId) throws Exception {
    // TODO: CDAP-12473: filter based on the entity visibility in the app detail
    // user needs to pass the visibility check to get the app detail
    AuthorizationUtil.ensureAccess(appId, authorizationEnforcer, authenticationContext.getPrincipal());
    ApplicationSpecification appSpec = store.getApplication(appId);
    if (appSpec == null) {
      throw new ApplicationNotFoundException(appId);
    }
    String ownerPrincipal = ownerAdmin.getOwnerPrincipal(appId);
    return filterApplicationDetail(appId, ApplicationDetail.fromSpec(appSpec, ownerPrincipal));
  }

  public Collection<String> getAppVersions(String namespace, String application) throws Exception {
    Collection<ApplicationId> appIds = store.getAllAppVersionsAppIds(new ApplicationId(namespace, application));
    List<String> versions = new ArrayList<>();
    for (ApplicationId appId : appIds) {
      versions.add(appId.getVersion());
    }
    return versions;
  }

  /**
   * To determine whether the app version is allowed to be deployed
   *
   * @param appId the id of the application to be determined
   * @return whether the app version is allowed to be deployed
   */
  public boolean updateAppAllowed(ApplicationId appId) throws Exception {
    AuthorizationUtil.ensureAccess(appId, authorizationEnforcer, authenticationContext.getPrincipal());
    ApplicationSpecification appSpec = store.getApplication(appId);
    if (appSpec == null) {
      // App does not exist. Allow to create a new one
      return true;
    }
    String version = appId.getVersion();
    return version.endsWith(ApplicationId.DEFAULT_VERSION);
  }

  /**
   * Update an existing application. An application's configuration and artifact version can be updated.
   *
   * @param appId the id of the application to update
   * @param appRequest the request to update the application, including new config and artifact
   * @param programTerminator a program terminator that will stop programs that are removed when updating an app.
   *                          For example, if an update removes a flow, the terminator defines how to stop that flow.
   * @return information about the deployed application
   * @throws ApplicationNotFoundException if the specified application does not exist
   * @throws ArtifactNotFoundException if the requested artifact does not exist
   * @throws InvalidArtifactException if the specified artifact is invalid. For example, if the artifact name changed,
   *                                  if the version is an invalid version, or the artifact contains no app classes
   * @throws Exception if there was an exception during the deployment pipeline. This exception will often wrap
   *                   the actual exception
   */
  public ApplicationWithPrograms updateApp(ApplicationId appId, AppRequest appRequest,
                                           ProgramTerminator programTerminator) throws Exception {
    // Check if the current user has admin privileges on it before updating.
    authorizationEnforcer.enforce(appId, authenticationContext.getPrincipal(), Action.ADMIN);

    // check that app exists
    ApplicationSpecification currentSpec = store.getApplication(appId);
    if (currentSpec == null) {
      throw new ApplicationNotFoundException(appId);
    }

    ArtifactId currentArtifact = currentSpec.getArtifactId();

    // if no artifact is given, use the current one.
    ArtifactId newArtifactId = currentArtifact;
    // otherwise, check requested artifact is valid and use it
    ArtifactSummary requestedArtifact = appRequest.getArtifact();
    if (requestedArtifact != null) {
      // cannot change artifact name, only artifact version.
      if (!currentArtifact.getName().equals(requestedArtifact.getName())) {
        throw new InvalidArtifactException(String.format(
          " Only artifact version updates are allowed. Cannot change from artifact '%s' to '%s'.",
          currentArtifact.getName(), requestedArtifact.getName()));
      }

      if (!currentArtifact.getScope().equals(requestedArtifact.getScope())) {
        throw new InvalidArtifactException("Only artifact version updates are allowed. " +
          "Cannot change from a non-system artifact to a system artifact or vice versa.");
      }

      // check requested artifact version is valid
      ArtifactVersion requestedVersion = new ArtifactVersion(requestedArtifact.getVersion());
      if (requestedVersion.getVersion() == null) {
        throw new InvalidArtifactException(String.format(
          "Requested artifact version '%s' is invalid", requestedArtifact.getVersion()));
      }
      newArtifactId = new ArtifactId(currentArtifact.getName(), requestedVersion, currentArtifact.getScope());
    }

    // ownerAdmin.getImpersonationPrincipal will give the owner which will be impersonated for the application
    // irrespective of the version
    SecurityUtil.verifyOwnerPrincipal(appId, appRequest.getOwnerPrincipal(), ownerAdmin);

    Object requestedConfigObj = appRequest.getConfig();
    // if config is null, use the previous config. Shouldn't use a static GSON since the request Config object can
    // be a user class, otherwise there will be ClassLoader leakage.
    String requestedConfigStr = requestedConfigObj == null ?
      currentSpec.getConfiguration() : new Gson().toJson(requestedConfigObj);

    Id.Artifact artifactId = Id.Artifact.fromEntityId(Artifacts.toArtifactId(appId.getParent(), newArtifactId));
    return deployApp(appId.getParent(), appId.getApplication(), null, artifactId, requestedConfigStr,
                     programTerminator, ownerAdmin.getOwner(appId), appRequest.canUpdateSchedules());
  }

  /**
   * Deploy an application by first adding the application jar to the artifact repository, then creating an application
   * using that newly added artifact.
   *
   * @param namespace the namespace to deploy the application and artifact in
   * @param appName the name of the app. If null, the name will be set based on the application spec
   * @param artifactId the id of the artifact to add and create the application from
   * @param jarFile the application jar to add as an artifact and create the application from
   * @param configStr the configuration to send to the application when generating the application specification
   * @param programTerminator a program terminator that will stop programs that are removed when updating an app.
   *                          For example, if an update removes a flow, the terminator defines how to stop that flow.
   * @return information about the deployed application
   * @throws InvalidArtifactException the the artifact is invalid. For example, if it does not contain any app classes
   * @throws ArtifactAlreadyExistsException if the specified artifact already exists
   * @throws IOException if there was an IO error writing the artifact
   */
  public ApplicationWithPrograms deployAppAndArtifact(NamespaceId namespace, @Nullable String appName,
                                                      Id.Artifact artifactId, File jarFile,
                                                      @Nullable String configStr,
                                                      @Nullable KerberosPrincipalId ownerPrincipal,
                                                      ProgramTerminator programTerminator,
                                                      boolean updateSchedules) throws Exception {

    ArtifactDetail artifactDetail = artifactRepository.addArtifact(artifactId, jarFile);
    try {
      return deployApp(namespace, appName, null, configStr, programTerminator, artifactDetail, ownerPrincipal,
                       updateSchedules);
    } catch (Exception e) {
      // if we added the artifact, but failed to deploy the application, delete the artifact to bring us back
      // to the state we were in before this call.
      try {
        artifactRepository.deleteArtifact(artifactId);
      } catch (IOException e2) {
        // if the delete fails, nothing we can do, just log it and continue on
        LOG.warn("Failed to delete artifact {} after deployment of artifact and application failed.", artifactId, e2);
        e.addSuppressed(e2);
      }
      throw e;
    }
  }

  /**
   * Deploy an application using the specified artifact and configuration. When an app is deployed, the Application
   * class is instantiated and configure() is called in order to generate an {@link ApplicationSpecification}.
   * Programs, datasets, and streams are created based on the specification before the spec is persisted in the
   * {@link Store}. This method can create a new application as well as update an existing one.
   *
   * @param namespace the namespace to deploy the app to
   * @param appName the name of the app. If null, the name will be set based on the application spec
   * @param artifactId the id of the artifact to create the application from
   * @param configStr the configuration to send to the application when generating the application specification
   * @param programTerminator a program terminator that will stop programs that are removed when updating an app.
   *                          For example, if an update removes a flow, the terminator defines how to stop that flow.
   * @return information about the deployed application
   * @throws InvalidArtifactException if the artifact does not contain any application classes
   * @throws ArtifactNotFoundException if the specified artifact does not exist
   * @throws IOException if there was an IO error reading artifact detail from the meta store
   * @throws Exception if there was an exception during the deployment pipeline. This exception will often wrap
   *                   the actual exception
   */
  public ApplicationWithPrograms deployApp(NamespaceId namespace, @Nullable String appName, @Nullable String appVersion,
                                           Id.Artifact artifactId,
                                           @Nullable String configStr,
                                           ProgramTerminator programTerminator) throws Exception {
    return deployApp(namespace, appName, appVersion, artifactId, configStr, programTerminator, null, true);
  }

  /**
   * Deploy an application using the specified artifact and configuration. When an app is deployed, the Application
   * class is instantiated and configure() is called in order to generate an {@link ApplicationSpecification}.
   * Programs, datasets, and streams are created based on the specification before the spec is persisted in the
   * {@link Store}. This method can create a new application as well as update an existing one.
   *
   * @param namespace the namespace to deploy the app to
   * @param appName the name of the app. If null, the name will be set based on the application spec
   * @param artifactId the id of the artifact to create the application from
   * @param configStr the configuration to send to the application when generating the application specification
   * @param programTerminator a program terminator that will stop programs that are removed when updating an app.
   *                          For example, if an update removes a flow, the terminator defines how to stop that flow.
   * @param ownerPrincipal the kerberos principal of the application owner
   * @param updateSchedules specifies if schedules of the workflow have to be updated,
   *                        if null value specified by the property "app.deploy.update.schedules" will be used.
   * @return information about the deployed application
   * @throws InvalidArtifactException if the artifact does not contain any application classes
   * @throws ArtifactNotFoundException if the specified artifact does not exist
   * @throws IOException if there was an IO error reading artifact detail from the meta store
   * @throws Exception if there was an exception during the deployment pipeline. This exception will often wrap
   *                   the actual exception
   */
  public ApplicationWithPrograms deployApp(NamespaceId namespace, @Nullable String appName, @Nullable String appVersion,
                                           Id.Artifact artifactId,
                                           @Nullable String configStr,
                                           ProgramTerminator programTerminator,
                                           @Nullable KerberosPrincipalId ownerPrincipal,
                                           @Nullable Boolean updateSchedules) throws Exception {
    ArtifactDetail artifactDetail = artifactRepository.getArtifact(artifactId);
    return deployApp(namespace, appName, appVersion, configStr, programTerminator, artifactDetail, ownerPrincipal,
                     updateSchedules == null ? appUpdateSchedules : updateSchedules);
  }

  /**
   * Deploy an application using the specified artifact and configuration. When an app is deployed, the Application
   * class is instantiated and configure() is called in order to generate an {@link ApplicationSpecification}.
   * Programs, datasets, and streams are created based on the specification before the spec is persisted in the
   * {@link Store}. This method can create a new application as well as update an existing one.
   *
   * @param namespace the namespace to deploy the app to
   * @param appName the name of the app. If null, the name will be set based on the application spec
   * @param summary the artifact summary of the app
   * @param configStr the configuration to send to the application when generating the application specification
   * @param programTerminator a program terminator that will stop programs that are removed when updating an app.
   *                          For example, if an update removes a flow, the terminator defines how to stop that flow.
   * @param ownerPrincipal the kerberos principal of the application owner
   * @param updateSchedules specifies if schedules of the workflow have to be updated,
   *                        if null value specified by the property "app.deploy.update.schedules" will be used.
   * @return information about the deployed application
   * @throws InvalidArtifactException if the artifact does not contain any application classes
   * @throws IOException if there was an IO error reading artifact detail from the meta store
   * @throws ArtifactNotFoundException if the specified artifact does not exist
   * @throws Exception if there was an exception during the deployment pipeline. This exception will often wrap
   *                   the actual exception
   */
  public ApplicationWithPrograms deployApp(NamespaceId namespace, @Nullable String appName, @Nullable String appVersion,
                                           ArtifactSummary summary,
                                           @Nullable String configStr,
                                           ProgramTerminator programTerminator,
                                           @Nullable KerberosPrincipalId ownerPrincipal,
                                           @Nullable Boolean updateSchedules) throws Exception {
    NamespaceId artifactNamespace =
      ArtifactScope.SYSTEM.equals(summary.getScope()) ? NamespaceId.SYSTEM : namespace;
    ArtifactRange range = new ArtifactRange(artifactNamespace.getNamespace(), summary.getName(),
                                            ArtifactVersionRange.parse(summary.getVersion()));
    // this method will not throw ArtifactNotFoundException, if no artifacts in the range, we are expecting an empty
    // collection returned.
    List<ArtifactDetail> artifactDetail = artifactRepository.getArtifactDetails(range, 1, ArtifactSortOrder.DESC);
    if (artifactDetail.isEmpty()) {
      throw new ArtifactNotFoundException(range.getNamespace(), range.getName());
    }
    return deployApp(namespace, appName, appVersion, configStr, programTerminator, artifactDetail.iterator().next(),
                     ownerPrincipal, updateSchedules == null ? appUpdateSchedules : updateSchedules);
  }

  /**
   * Remove all the applications inside the given {@link Id.Namespace}
   *
   * @param namespaceId the {@link NamespaceId} under which all application should be deleted
   * @throws Exception
   */
  public void removeAll(NamespaceId namespaceId) throws Exception {
    Map<ProgramRunId, RunRecordMeta> runningPrograms = store.getActiveRuns(namespaceId);
    List<ApplicationSpecification> allSpecs = new ArrayList<>(store.getAllApplications(namespaceId));
    Map<ApplicationId, ApplicationSpecification> apps = new HashMap<>();
    for (ApplicationSpecification appSpec : allSpecs) {
      ApplicationId applicationId = namespaceId.app(appSpec.getName(), appSpec.getAppVersion());
      authorizationEnforcer.enforce(applicationId, authenticationContext.getPrincipal(), Action.ADMIN);
      apps.put(applicationId, appSpec);
    }

    if (!runningPrograms.isEmpty()) {
      Set<String> activePrograms = new HashSet<>();
      for (Map.Entry<ProgramRunId, RunRecordMeta> runningProgram : runningPrograms.entrySet()) {
        activePrograms.add(runningProgram.getKey().getApplication() +
                            ": " + runningProgram.getKey().getProgram());
      }

      String appAllRunningPrograms = Joiner.on(',')
        .join(activePrograms);
      throw new CannotBeDeletedException(namespaceId,
                                         "The following programs are still running: " + appAllRunningPrograms);
    }

    // All Apps are STOPPED, delete them
    for (ApplicationId appId : apps.keySet()) {
      removeAppInternal(appId, apps.get(appId));
    }
  }

  /**
   * Delete an application specified by appId.
   *
   * @param appId the {@link ApplicationId} of the application to be removed
   * @throws Exception
   */
  public void removeApplication(ApplicationId appId) throws Exception {
    // enforce ADMIN privileges on the app
    authorizationEnforcer.enforce(appId, authenticationContext.getPrincipal(), Action.ADMIN);
    ensureNoRunningPrograms(appId);
    ApplicationSpecification spec = store.getApplication(appId);
    if (spec == null) {
      throw new NotFoundException(Id.Application.fromEntityId(appId));
    }

    removeAppInternal(appId, spec);
  }

  /**
   * Remove application by the appId and appSpec, note that this method does not have any auth check
   *
   * @param applicationId the {@link ApplicationId} of the application to be removed
   * @param appSpec the {@link ApplicationSpecification} of the application to be removed
   */
  private void removeAppInternal(ApplicationId applicationId, ApplicationSpecification appSpec) throws Exception {
    // if the application has only one version, do full deletion, else only delete the specified version
    if (store.getAllAppVersions(applicationId).size() == 1) {
      deleteApp(applicationId, appSpec);
      return;
    }
    deleteAppVersion(applicationId, appSpec);
  }

  /**
   * Find if the given application has running programs
   *
   * @param appId the id of the application to find running programs for
   * @throws CannotBeDeletedException : the application cannot be deleted because of running programs
   */
  private void ensureNoRunningPrograms(ApplicationId appId) throws CannotBeDeletedException {
    //Check if all are stopped.
    Map<ProgramRunId, RunRecordMeta> runningPrograms = store.getActiveRuns(appId);

    if (!runningPrograms.isEmpty()) {
      Set<String> activePrograms = new HashSet<>();
      for (Map.Entry<ProgramRunId, RunRecordMeta> runningProgram : runningPrograms.entrySet()) {
        activePrograms.add(runningProgram.getKey().getProgram());
      }

      String appAllRunningPrograms = Joiner.on(',')
        .join(activePrograms);
      throw new CannotBeDeletedException(appId,
                                         "The following programs are still running: " + appAllRunningPrograms);
    }
  }

  /**
   * Get detail about the plugin in the specified application
   *
   * @param appId the id of the application
   * @return list of plugins in the application
   * @throws ApplicationNotFoundException if the specified application does not exist
   */
  public List<PluginInstanceDetail> getPlugins(ApplicationId appId)
    throws ApplicationNotFoundException {
    ApplicationSpecification appSpec = store.getApplication(appId);
    if (appSpec == null) {
      throw new ApplicationNotFoundException(appId);
    }
    List<PluginInstanceDetail> pluginInstanceDetails = new ArrayList<>();
    for (Map.Entry<String, Plugin> entry : appSpec.getPlugins().entrySet()) {
      pluginInstanceDetails.add(new PluginInstanceDetail(entry.getKey(), entry.getValue()));
    }
    return pluginInstanceDetails;
  }

  private Iterable<ProgramSpecification> getProgramSpecs(ApplicationId appId) {
    ApplicationSpecification appSpec = store.getApplication(appId);
    return Iterables.concat(appSpec.getFlows().values(),
                            appSpec.getMapReduce().values(),
                            appSpec.getServices().values(),
                            appSpec.getSpark().values(),
                            appSpec.getWorkers().values(),
                            appSpec.getWorkflows().values());
  }

  /**
   * Delete the metrics for an application.
   *
   * @param applicationId the application to delete metrics for.
   */
  private void deleteMetrics(ApplicationId applicationId) {
    ApplicationSpecification spec = this.store.getApplication(applicationId);
    long endTs = System.currentTimeMillis() / 1000;
    Map<String, String> tags = new LinkedHashMap<>();
    tags.put(Constants.Metrics.Tag.NAMESPACE, applicationId.getNamespace());
    // add or replace application name in the tagMap
    tags.put(Constants.Metrics.Tag.APP, spec.getName());
    MetricDeleteQuery deleteQuery = new MetricDeleteQuery(0, endTs, Collections.emptySet(), tags,
                                                          new ArrayList<>(tags.keySet()));
    metricStore.delete(deleteQuery);
  }

  /**
   * Delete stored Preferences of the application and all its programs.
   *
   * @param appId applicationId
   */
  private void deletePreferences(ApplicationId appId) {
    Iterable<ProgramSpecification> programSpecs = getProgramSpecs(appId);
    for (ProgramSpecification spec : programSpecs) {

      preferencesService.deleteProperties(appId.program(ProgramTypes.fromSpecification(spec), spec.getName()));
      LOG.trace("Deleted Preferences of Program : {}, {}, {}, {}", appId.getNamespace(), appId.getApplication(),
                ProgramTypes.fromSpecification(spec).getCategoryName(), spec.getName());
    }
    preferencesService.deleteProperties(appId);
    LOG.trace("Deleted Preferences of Application : {}, {}", appId.getNamespace(), appId.getApplication());
  }

  private ApplicationWithPrograms deployApp(NamespaceId namespaceId, @Nullable String appName,
                                            @Nullable String appVersion,
                                            @Nullable String configStr,
                                            ProgramTerminator programTerminator,
                                            ArtifactDetail artifactDetail,
                                            @Nullable KerberosPrincipalId ownerPrincipal,
                                            boolean updateSchedules) throws Exception {
    // Now to deploy an app, we need ADMIN privilege on the owner principal if it is present, and also ADMIN on the app
    // But since at this point, app name is unknown to us, so the enforcement on the app is happening in the deploy
    // pipeline - LocalArtifactLoaderStage

    // need to enforce on the principal id if impersonation is involved
    KerberosPrincipalId effectiveOwner =
      SecurityUtil.getEffectiveOwner(ownerAdmin, namespaceId,
                                     ownerPrincipal == null ? null : ownerPrincipal.getPrincipal());


    Principal requestingUser = authenticationContext.getPrincipal();
    // enforce that the current principal, if not the same as the owner principal, has the admin privilege on the
    // impersonated principal
    if (effectiveOwner != null) {
      authorizationEnforcer.enforce(effectiveOwner, requestingUser, Action.ADMIN);
    }

    ApplicationClass appClass = Iterables.getFirst(artifactDetail.getMeta().getClasses().getApps(), null);
    if (appClass == null) {
      throw new InvalidArtifactException(String.format("No application class found in artifact '%s' in namespace '%s'.",
                                                       artifactDetail.getDescriptor().getArtifactId(), namespaceId));
    }

    // deploy application with newly added artifact
    AppDeploymentInfo deploymentInfo = new AppDeploymentInfo(artifactDetail.getDescriptor(), namespaceId,
                                                             appClass.getClassName(), appName, appVersion,
                                                             configStr, ownerPrincipal, updateSchedules);

    Manager<AppDeploymentInfo, ApplicationWithPrograms> manager = managerFactory.create(programTerminator);
    // TODO: (CDAP-3258) Manager needs MUCH better error handling.
    ApplicationWithPrograms applicationWithPrograms;
    try {
      applicationWithPrograms = manager.deploy(deploymentInfo).get();
    } catch (ExecutionException e) {
      Throwables.propagateIfPossible(e.getCause(), Exception.class);
      throw Throwables.propagate(e.getCause());
    }

    adminEventPublisher.publishAppCreation(applicationWithPrograms.getApplicationId(),
                                           applicationWithPrograms.getSpecification());
    return applicationWithPrograms;
  }

  // deletes without performs checks that no programs are running

  /**
   * Delete the specified application without performing checks that its programs are stopped.
   *
   * @param appId the id of the application to delete
   * @param spec the spec of the application to delete
   * @throws Exception
   */
  private void deleteApp(ApplicationId appId, ApplicationSpecification spec) throws Exception {
    //Delete the schedules
    scheduler.deleteSchedules(appId);
    for (WorkflowSpecification workflowSpec : spec.getWorkflows().values()) {
      scheduler.modifySchedulesTriggeredByDeletedProgram(appId.workflow(workflowSpec.getName()));
    }

    deleteMetrics(appId);

    //Delete all preferences of the application and of all its programs
    deletePreferences(appId);

    // Delete all streams and queues state of each flow
    for (final FlowSpecification flowSpecification : spec.getFlows().values()) {
      FlowUtils.clearDeletedFlow(impersonator, queueAdmin, streamConsumerFactory,
                                 appId.flow(flowSpecification.getName()), flowSpecification);
    }

    ApplicationSpecification appSpec = store.getApplication(appId);
    deleteAppMetadata(appId, appSpec);
    deleteRouteConfig(appId, appSpec);
    store.deleteWorkflowStats(appId);
    store.removeApplication(appId);
    try {
      // delete the owner as it has already been determined that this is the only version of the app
      ownerAdmin.delete(appId);
    } catch (Exception e) {
      LOG.warn("Failed to delete app owner principal for application {} if one existed while deleting the " +
                 "application.", appId);
    }

    try {
      usageRegistry.unregister(appId);
    } catch (Exception e) {
      LOG.warn("Failed to unregister usage of app: {}", appId, e);
    }

    // make sure the program profile metadata is removed
    adminEventPublisher.publishAppDeletion(appId, appSpec);
  }

  /**
   * Delete the specified application version without performing checks that its programs are stopped.
   *
   * @param appId the id of the application to delete
   * @param spec the spec of the application to delete
   */
  private void deleteAppVersion(ApplicationId appId, ApplicationSpecification spec) {
    //Delete the schedules
    scheduler.deleteSchedules(appId);
    for (WorkflowSpecification workflowSpec : spec.getWorkflows().values()) {
      scheduler.modifySchedulesTriggeredByDeletedProgram(appId.workflow(workflowSpec.getName()));
    }
    store.removeApplication(appId);
  }

  // Delete route configs for all services, if they are present, in that Application
  private void deleteRouteConfig(ApplicationId appId, ApplicationSpecification appSpec) {
    for (ServiceSpecification serviceSpec : appSpec.getServices().values()) {
      ProgramId serviceId = appId.service(serviceSpec.getName());
      try {
        routeStore.delete(serviceId);
      } catch (NotFoundException ex) {
        // expected if a config has not been stored for that service.
      }
    }
  }

  /**
   * Delete the metadata for the application and the programs.
   */
  private void deleteAppMetadata(ApplicationId appId, ApplicationSpecification appSpec) {
    // Remove metadata for the Application itself.
    metadataStore.removeMetadata(appId.toMetadataEntity());

    // Remove metadata for the programs of the Application
    // TODO: Need to remove this we support prefix search of metadata type.
    // See https://issues.cask.co/browse/CDAP-3669
    for (ProgramId programId : getAllPrograms(appId, appSpec)) {
      metadataStore.removeMetadata(programId.toMetadataEntity());
    }
  }

  private Set<ProgramId> getProgramsWithType(ApplicationId appId, ProgramType type,
                                             Map<String, ? extends ProgramSpecification> programSpecs) {
    Set<ProgramId> result = new HashSet<>();

    for (String programName : programSpecs.keySet()) {
      result.add(appId.program(type, programName));
    }
    return result;
  }

  private Set<ProgramId> getAllPrograms(ApplicationId appId, ApplicationSpecification appSpec) {
    Set<ProgramId> result = new HashSet<>();
    result.addAll(getProgramsWithType(appId, ProgramType.FLOW, appSpec.getFlows()));
    result.addAll(getProgramsWithType(appId, ProgramType.MAPREDUCE, appSpec.getMapReduce()));
    result.addAll(getProgramsWithType(appId, ProgramType.WORKFLOW, appSpec.getWorkflows()));
    result.addAll(getProgramsWithType(appId, ProgramType.SERVICE, appSpec.getServices()));
    result.addAll(getProgramsWithType(appId, ProgramType.SPARK, appSpec.getSpark()));
    result.addAll(getProgramsWithType(appId, ProgramType.WORKER, appSpec.getWorkers()));
    return result;
  }

  /**
   * Filter the {@link ApplicationDetail} by only returning the visible entities
   */
  private ApplicationDetail filterApplicationDetail(ApplicationId appId,
                                                    ApplicationDetail applicationDetail) throws Exception {
    Principal principal = authenticationContext.getPrincipal();
    List<ProgramRecord> filteredPrograms =
      AuthorizationUtil.isVisible(applicationDetail.getPrograms(), authorizationEnforcer, principal,
                                  new Function<ProgramRecord, EntityId>() {
                                    @Override
                                    public EntityId apply(ProgramRecord input) {
                                      return appId.program(input.getType(), input.getName());
                                    }
                                  }, null);
    List<StreamDetail> filteredStreams =
      AuthorizationUtil.isVisible(applicationDetail.getStreams(), authorizationEnforcer, principal,
                                  new Function<StreamDetail, EntityId>() {
                                    @Override
                                    public EntityId apply(StreamDetail input) {
                                      return appId.getNamespaceId().stream(input.getName());
                                    }
                                  }, null);
    List<DatasetDetail> filteredDatasets =
      AuthorizationUtil.isVisible(applicationDetail.getDatasets(), authorizationEnforcer, principal,
                                  new Function<DatasetDetail, EntityId>() {
                                    @Override
                                    public EntityId apply(DatasetDetail input) {
                                      return appId.getNamespaceId().dataset(input.getName());
                                    }
                                  }, null);
    return new ApplicationDetail(applicationDetail.getName(), applicationDetail.getAppVersion(),
                                 applicationDetail.getDescription(), applicationDetail.getConfiguration(),
                                 filteredStreams, filteredDatasets, filteredPrograms, applicationDetail.getPlugins(),
                                 applicationDetail.getArtifact(), applicationDetail.getOwnerPrincipal());
  }

  // get filter for app specs by artifact name and version. if they are null, it means don't filter.
  private com.google.common.base.Predicate<ApplicationRecord> getAppPredicate(Set<String> artifactNames,
                                                       @Nullable String artifactVersion) {
    if (artifactNames.isEmpty() && artifactVersion == null) {
      return Predicates.alwaysTrue();
    } else if (artifactNames.isEmpty()) {
      return new ArtifactVersionPredicate(artifactVersion);
    } else if (artifactVersion == null) {
      return new ArtifactNamesPredicate(artifactNames);
    } else {
      return Predicates.and(new ArtifactNamesPredicate(artifactNames), new ArtifactVersionPredicate(artifactVersion));
    }
  }

  /**
   * Returns true if the application artifact is in a whitelist of names
   */
  private static class ArtifactNamesPredicate implements com.google.common.base.Predicate<ApplicationRecord> {
    private final Set<String> names;

    ArtifactNamesPredicate(Set<String> names) {
      this.names = names;
    }

    @Override
    public boolean apply(ApplicationRecord input) {
      return names.contains(input.getArtifact().getName());
    }
  }

  /**
   * Returns true if the application artifact is a specific version
   */
  private static class ArtifactVersionPredicate implements com.google.common.base.Predicate<ApplicationRecord> {
    private final String version;

    ArtifactVersionPredicate(String version) {
      this.version = version;
    }

    @Override
    public boolean apply(ApplicationRecord input) {
      return version.equals(input.getArtifact().getVersion());
    }
  }
}
