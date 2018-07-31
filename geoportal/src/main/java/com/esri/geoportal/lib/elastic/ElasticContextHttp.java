/* See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * Esri Inc. licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.esri.geoportal.lib.elastic;
import com.esri.geoportal.base.util.JsonUtil;
import com.esri.geoportal.base.util.Val;
import com.esri.geoportal.lib.elastic.http.ElasticClient;

import java.io.FileNotFoundException;
import java.util.Timer;
import java.util.TimerTask;

import javax.annotation.PostConstruct;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Elasticsearch context (HTTP based, no Transport client.
 */
public class ElasticContextHttp extends ElasticContext {
  
  /** Logger. */
  private static final Logger LOGGER = LoggerFactory.getLogger(ElasticContextHttp.class);
  
  /** Instance variables . */
  private boolean wasStarted = false;
  
  /** Constructor */
  public ElasticContextHttp() {
    super();
  }
  
  /**
   * Create an alias.
   * @param index the index name
   * @param alias the alias name
   * @throws Exception
   */
  protected void _createAlias(String index, String alias) throws Exception {
    //LOGGER.info("Creating alias: "+alias+" for index: "+index);
    ElasticClient client = new ElasticClient(getBaseUrl(false),getBasicCredentials());
    String url = client.getBaseUrl()+"/_aliases";
    JsonObjectBuilder request = Json.createObjectBuilder();
    JsonArrayBuilder actions = Json.createArrayBuilder();
    actions.add(Json.createObjectBuilder().add(
      "add",Json.createObjectBuilder().add("index",index).add("alias",alias)
    ));
    request.add("actions",actions);
    String postData = request.build().toString();
    String contentType = "application/json;charset=utf-8";
    String result = client.sendPost(url,postData,contentType);
    //LOGGER.debug("_createAlias.result",result);
  }
  
  /**
   * Create an index.
   * @param name the index name
   * @throws Exception
   */
  protected void _createIndex(String name) throws Exception {
    //LOGGER.info("Creating index: "+name);
    ElasticClient client = new ElasticClient(getBaseUrl(false),getBasicCredentials());
    String url = client.getIndexUrl(name);
    String path = this.getMappingsFile();
    JsonObject jso = (JsonObject)JsonUtil.readResourceFile(path);
    String postData = JsonUtil.toJson(jso,false);
    String contentType = "application/json;charset=utf-8";
    String result = client.sendPut(url,postData,contentType);
    //LOGGER.debug("_createIndex.result",result);    
  }
  
  /**
   * Ensure that an index exists.
   * @param name the index name
   * @param considerAsAlias consider creating an aliased index
   * @throws Exception if an exception occurs
   */
  public void ensureIndex(String name, boolean considerAsAlias) throws Exception {
    LOGGER.debug("Checking index: "+name);
    try {
      if (name == null || name.trim().length() == 0) return;
      
      ElasticClient client = new ElasticClient(getBaseUrl(false),getBasicCredentials());
      try {
        client.sendHead(client.getIndexUrl(name));
        // the index exists
        return;
      } catch (FileNotFoundException e) {
      }
      
      if (name.equals(this.getItemIndexName())) {
        considerAsAlias = this.getIndexNameIsAlias();
      }
      if (name.indexOf("_v") != -1) considerAsAlias = false;
      if (!considerAsAlias) {
        _createIndex(name);
      } else {
        
        String pfx = name+"_v";
        String idxName = null;
        int sfx = -1;
        
        String url = client.getBaseUrl()+"/_aliases";
        String result = client.sendGet(url);
        if (result != null && result.length() > 0 && result.indexOf("{") == 0) {
          JsonObject jso = (JsonObject)JsonUtil.toJsonStructure(result);
          if (!jso.isEmpty()) {
            for (String k: jso.keySet()) {
              if (k.startsWith(pfx)) {
                String s = k.substring(pfx.length());
                int i = Val.chkInt(s,-1);
                if (i > sfx) {
                  sfx = i;
                  idxName = k;
                }               
              }
            }
          }
        }
        
        if (idxName == null) {
          idxName = pfx+"1";
          _createIndex(idxName);
        }
        _createAlias(idxName,name);
      }
    } catch (Exception e) {
      LOGGER.error("Error executing ensureIndex()",e);
      throw e;
    }
  }
  
  /** Startup. */
  @PostConstruct
  public void startup() throws Exception {
    LOGGER.info("Starting up ElasticContextHttp...");
    String[] nodeNames = this.nodesToArray();
    if ((nodeNames == null) || (nodeNames.length == 0)) {
      LOGGER.warn("Configuration warning: Elasticsearch - no nodes defined.");
    } else if (wasStarted) {
      LOGGER.warn("Configuration warning: ElasticContextHttp has already been started.");
    } else {
      
      if (this.getAutoCreateIndex()) {
        String indexName = getItemIndexName();
        boolean indexNameIsAlias = getIndexNameIsAlias();
        try {
          ensureIndex(indexName,indexNameIsAlias);
        } catch (Exception e) {
          // keep trying - every 5 minutes
          long period = 1000 * 60 * 5;
          long delay = period;
          Timer timer = new Timer(true);
          timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
              try {
                ensureIndex(indexName,indexNameIsAlias);
                timer.cancel();
              } catch (Exception e2) {
                // logging is handled by ensureIndex
              }
            }      
          },delay,period);
        }
      }
      
    }
  }
  
}