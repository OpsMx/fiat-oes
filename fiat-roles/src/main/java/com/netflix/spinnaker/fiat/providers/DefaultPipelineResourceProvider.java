/*
 * Copyright 2022 OpxMx, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.fiat.providers;

import com.google.common.collect.ImmutableSet;
import com.netflix.spinnaker.fiat.model.resources.Permissions;
import com.netflix.spinnaker.fiat.model.resources.Pipeline;
import com.netflix.spinnaker.fiat.model.resources.Role;
import com.netflix.spinnaker.fiat.permissions.FallbackPermissionsResolver;
import com.netflix.spinnaker.fiat.providers.internal.Front50Service;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import lombok.NonNull;

public class DefaultPipelineResourceProvider extends BaseResourceProvider<Pipeline>
    implements ResourceProvider<Pipeline> {

  private final Front50Service front50Service;
  private final ResourcePermissionProvider<Pipeline> permissionProvider;
  private final FallbackPermissionsResolver executeFallbackPermissionsResolver;

  public DefaultPipelineResourceProvider(
      Front50Service front50Service,
      ResourcePermissionProvider<Pipeline> permissionProvider,
      FallbackPermissionsResolver executeFallbackPermissionsResolver) {
    this.front50Service = front50Service;
    this.permissionProvider = permissionProvider;
    this.executeFallbackPermissionsResolver = executeFallbackPermissionsResolver;
  }

  @Override
  public Set<Pipeline> getAllRestricted(
      @NonNull String userId, @NonNull Set<Role> userRoles, boolean isAdmin)
      throws ProviderException {
    return getAllPipelines(userId, userRoles, isAdmin, true);
  }

  @Override
  public Set<Pipeline> getAllUnrestricted() throws ProviderException {
    return getAllPipelines(null, Collections.emptySet(), false, false);
  }

  @Override
  protected Set<Pipeline> loadAll() throws ProviderException {
    try {
      final List<Pipeline> pipelines = front50Service.getAllPipelines();

      pipelines.forEach(
          pipeline -> {
            Permissions permissions = permissionProvider.getPermissions(pipeline);
            pipeline.setPermissions(
                executeFallbackPermissionsResolver.shouldResolve(permissions)
                    ? executeFallbackPermissionsResolver.resolve(permissions)
                    : permissions);
          });
      return ImmutableSet.copyOf(pipelines);
    } catch (RuntimeException e) {
      throw new ProviderException(this.getClass(), e);
    }
  }

  private Set<Pipeline> getAllPipelines(
      String userId, Set<Role> userRoles, boolean isAdmin, boolean isRestricted) {
    return isRestricted
        ? super.getAllRestricted(userId, userRoles, isAdmin)
        : super.getAllUnrestricted();
  }
}
