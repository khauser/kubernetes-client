/**
 * Copyright (C) 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.kubernetes.client.dsl.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.fabric8.kubernetes.api.model.DeleteOptions;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.dsl.base.OperationSupport;
import io.fabric8.kubernetes.client.utils.HttpClientUtils;
import io.fabric8.kubernetes.client.utils.IOHelpers;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.kubernetes.client.utils.Utils;
import io.fabric8.kubernetes.client.utils.WatcherToggle;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * This class simple does basic operations for custom defined resources without
 * demanding the POJOs for custom resources. It is serializing/deserializing
 * objects to plain hash map(String, Object).
 *
 * Right now it supports basic operations like GET, POST, PUT, DELETE.
 *
 */
public class RawCustomResourceOperationsImpl extends OperationSupport {
  
  private static final String METADATA = "metadata";
  private static final String RESOURCE_VERSION = "resourceVersion";
  private OkHttpClient client;
  private Config config;
  private CustomResourceDefinitionContext customResourceDefinition;
  private ObjectMapper objectMapper;

  private enum HttpCallMethod { GET, POST, PUT, DELETE };

  public RawCustomResourceOperationsImpl(OkHttpClient client, Config config, CustomResourceDefinitionContext customResourceDefinition) {
    this.client = client;
    this.config = config;
    this.customResourceDefinition = customResourceDefinition;
    this.objectMapper = Serialization.jsonMapper();
  }

  /**
   * Load a custom resource object from an inputstream into a HashMap
   *
   * @param fileInputStream file input stream
   * @return custom resource as HashMap
   * @throws IOException exception in case any read operation fails.
   */
  public Map<String, Object> load(InputStream fileInputStream) throws IOException {
    return convertJsonOrYamlStringToMap(IOHelpers.readFully(fileInputStream));
  }

  /**
   * Load a custom resource object from a JSON string into a HashMap
   *
   * @param objectAsJsonString object as JSON string
   * @return custom resource as HashMap
   * @throws IOException exception in case any problem in reading json.
   */
  public Map<String, Object> load(String objectAsJsonString) throws IOException {
    return convertJsonOrYamlStringToMap(objectAsJsonString);
  }

  /**
   * Create a custom resource which is a non-namespaced object.
   *
   * @param objectAsString object as JSON string
   * @return Object as HashMap
   * @throws IOException exception in case of any network/read problems
   */
  public Map<String, Object> create(String objectAsString) throws IOException {
    return validateAndSubmitRequest(null, null, objectAsString, HttpCallMethod.POST);
  }

  /**
   * Create a custom resource which is non-namespaced.
   *
   * @param object object a HashMap
   * @return Object as HashMap
   * @throws IOException in case of problems while reading HashMap
   */
  public Map<String, Object> create(Map<String, Object> object) throws IOException {
    return validateAndSubmitRequest(null, null, objectMapper.writeValueAsString(object), HttpCallMethod.POST);
  }

  /**
   * Create a custom resource which is a namespaced object.
   *
   * @param namespace namespace in which we want object to be created.
   * @param objectAsString Object as JSON string
   * @return Object as HashMap
   * @throws IOException in case of problems while reading JSON object
   */
  public Map<String, Object> create(String namespace, String objectAsString) throws IOException {
    return validateAndSubmitRequest(namespace, null, objectAsString, HttpCallMethod.POST);
  }

  /**
   * Create a custom resource which is non-namespaced object.
   *
   * @param objectAsStream object as a file input stream
   * @return Object as HashMap
   * @throws IOException in case of problems while reading file
   */
  public Map<String, Object> create(InputStream objectAsStream) throws IOException {
    return validateAndSubmitRequest(null, null, IOHelpers.readFully(objectAsStream), HttpCallMethod.POST);
  }

  /**
   * Create a custom resource which is a namespaced object.
   *
   * @param namespace namespace in which we want object to be created
   * @param objectAsStream object as file input stream
   * @return Object as HashMap
   * @throws IOException in case of problems while reading file
   */
  public Map<String, Object> create(String namespace, InputStream objectAsStream) throws IOException {
    return validateAndSubmitRequest(namespace, null, IOHelpers.readFully(objectAsStream), HttpCallMethod.POST);
  }

