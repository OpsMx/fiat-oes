/*
 * Copyright 2016 Google, Inc.
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

package com.netflix.spinnaker.fiat.controllers;

import com.netflix.spinnaker.fiat.model.UserPermission;
import com.netflix.spinnaker.fiat.model.resources.Role;
import com.netflix.spinnaker.fiat.permissions.ExternalUser;
import com.netflix.spinnaker.fiat.permissions.PermissionResolutionException;
import com.netflix.spinnaker.fiat.permissions.PermissionsRepository;
import com.netflix.spinnaker.fiat.permissions.PermissionsResolver;
import com.netflix.spinnaker.fiat.roles.UserRolesSyncer;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StopWatch;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/roles")
@ConditionalOnExpression("${fiat.write-mode.enabled:true}")
public class RolesController {

  @Autowired @Setter PermissionsResolver permissionsResolver;

  @Autowired @Setter PermissionsRepository permissionsRepository;

  @Autowired @Setter UserRolesSyncer syncer;

  @RequestMapping(value = "/{userId:.+}", method = RequestMethod.POST)
  public void putUserPermission(@PathVariable String userId) {
    try {
      UserPermission userPermission =
          permissionsResolver.resolve(ControllerSupport.convert(userId));
      log.debug(
          "Updated user permissions (userId: {}, roles: {})",
          userId,
          userPermission.getRoles().stream().map(Role::getName).collect(Collectors.toList()));

      permissionsRepository.put(userPermission);
    } catch (PermissionResolutionException pre) {
      throw new UserPermissionModificationException(pre);
    }
  }

  @RequestMapping(value = "/{userId:.+}", method = RequestMethod.PUT)
  public void putUserPermission(
      @PathVariable String userId, @RequestBody @NonNull List<String> externalRoles) {
    log.debug(
        "put user permission for userId : {}, size of the externalRoles : {} , externalRoles : {}",
        userId,
        externalRoles.size(),
        externalRoles);
    List<Role> convertedRoles =
        externalRoles.stream()
            .map(extRole -> new Role().setSource(Role.Source.EXTERNAL).setName(extRole))
            .collect(Collectors.toList());
    log.debug("converted roles : {}", convertedRoles);

    ExternalUser extUser =
        new ExternalUser()
            .setId(ControllerSupport.convert(userId))
            .setExternalRoles(convertedRoles);
    log.debug("external user : {}", extUser);

    try {
      UserPermission userPermission = permissionsResolver.resolveAndMerge(extUser);
      log.debug(
          "Updated user permissions (userId: {}, roles: {}, suppliedExternalRoles: {})",
          userId,
          userPermission.getRoles().stream().map(Role::getName).collect(Collectors.toList()),
          externalRoles);

      permissionsRepository.put(userPermission);
    } catch (PermissionResolutionException pre) {
      log.error("exception : ", pre);
      throw new UserPermissionModificationException(pre);
    } catch (Exception e) {
      log.error("Exception occurred while persisting : ", e);
    }
  }

  @RequestMapping(value = "/{userId:.+}", method = RequestMethod.DELETE)
  public void deleteUserPermission(@PathVariable String userId) {
    permissionsRepository.remove(ControllerSupport.convert(userId));
  }

  @RequestMapping(value = "/sync", method = RequestMethod.POST)
  public long sync(
      HttpServletResponse response, @RequestBody(required = false) List<String> specificRoles)
      throws IOException {

    log.info("Role sync invoked by web request for roles: {}", specificRoles);
    long count = syncer.syncAndReturn(specificRoles);
    if (count == 0) {
      log.info("No users found with specified roles");
      response.sendError(
          HttpServletResponse.SC_SERVICE_UNAVAILABLE,
          "Error occurred syncing permissions. See Fiat Logs.");
    }
    return count;
  }

  @RequestMapping(value = "/syncOnlyUnrestrictedUser", method = RequestMethod.POST)
  public long syncOnlyUnrestrictedUser(HttpServletResponse response) throws IOException {
    StopWatch watch = new StopWatch("RolesController.syncOnlyUnrestrictedUser");
    watch.start();
    log.trace("Role syncOnlyUnrestrictedUser invoked by web request for roles:");
    long count = syncer.syncOnlyUnrestrictedUserAndReturn();
    if (count == 0) {
      log.info("No users found with specified roles");
      response.sendError(
          HttpServletResponse.SC_SERVICE_UNAVAILABLE,
          "Error occurred syncing permissions. See Fiat Logs.");
    }
    watch.stop();
    log.trace("*** {} or {}s", watch.shortSummary(), watch.getTotalTimeSeconds());
    return count;
  }

  @RequestMapping(value = "/sync/serviceAccount/{serviceAccountId:.+}", method = RequestMethod.POST)
  public long syncServiceAccount(
      @PathVariable String serviceAccountId, @RequestBody List<String> specificRoles) {
    log.info(
        "Service Account {} sync invoked by web request with roles: {}",
        serviceAccountId,
        specificRoles);
    return syncer.syncServiceAccount(serviceAccountId, specificRoles);
  }
}
