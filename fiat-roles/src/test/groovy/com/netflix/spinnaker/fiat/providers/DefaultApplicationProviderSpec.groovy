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

package com.netflix.spinnaker.fiat.providers

import com.netflix.spinnaker.fiat.config.ResourceProviderConfig.ApplicationProviderConfig
import com.netflix.spinnaker.fiat.model.Authorization
import com.netflix.spinnaker.fiat.model.resources.Application
import com.netflix.spinnaker.fiat.model.resources.Permissions
import com.netflix.spinnaker.fiat.model.resources.Role
import com.netflix.spinnaker.fiat.permissions.DefaultFallbackPermissionsResolver
import com.netflix.spinnaker.fiat.permissions.FallbackPermissionsResolver
import com.netflix.spinnaker.fiat.providers.internal.ClouddriverService
import com.netflix.spinnaker.fiat.providers.internal.Front50Service
import org.apache.commons.collections4.CollectionUtils
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

class DefaultApplicationProviderSpec extends Specification {
  private static final Authorization R = Authorization.READ
  private static final Authorization W = Authorization.WRITE
  private static final Authorization E = Authorization.EXECUTE

  ClouddriverService clouddriverService = Mock(ClouddriverService)
  Front50Service front50Service = Mock(Front50Service)
  ResourcePermissionProvider<Application> defaultProvider = new AggregatingResourcePermissionProvider<>([new ApplicationResourcePermissionSource()])
  FallbackPermissionsResolver fallbackPermissionsResolver = new DefaultFallbackPermissionsResolver(Authorization.EXECUTE, Authorization.READ)
  ApplicationProviderConfig applicationProviderConfig = new ApplicationProviderConfig();

  @Subject DefaultApplicationResourceProvider provider

  def makePerms(Map<Authorization, List<String>> auths) {
    Map<Authorization, Set<String>> data = new HashMap<>()
    if (auths != null) {
      auths.entrySet().forEach { data.put(it.key, new HashSet(it.value)) }
    }
    return Permissions.Builder.factory(data).build()
  }

  @Unroll
  def "should #action applications with empty permissions when allowAccessToUnknownApplications = #allowAccessToUnknownApplications"() {
    setup:
    Front50Service front50Service = Mock(Front50Service) {
      getAllApplications() >> [
          new Application().setName("onlyKnownToFront50"),
          new Application().setName("app1")
                           .setPermissions(new Permissions.Builder().add(Authorization.READ, "role").build()),
      ]
    }
    ClouddriverService clouddriverService = Mock(ClouddriverService) {
      getApplications() >> [
          new Application().setName("app1"),
          new Application().setName("onlyKnownToClouddriver")
      ]
    }

    provider = new DefaultApplicationResourceProvider(
            front50Service,
            clouddriverService,
            defaultProvider,
            fallbackPermissionsResolver,
            allowAccessToUnknownApplications,
            applicationProviderConfig)

    when:
    def restrictedResult = provider.getAllRestricted("userId", [new Role(role)] as Set<Role>, false)
    List<String> restrictedApplicationNames = restrictedResult*.name

    def unrestrictedResult = provider.getAllUnrestricted()
    List<String> unrestrictedApplicationNames = unrestrictedResult*.name

    then:
    CollectionUtils.disjunction(
        new HashSet(restrictedApplicationNames + unrestrictedApplicationNames),
        expectedApplicationNames
    ).isEmpty()

    where:
    allowAccessToUnknownApplications | role       || expectedApplicationNames
    false                            | "role"     || ["onlyKnownToFront50", "app1", "onlyKnownToClouddriver"]
    false                            | "bad_role" || ["onlyKnownToFront50", "onlyKnownToClouddriver"]
    true                             | "role"     || ["app1"]
    true                             | "bad_role" || ["app1"]

    action = allowAccessToUnknownApplications ? "exclude" : "include"
  }

  @Unroll
  def "should add fallback execute permissions only to applications where it is not already set"() {
    setup:
    def app = new Application().setName("app")

    when:
    app.setPermissions(makePerms(givenPermissions))
    provider = new DefaultApplicationResourceProvider(
            front50Service,
            clouddriverService,
            defaultProvider,
            fallbackPermissionsResolver,
            allowAccessToUnknownApplications,
            applicationProviderConfig)
    def resultApps = provider.getAll()

    then:
    1 * front50Service.getAllApplications() >> [app]
    1 * clouddriverService.getApplications() >> []

    resultApps.size() == 1
    makePerms(expectedPermissions) == resultApps.permissions[0]

    where:
    givenPermissions           | allowAccessToUnknownApplications || expectedPermissions
    [:]                        | false                            || [:]
    [(R): ['r']]               | false                            || [(R): ['r'], (E): ['r']]
    [(R): ['r'], (E): ['foo']] | false                            || [(R): ['r'], (E): ['foo']]
    [(R): ['r']]               | true                             || [(R): ['r'], (E): ['r']]
  }