  /**
   * Create a custom resource which is a namespaced object.
   *
   * @param namespace namespace in which we want object to be created.
   * @param object object as a HashMap
   * @return Object as HashMap
   * @throws IOException in case of problems faced while serializing HashMap
   */
  public Map<String, Object> create(String namespace, Map<String, Object> object) throws IOException {
    return validateAndSubmitRequest(namespace, null, objectMapper.writeValueAsString(object), HttpCallMethod.POST);
  }

  /**
   *
   * Create or replace a custom resource which is a non-namespaced object.
   *
   * @param objectAsString object as JSON string
   * @return Object as HashMap
   * @throws IOException in case of network/serializiation failures or failures from Kuberntes API
   */
  public Map<String, Object> createOrReplace(String objectAsString) throws IOException {
    return createOrReplaceObject(null, load(objectAsString));
  }

  /**
   * Create or replace a custom resource which is a non-namespced object.
   *
   * @param customResourceObject object as HashMap
   * @return Object as HashMap
   * @throws IOException in case of network/serialization failures or failures from Kubernetes API
   */
  public Map<String, Object> createOrReplace(Map<String, Object> customResourceObject) throws IOException {
    return createOrReplaceObject(null, customResourceObject);
  }

  /**
   * Create or replace a custom resource which is non-namespaced object.
   *
   * @param inputStream object as file input stream
   * @return Object as HashMap
   * @throws IOException in case of network/serialization failures or failures from Kubernetes API
   */
  public Map<String, Object> createOrReplace(InputStream inputStream) throws IOException {
    return createOrReplaceObject(null, load(inputStream));
  }

  /**
   * Create or replace a custom resource which is namespaced object.
   *
   * @param namespace desired namespace
   * @param objectAsString object as JSON String
   * @return Object as HashMap
   * @throws IOException in case of network/serialization failures or failures from Kubernetes API
   */
  public Map<String, Object> createOrReplace(String namespace, String objectAsString) throws IOException {
    return createOrReplaceObject(namespace, load(objectAsString));
  }

  /**
   * Create or replace a custom resource which is namespaced object.
   *
   * @param namespace desired namespace
   * @param customResourceObject object as HashMap
   * @return Object as HashMap
   * @throws IOException in case of network/serialization failures or failures from Kubernetes API
   */
  public Map<String, Object> createOrReplace(String namespace, Map<String, Object> customResourceObject) throws IOException {
    return createOrReplaceObject(namespace, customResourceObject);
  }

  /**
   * Create or replace a custom resource which is namespaced object.
   *
   * @param namespace desired namespace
   * @param objectAsStream object as file input stream
   * @return Object as HashMap
   * @throws IOException in case of network/serialization failures or failures from Kubernetes API
   */
  public Map<String, Object> createOrReplace(String namespace, InputStream objectAsStream) throws IOException {
    return createOrReplaceObject(namespace, load(objectAsStream));
  }

  /**
   * Edit a custom resource object which is a non-namespaced object.
   *
   * @param name name of the custom resource
   * @param object new object as a HashMap
   * @return Object as HashMap
   * @throws IOException in case of network/serialization failures or failures from Kubernetes API
   */
  public Map<String, Object> edit(String name, Map<String, Object> object) throws IOException {
    return validateAndSubmitRequest(null, name, objectMapper.writeValueAsString(object), HttpCallMethod.PUT);
  }

  /**
   * Edit a custom resource object which is a non-namespaced object.
   *
   * @param name name of the custom resource
   * @param objectAsString new object as a JSON String
   * @return Object as HashMap
   * @throws IOException in case of network/serialization failures or failures from Kubernetes API
   */
  public Map<String, Object> edit(String name, String objectAsString) throws IOException {
    return validateAndSubmitRequest(null, name, objectAsString, HttpCallMethod.PUT);
  }

  /**
   * Edit a custom resource object which is a namespaced object.
   *
   * @param namespace desired namespace
   * @param name name of the custom resource
   * @param object new object as a HashMap
   * @return Object as HashMap
   * @throws IOException in case of network/serialization failures or failures from Kubernetes API
   */
  public Map<String, Object> edit(String namespace, String name, Map<String, Object> object) throws IOException {
    object = appendResourceVersionInObject(namespace, name, object);
    return validateAndSubmitRequest(namespace, name, objectMapper.writeValueAsString(object), HttpCallMethod.PUT);
  }

  /**
   * Edit a custom resource object which is a namespaced object.
   *
   * @param namespace desired namespace
   * @param name name of the custom resource
   * @param objectAsString new object as a JSON string
   * @return Object as HashMap
   * @throws IOException in case of network/serialization failures or failures from Kubernetes API
   */
  public Map<String, Object> edit(String namespace, String name, String objectAsString) throws IOException {
    // Append resourceVersion in object metadata in order to
    // avoid : https://github.com/fabric8io/kubernetes-client/issues/1724
    objectAsString = appendResourceVersionInObject(namespace, name, objectAsString);
    return validateAndSubmitRequest(namespace, name, objectAsString, HttpCallMethod.PUT);
  }

  /**
   * Edit a custom resource object which is a non-namespaced object.
   *
   * @param name name of the custom resource
   * @param objectAsStream new object as a file input stream
   * @return Object as HashMap
   * @throws IOException in case of network/serialization failures or failures from Kubernetes API
   */
  public Map<String, Object> edit(String name, InputStream objectAsStream) throws IOException {
    return validateAndSubmitRequest(null, name, IOHelpers.readFully(objectAsStream), HttpCallMethod.PUT);
  }

  /**
   * Edit a custom resource object which is a namespaced object.
   *
   * @param namespace desired namespace
   * @param name name of the custom resource
   * @param objectAsStream new object as a file input stream
   * @return Object as HashMap
   * @throws IOException in case of network/serialization failures or failures from Kubernetes API
   */
  public Map<String, Object> edit(String namespace, String name, InputStream objectAsStream) throws IOException {
    return validateAndSubmitRequest(namespace, name, IOHelpers.readFully(objectAsStream), HttpCallMethod.PUT);
  }

  /**
   * Update status related to a CustomResource, this method does a PUT request on /status endpoint related
   * to the CustomResource
   *
   * @param name name of custom resource
   * @param objectAsMap custom resource as a HashMap
   * @return updated CustomResource as HashMap
   * @throws IOException in case any failure to parse Map
   */
  public Map<String, Object> updateStatus(String name, Map<String, Object> objectAsMap) throws IOException {
    return validateAndSubmitRequest(fetchUrl(null, name, null) + "/status", objectMapper.writeValueAsString(objectAsMap), HttpCallMethod.PUT);
  }

  /**
   * Update status related to a CustomResource, this method does a PUT request on /status endpoint related
   * to the CustomResource
   *
   * @param name name of CustomResource
   * @param objectAsJsonString CustomResource as a JSON string
   * @return updated CustomResource as a HashMap
   * @throws IOException in case any failure to parse Map
   */
  public Map<String, Object> updateStatus(String name, String objectAsJsonString) throws IOException {
    return validateAndSubmitRequest(fetchUrl(null, name, null) + "/status", objectAsJsonString, HttpCallMethod.PUT);
  }

  /**
   * Update status related to a CustomResource, this method does a PUT request on /status endpoint related
   * to the CustomResource
   *
   * @param namespace namespace of CustomResource
   * @param name name of CustomResource
   * @param objectAsMap CustomResource as a HashMap
   * @return updated CustomResource as a HashMap
   * @throws IOException in case any failure to parse Map
   */
  public Map<String, Object> updateStatus(String namespace, String name, Map<String, Object> objectAsMap) throws IOException {
    return validateAndSubmitRequest(fetchUrl(namespace, name, null) + "/status", objectMapper.writeValueAsString(objectAsMap), HttpCallMethod.PUT);
  }