  @Unroll
  def "should add fallback execute permissions based on executeFallback value" () {
    setup:
    def app = new Application().setName("app")
    FallbackPermissionsResolver fallbackResolver = new DefaultFallbackPermissionsResolver(Authorization.EXECUTE, fallback)

    when:
    app.setPermissions(makePerms(givenPermissions))
    provider = new DefaultApplicationResourceProvider(
            front50Service,
            clouddriverService,
            defaultProvider,
            fallbackResolver,
            false,
            applicationProviderConfig)
    def resultApps = provider.getAll()

    then:
    1 * front50Service.getAllApplications() >> [app]
    1 * clouddriverService.getApplications() >> []

    resultApps.size() == 1
    makePerms(expectedPermissions) == resultApps.permissions[0]

    where:
    fallback    | givenPermissions         || expectedPermissions
    R           | [:]                      || [:]
    R           | [(R): ['r']]             || [(R): ['r'], (E): ['r']]
    W           | [(R): ['r'], (W): ['w']] || [(R): ['r'], (W): ['w'], (E): ['w']]
  }

  @Unroll
  def "enable calling Clouddriver during application load based on config"() {

    setup:
    final Map<String,String> map1 = new HashMap<>()
    map1.put("foo", "bar")
    map1.put("xyz", "pqr")
    //final Map<String,String> map2 = HashMap.any("foo", "bar", "xyz", "pqr")
    final Map<String,String> map2 = new HashMap<>()
    map2.put("foo", "bar")
    map2.put("xyz", "pqr")
    def front50Apps = [
            new Application().setName("front50App1")
                    .setDetails(map1)
                    .setPermissions(new Permissions.Builder().add(Authorization.READ, "role").build()),
            new Application().setName("front50App2")
                    .setDetails(map2)
                    .setPermissions(new Permissions.Builder().add(Authorization.READ, "role").build())
    ]

    def clouddriverApps = [
            new Application().setName("clouddriverApp1")
                    .setDetails(map1)
                    .setPermissions(new Permissions.Builder().add(Authorization.READ, "role").build()),
            new Application().setName("clouddriverApp2")
                    .setDetails(map2)
                    .setPermissions(new Permissions.Builder().add(Authorization.READ, "role").build())
    ]

    Front50Service front50Service = Mock(Front50Service) {
      getAllApplications() >> front50Apps
    }

    ClouddriverService clouddriverService = Mock(ClouddriverService) {
      getApplications() >> clouddriverApps
    }

    applicationProviderConfig.clouddriver.setLoadApplications(loadApplicationsFromClouddriver)

    provider = new DefaultApplicationResourceProvider(
            front50Service,
            clouddriverService,
            defaultProvider,
            fallbackPermissionsResolver,
            false,
            applicationProviderConfig,
    )

    when:
    def result = provider.loadAll()

    def actualAppNames = result.collect{
      it.getName()
    }

    then:
    actualAppNames == expectedAppNames

    where:
    loadApplicationsFromClouddriver  | expectedAppNames
    false                            | ["front50App1", "front50App2"]
    true                             | ["front50App1", "front50App2", "clouddriverApp1", "clouddriverApp2"]
  }

  @Unroll
  def "should suppress details when loading all applications"() {

    setup:
    def testApps = [
            new Application().setName("front50App1")
                    .setDetails([foo: "bar", xyz: "pqr"])
                    .setPermissions(new Permissions.Builder().add(Authorization.READ, "role").build()),
            new Application().setName("front50App2")
                    .setDetails([foo: "bar", xyz: "pqr"])
                    .setPermissions(new Permissions.Builder().add(Authorization.READ, "role").build())
    ]

    Set<Application> expectedApps = [
            new Application().setName("front50App1")
                    .setDetails([foo: "bar", xyz: "pqr"])
                    .setPermissions(new Permissions.Builder()
                            .add(Authorization.READ, "role")
                            .add(Authorization.EXECUTE, "role")
                            .build()),
            new Application().setName("front50App2")
                    .setDetails([foo: "bar", xyz: "pqr"])
                    .setPermissions(new Permissions.Builder()
                            .add(Authorization.READ, "role")
                            .add(Authorization.EXECUTE, "role")
                            .build()),
    ] as Set

    Front50Service front50Service = Mock(Front50Service) {
      getAllApplications() >> testApps
    }

    ClouddriverService clouddriverService = Mock(ClouddriverService) {
      getApplications() >> []
    }

    applicationProviderConfig.setSuppressDetails(suppressDetails)
    applicationProviderConfig.setDetailsExcludedFromSuppression(excludeFromSupression)
    provider = new DefaultApplicationResourceProvider(
            front50Service,
            clouddriverService,
            defaultProvider,
            fallbackPermissionsResolver,
            true,
            applicationProviderConfig,
    )

    when:
    def result = provider.loadAll()

    then:
    result == expectedApps.each {
      it.setDetails(expectedDetails)
    }

    where:
    suppressDetails | excludeFromSupression | expectedDetails
    false           | [] as Set             | [foo: "bar", xyz: "pqr"]
    true            | [] as Set             | [:]
    true            | ["foo", "xyz"] as Set | [foo: "bar", xyz: "pqr"]
    true            | ["foo"] as Set        | [foo: "bar"]
  }

}