  /**
   * Update status related to a CustomResource, this method does a PUT request on /status endpoint related
   * to the CustomResource
   *
   * @param name name of CustomResource
   * @param objectAsStream stream pointing to CustomResource
   * @return updated CustomResource as a HashMap
   * @throws IOException in case any failure to parse Map
   */
  public Map<String, Object> updateStatus(String name, InputStream objectAsStream) throws IOException {
    return validateAndSubmitRequest(fetchUrl(null, name, null) + "/status", IOHelpers.readFully(objectAsStream), HttpCallMethod.PUT);
  }

  /**
   * Update status related to a CustomResource, this method does a PUT request on /status endpoint related
   * to the CustomResource
   *
   * @param namespace namespace of CustomResource
   * @param name name of CustomResource
   * @param objectAsStream CustomResource object as a stream
   * @return updated CustomResource as a HashMap
   * @throws IOException in case any failure to parse Map
   */
  public Map<String, Object> updateStatus(String namespace, String name, InputStream objectAsStream) throws IOException {
    return validateAndSubmitRequest(fetchUrl(namespace, name, null) + "/status", IOHelpers.readFully(objectAsStream), HttpCallMethod.PUT);
  }

  /**
   * Update status related to a CustomResource, this method does a PUT request on /status endpoint related
   * to the CustomResource
   *
   * @param namespace namespace of CustomResource
   * @param name name of CustomResource
   * @param objectAsJsonString CustomResource object as a JSON string
   * @return updated CustomResource as a HashMap
   * @throws IOException in case any failure to parse Map
   */
  public Map<String, Object> updateStatus(String namespace, String name, String objectAsJsonString) throws IOException {
    return validateAndSubmitRequest(fetchUrl(namespace, name, null) + "/status", objectAsJsonString, HttpCallMethod.PUT);
  }

  /**
   * Get a custom resource from the cluster which is non-namespaced.
   *
   * @param name name of custom resource
   * @return Object as HashMap
   */
  public Map<String, Object> get(String name) {
    return makeCall(fetchUrl(null, name, null), null, HttpCallMethod.GET);
  }

  /**
   * Get a custom resource from the cluster which is namespaced.
   *
   * @param namespace desired namespace
   * @param name name of custom resource
   * @return Object as HashMap
   */
  public Map<String, Object> get(String namespace, String name) {
      return makeCall(fetchUrl(namespace, name, null), null, HttpCallMethod.GET);
  }

  /**
   * List all custom resources in all namespaces
   *
   * @return list of custom resources as HashMap
   */
  public Map<String, Object> list() {
    return makeCall(fetchUrl(null, null, null), null, HttpCallMethod.GET);
  }

  /**
   * List all custom resources in a specific namespace
   *
   * @param namespace desired namespace
   * @return list of custom resources as HashMap
   */
  public Map<String, Object> list(String namespace) {
    return makeCall(fetchUrl(namespace, null, null), null, HttpCallMethod.GET);
  }

  /**
   * List all custom resources in a specific namespace with some labels
   *
   * @param namespace desired namespace
   * @param labels labels as a HashMap
   * @return list of custom resources as HashMap
   */
  public Map<String, Object> list(String namespace, Map<String, String> labels) {
    return makeCall(fetchUrl(namespace, null, labels), null, HttpCallMethod.GET);
  }

  /**
   * Delete all custom resources in a specific namespace
   *
   * @param namespace desired namespace
   * @return deleted objects as HashMap
   */
  public Map<String, Object> delete(String namespace) {
    return makeCall(fetchUrl(namespace, null, null), null, HttpCallMethod.DELETE);
  }

  /**
   * Delete all custom resources in a specific namespace
   *
   * @param namespace desired namespace
   * @param cascading whether dependent object need to be orphaned or not.  If true/false, the "orphan"
   *                   finalizer will be added to/removed from the object's finalizers list.
   * @return deleted objects as HashMap
   * @throws IOException in case of any network/parsing exception
   */
  public Map<String, Object> delete(String namespace, boolean cascading) throws IOException {
    return makeCall(fetchUrl(namespace, null, null), objectMapper.writeValueAsString(fetchDeleteOptions(cascading, null)), HttpCallMethod.DELETE);
  }

  /**
   * Delete all custom resources in a specific namespace
   *
   * @param namespace desired namespace
   * @param deleteOptions object provided by Kubernetes API for more fine grained control over deletion.
   *                       For more information please see https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.16/#deleteoptions-v1-meta
   * @return deleted object as HashMap
   * @throws IOException in case of any network/object parse problems
   */
  public Map<String, Object> delete(String namespace, DeleteOptions deleteOptions) throws IOException {
    return makeCall(fetchUrl(namespace, null, null), objectMapper.writeValueAsString(deleteOptions), HttpCallMethod.DELETE);
  }

  /**
   * Delete a custom resource in a specific namespace
   *
   * @param namespace desired namespace
   * @param name custom resource's name
   * @return object as HashMap
   * @throws IOException in case of any network/object parse problems
   */
  public Map<String, Object> delete(String namespace, String name) throws IOException {
    return makeCall(fetchUrl(namespace, name, null), objectMapper.writeValueAsString(fetchDeleteOptions(false, DeletionPropagation.BACKGROUND.toString())), HttpCallMethod.DELETE);
  }

  /**
   * Delete a custom resource in a specific namespace
   *
   * @param namespace required namespace
   * @param name required name of custom resource
   * @param cascading whether dependent object need to be orphaned or not.  If true/false, the "orphan"
   *                   finalizer will be added to/removed from the object's finalizers list.
   * @return deleted objects as HashMap
   * @throws IOException exception related to network/object parsing
   */
  public Map<String, Object> delete(String namespace, String name, boolean cascading) throws IOException {
    return makeCall(fetchUrl(namespace, name, null), objectMapper.writeValueAsString(fetchDeleteOptions(cascading, null)), HttpCallMethod.DELETE);
  }

  /**
   * Delete a custom resource in a specific namespace
   *
   * @param namespace required namespace
   * @param name required name of custom resource
   * @param propagationPolicy Whether and how garbage collection will be performed. Either this field or OrphanDependents
   *                            may be set, but not both. The default policy is decided by the existing finalizer set in
   *                            the metadata.finalizers and the resource-specific default policy.
   *                            Acceptable values are:
   *                            'Orphan' - orphan the dependents;
   *                            'Background' - allow the garbage collector to delete the dependents in the background;
   *                            'Foreground' - a cascading policy that deletes all dependents in the foreground.
   * @return deleted object as HashMap
   * @throws IOException in case of network/object parse exception
   */
  public Map<String, Object> delete(String namespace, String name, String propagationPolicy) throws IOException {
    return makeCall(fetchUrl(namespace, name, null), objectMapper.writeValueAsString(fetchDeleteOptions(false, propagationPolicy)) , HttpCallMethod.DELETE);
  }

  /**
   * Delete a custom resource in a specific namespace
   *
   * @param namespace required namespace
   * @param name name of custom resource
   * @param deleteOptions object provided by Kubernetes API for more fine grained control over deletion.
   *                       For more information please see https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.16/#deleteoptions-v1-meta
   * @return deleted object as HashMap
   * @throws IOException in case of any network/object parse exception
   */
  public Map<String, Object> delete(String namespace, String name, DeleteOptions deleteOptions) throws IOException {
    return makeCall(fetchUrl(namespace, name, null), objectMapper.writeValueAsString(deleteOptions), HttpCallMethod.DELETE);
  }

  /**
   * Watch custom resources in a specific namespace. Here Watcher is provided
   * for string type only. User has to deserialize object itself.
   *
   * @param namespace namespace to watch
   * @param watcher watcher object which reports updates with object
   * @throws IOException in case of network error
   */
  public void watch(String namespace, Watcher<String> watcher) throws IOException {
    watch(namespace, null, null, new ListOptionsBuilder().build(), watcher);
  }

  /**
   * Watch a custom resource in a specific namespace with some resourceVersion. Here
   * watcher is provided from string type only. User has to deserialize object itself.
   *
   * @param namespace namespace to watch
   * @param resourceVersion resource version since when to watch
   * @param watcher watcher object which reports updates
   * @throws IOException in case of network error
   */
  public void watch(String namespace, String resourceVersion, Watcher<String> watcher) throws IOException {
    watch(namespace, null, null, new ListOptionsBuilder().withResourceVersion(resourceVersion).build(), watcher);
  }

  /**
   * Watch a custom resource in a specific namespace with some resourceVersion. Here
   * watcher is provided from string type only. User has to deserialize object itself.
   *
   * @param namespace namespace to watch
   * @param options {@link ListOptions} list options for watching
   * @param watcher watcher object which reports updates
   * @throws IOException in case of network error
   */
  public void watch(String namespace, ListOptions options, Watcher<String> watcher) throws IOException {
    watch(namespace, null, null, options, watcher);
  }

  /**
   * Watchers custom resources across all namespaces. Here watcher is provided
   * for string type only. User has to deserialize object itself.
   *
   * @param watcher watcher object which reports events
   * @throws IOException in case of network error
   */
  public void watch(Watcher<String> watcher) throws IOException {
    watch(null, null, null, new ListOptionsBuilder().build(), watcher);
  }

  /**
   * Watch custom resources in the parameters specified.
   *
   * Most of the parameters except watcher are optional, they would be
   * skipped if passed null. Here watcher is provided for string type
   * only. User has to deserialize the object itself.
   *
   * @param namespace namespace to watch (optional
   * @param name name of custom resource (optional)
   * @param labels HashMap containing labels (optional)
   * @param resourceVersion resource version to start watch from
   * @param watcher watcher object which reports events
   * @return watch object for watching resource
   * @throws IOException in case of network error
   */
  public Watch watch(String namespace, String name, Map<String, String> labels, String resourceVersion, Watcher<String> watcher) throws IOException {
    return watch(namespace, name, labels, new ListOptionsBuilder().withResourceVersion(resourceVersion).build(), watcher);
  }

  /**
   * Watch custom resources in the parameters specified.
   *
   * Most of the parameters except watcher are optional, they would be
   * skipped if passed null. Here watcher is provided for string type
   * only. User has to deserialize the object itself.
   *
   * @param namespace namespace to watch (optional
   * @param name name of custom resource (optional)
   * @param labels HashMap containing labels (optional)
   * @param options {@link ListOptions} list options for watch
   * @param watcher watcher object which reports events
   * @return watch object for watching resource
   * @throws IOException in case of network error
   */
  public Watch watch(String namespace, String name, Map<String, String> labels, ListOptions options, Watcher<String> watcher) throws IOException {
    if (options == null) {
      options = new ListOptions();
    }
    options.setWatch(true);
    HttpUrl.Builder watchUrlBuilder = fetchWatchUrl(namespace, name, labels, options);

    OkHttpClient.Builder clonedClientBuilder = client.newBuilder();
      clonedClientBuilder.readTimeout(getConfig() != null ?
        getConfig().getWebsocketTimeout() : Config.DEFAULT_WEBSOCKET_TIMEOUT, TimeUnit.MILLISECONDS);
      clonedClientBuilder.pingInterval(getConfig() != null ?
        getConfig().getWebsocketPingInterval() : Config.DEFAULT_WEBSOCKET_PING_INTERVAL, TimeUnit.MILLISECONDS);

    OkHttpClient clonedOkHttpClient = clonedClientBuilder.build();
    WatcherToggle<String> watcherToggle = new WatcherToggle<>(watcher, true);
    RawWatchConnectionManager watch = null;
    try {
      watch = new RawWatchConnectionManager(
        clonedOkHttpClient, watchUrlBuilder, options, objectMapper, watcher,
        getConfig() != null ? getConfig().getWatchReconnectLimit() : -1,
        getConfig() != null ? getConfig().getWatchReconnectInterval() : 1000,
        5);
      watch.waitUntilReady();
      return watch;
    } catch (KubernetesClientException ke) {

      if (ke.getCode() != 200) {
        if(watch != null){
          //release the watch
          watch.close();
        }

        throw ke;
      }

      if(watch != null){
        //release the watch after disabling the watcher (to avoid premature call to onClose)
        watcherToggle.disable();
        watch.close();
      }

      // If the HTTP return code is 200, we retry the watch again using a persistent hanging
      // HTTP GET. This is meant to handle cases like kubectl local proxy which does not support
      // websockets. Issue: https://github.com/kubernetes/kubernetes/issues/25126
      return new RawWatchConnectionManager(
        clonedOkHttpClient, watchUrlBuilder, options, objectMapper, watcher,
        getConfig() != null ? getConfig().getWatchReconnectLimit() : -1,
        getConfig() != null ? getConfig().getWatchReconnectInterval() : 1000,
        5);
    }

  }

  private Map<String, Object> createOrReplaceObject(String namespace, Map<String, Object> objectAsMap) throws IOException {
    Map<String, Object> metadata = (Map<String, Object>) objectAsMap.get(METADATA);
    if (metadata == null) {
      throw KubernetesClientException.launderThrowable(new IllegalStateException("Invalid object provided -- metadata is required."));
    }

    Map<String, Object> ret;

    // can't include resourceVersion in create calls
    String originalResourceVersion = (String) metadata.get(RESOURCE_VERSION);
    metadata.remove(RESOURCE_VERSION);

    try {
      if(namespace != null) {
        ret = create(namespace, objectAsMap);
      } else {
        ret = create(objectAsMap);
      }
    } catch (KubernetesClientException exception) {
      if (exception.getCode() != HttpURLConnection.HTTP_CONFLICT) {
        throw exception;
      }

      try {
        // re-add for edit call
        if (originalResourceVersion != null) {
          metadata.put(RESOURCE_VERSION, originalResourceVersion);
        }
        String name = (String) metadata.get("name");
        ret = namespace != null ?
          edit(namespace, name, objectAsMap) : edit(name, objectAsMap);
      } catch (NullPointerException nullPointerException) {
        throw KubernetesClientException.launderThrowable(new IllegalStateException("Invalid object provided -- metadata.name is required."));
      }
    }
    return ret;
  }

  /**
   * Converts yaml/json object as string to a HashMap.
   * This method checks whether
   *
   * @param objectAsString JSON or Yaml object as plain string
   * @return object being deserialized to a HashMap
   * @throws IOException in case of any parsing error
   */
  private Map<String, Object> convertJsonOrYamlStringToMap(String objectAsString) throws IOException {
    HashMap<String, Object> retVal = null;
    if (IOHelpers.isJSONValid(objectAsString)) {
      retVal =  objectMapper.readValue(objectAsString, HashMap.class);
    } else {
      retVal = objectMapper.readValue(IOHelpers.convertYamlToJson(objectAsString), HashMap.class);
    }
    return retVal;
  }

  protected HttpUrl.Builder fetchWatchUrl(String namespace, String name, Map<String, String> labels, ListOptions options) throws MalformedURLException {
    String resourceUrl = fetchUrl(namespace, null, labels);
    if (resourceUrl.endsWith("/")) {
      resourceUrl = resourceUrl.substring(0, resourceUrl.length() - 1);
    }
    URL url = new URL(resourceUrl);
    HttpUrl.Builder httpUrlBuilder = HttpUrl.get(url).newBuilder();

    if (name != null) {
      httpUrlBuilder.addQueryParameter("fieldSelector", "metadata.name=" + name);
    }

    HttpClientUtils.appendListOptionParams(httpUrlBuilder, options);
    return httpUrlBuilder;
  }

  private String fetchUrl(String namespace, String name, Map<String, String> labels) {
    if (config.getMasterUrl() == null) {
      return null;
    }

    StringBuilder urlBuilder = new StringBuilder(config.getMasterUrl());

    urlBuilder.append(config.getMasterUrl().endsWith("/") ? "" : "/");
    urlBuilder.append("apis/")
      .append(customResourceDefinition.getGroup())
      .append("/")
      .append(customResourceDefinition.getVersion())
      .append("/");

    if(customResourceDefinition.getScope().equals("Namespaced") && namespace != null) {
      urlBuilder.append("namespaces/").append(namespace).append("/");
    }
    urlBuilder.append(customResourceDefinition.getPlural());
    if (name != null) {
      urlBuilder.append("/").append(name);
    }
    if(labels != null) {
      urlBuilder.append("?labelSelector").append("=").append(getLabelsQueryParam(labels));
    }
    return urlBuilder.toString();
  }

  private String getLabelsQueryParam(Map<String, String> labels) {
    StringBuilder labelQueryBuilder = new StringBuilder();
    for(Map.Entry<String, String> entry : labels.entrySet()) {
      if(labelQueryBuilder.length() > 0) {
        labelQueryBuilder.append(",");
      }
      labelQueryBuilder.append(entry.getKey()).append(Utils.toUrlEncoded("=")).append(entry.getValue());
    }
    return labelQueryBuilder.toString();
  }

  private Map<String, Object> makeCall(String url, String body, HttpCallMethod callMethod) {
    Request request = (body == null) ? getRequest(url, callMethod) : getRequest(url, body, callMethod);
    try (Response response = client.newCall(request).execute()) {
      if (response.isSuccessful()) {
        String respBody = response.body().string();
        if(Utils.isNullOrEmpty(respBody))
          return new HashMap<>();
        else
          return objectMapper.readValue(respBody, HashMap.class);
      } else {
        throw requestFailure(request, createStatus(response));
      }
    } catch(Exception e) {
      throw KubernetesClientException.launderThrowable(e);
    }
  }

  private Map<String, Object> validateAndSubmitRequest(String namespace, String name, String objectAsString, HttpCallMethod httpCallMethod) throws IOException {
    return validateAndSubmitRequest(fetchUrl(namespace, name, null), objectAsString, httpCallMethod);
  }

  private Map<String, Object> validateAndSubmitRequest(String resourceUrl, String objectAsString, HttpCallMethod httpCallMethod) throws IOException {
    if (IOHelpers.isJSONValid(objectAsString)) {
      return makeCall(resourceUrl, objectAsString, httpCallMethod);
    } else {
      return makeCall(resourceUrl, IOHelpers.convertYamlToJson(objectAsString), httpCallMethod);
    }
  }

  private Request getRequest(String url, HttpCallMethod httpCallMethod) {
    Request.Builder requestBuilder = new Request.Builder();
    switch(httpCallMethod) {
      case GET:
        requestBuilder.get().url(url);
        break;
      case DELETE:
        requestBuilder.delete().url(url);
        break;
    }

    return requestBuilder.build();
  }

  private Request getRequest(String url, String body, HttpCallMethod httpCallMethod) {
    Request.Builder requestBuilder = new Request.Builder();
    RequestBody requestBody = RequestBody.create(MediaType.parse("application/json"), body);
    switch(httpCallMethod) {
      case DELETE:
        return requestBuilder.delete(requestBody).url(url).build();
      case POST:
        return requestBuilder.post(requestBody).url(url).build();
      case PUT:
        return requestBuilder.put(requestBody).url(url).build();
    }
    return requestBuilder.build();
  }

  private String appendResourceVersionInObject(String namespace, String customResourceName, String customResourceAsJsonString) throws IOException {
    Map<String, Object> newObject = convertJsonOrYamlStringToMap(customResourceAsJsonString);

    return objectMapper.writeValueAsString(appendResourceVersionInObject(namespace, customResourceName, newObject));
  }

  private Map<String, Object> appendResourceVersionInObject(String namespace, String customResourceName, Map<String, Object> customResource) throws IOException {
    Map<String, Object> oldObject = get(namespace, customResourceName);
    String resourceVersion = ((Map<String, Object>)oldObject.get(METADATA)).get(RESOURCE_VERSION).toString();

    ((Map<String, Object>)customResource.get(METADATA)).put(RESOURCE_VERSION, resourceVersion);

    return customResource;
  }

  private DeleteOptions fetchDeleteOptions(boolean cascading, String propagationPolicy) {
    DeleteOptions deleteOptions = new DeleteOptions();
    if (propagationPolicy != null) {
      deleteOptions.setPropagationPolicy(propagationPolicy);
    } else {
      deleteOptions.setOrphanDependents(!cascading);
    }
    return deleteOptions;
  }
}
