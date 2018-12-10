// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.controllers;

import static com.yugabyte.yw.common.AssertHelper.*;
import static com.yugabyte.yw.common.FakeApiHelper.doRequestWithAuthToken;
import static com.yugabyte.yw.common.FakeApiHelper.doRequestWithAuthTokenAndBody;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.core.StringContains.containsString;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static play.mvc.Http.Status.OK;
import static play.test.Helpers.contentAsString;

import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.commissioner.tasks.CommissionerBaseTest;
import com.yugabyte.yw.common.ModelFactory;
import com.yugabyte.yw.common.services.YBClientService;
import com.yugabyte.yw.forms.ImportUniverseFormData;
import com.yugabyte.yw.models.Customer;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.yb.client.YBClient;
import org.yb.client.ListTabletServersResponse;
import org.yb.util.ServerInfo;

import play.Application;
import play.inject.guice.GuiceApplicationBuilder;
import play.libs.Json;
import play.mvc.Result;
import play.test.Helpers;
import play.test.WithApplication;

public class ImportControllerTest extends CommissionerBaseTest {
  private static Commissioner mockCommissioner;
  private static String MASTER_ADDRS = "127.0.0.1:7100,127.0.0.2:7100,127.0.0.3:7100";
  private Customer customer;
  private String authToken;
  private YBClient mockClient;
  private ListTabletServersResponse mockResponse;

  @Before
  public void setUp() {
    customer = ModelFactory.testCustomer();
    authToken = customer.createAuthToken();
    mockClient = mock(YBClient.class);
    mockResponse = mock(ListTabletServersResponse.class);
    when(mockYBClient.getClient(any())).thenReturn(mockClient);
    when(mockClient.waitForServer(any(), anyLong())).thenReturn(true);
    when(mockResponse.getTabletServersCount()).thenReturn(3);
    List<ServerInfo> mockTabletSIs = new ArrayList<ServerInfo>();
    ServerInfo si = new ServerInfo("UUID1", "127.0.0.1", 9100, false, "ALIVE");
    mockTabletSIs.add(si);
    si = new ServerInfo("UUID2", "127.0.0.2", 9100, false, "ALIVE");
    mockTabletSIs.add(si);
    si = new ServerInfo("UUID3", "127.0.0.3", 9100, false, "ALIVE");
    mockTabletSIs.add(si);
    when(mockResponse.getTabletServersList()).thenReturn(mockTabletSIs);
    try {
      when(mockClient.listTabletServers()).thenReturn(mockResponse);
      doNothing().when(mockClient).waitForMasterLeader(anyLong());
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  @Test
  public void testImportUniverse() {
    String url = "/api/customers/" + customer.uuid + "/universes/import";
    ObjectNode bodyJson = Json.newObject()
                              .put("universeName", "importUniv")
                              .put("masterAddresses", MASTER_ADDRS);
    // Master phase
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);
    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertNotNull(json.get("universeUUID"));
    assertNotNull(json.get("checks").get("create_db_entry"));
    assertEquals(json.get("checks").get("create_db_entry").asText(), "OK");
    assertNotNull(json.get("checks").get("check_masters_are_running"));
    assertEquals(json.get("checks").get("check_masters_are_running").asText(), "OK");
    assertNotNull(json.get("checks").get("check_master_leader_election"));
    assertEquals(json.get("checks").get("check_master_leader_election").asText(), "OK");
    assertNotNull(json.get("state").asText());
    assertEquals(json.get("state").asText(), "IMPORTED_MASTERS");

    String univUUID = json.get("universeUUID").asText();
    // Tserver phase
    bodyJson.put("currentState", json.get("state").asText())
            .put("universeUUID", univUUID);
    result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);
    assertOk(result);
    json = Json.parse(contentAsString(result));
    assertEquals(json.get("state").asText(), "IMPORTED_TSERVERS");
    assertNotNull(json.get("universeUUID"));
    assertNotNull(json.get("checks").get("find_tservers_list"));
    assertEquals(json.get("checks").get("find_tservers_list").asText(), "OK");
    assertNotNull(json.get("checks").get("check_tservers_are_running"));
    assertEquals(json.get("checks").get("check_tservers_are_running").asText(), "OK");
    assertNotNull(json.get("checks").get("check_tserver_heartbeats"));
    assertEquals(json.get("checks").get("check_tserver_heartbeats").asText(), "OK");
    assertNotNull(json.get("tservers_list").asText());
    assertValue(json, "tservers_count", "3");

    // Finish
    bodyJson.put("currentState", json.get("state").asText());
    result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);
    assertOk(result);
    json = Json.parse(contentAsString(result));
    assertValue(json, "state", "FINISHED");
    assertNotNull(json.get("checks").get("create_prometheus_config"));
    assertEquals(json.get("checks").get("create_prometheus_config").asText(), "OK");
    assertEquals(json.get("universeUUID").asText(), univUUID);

    // Confirm customer knows about this universe and has correct node names/ips.
    url = "/api/customers/" + customer.uuid + "/universes/" + univUUID;
    result = doRequestWithAuthToken("GET", url, authToken);
    assertOk(result);
    json = Json.parse(contentAsString(result));
    JsonNode universeDetails = json.get("universeDetails");
    assertNotNull(universeDetails);
    JsonNode nodeDetailsMap = universeDetails.get("nodeDetailsSet");
    assertNotNull(nodeDetailsMap);
    int numNodes = 0;
    for (Iterator<JsonNode> it = nodeDetailsMap.elements(); it.hasNext();) {
      JsonNode node = it.next();
      int nodeIdx = node.get("nodeIdx").asInt();
      assertValue(node, "nodeName", "yb-importUniv-n" + nodeIdx);
      assertThat(node.get("cloudInfo").get("private_ip").asText(),
                 allOf(notNullValue(), containsString("127.0.0")));
      numNodes++;
    }
    assertEquals(3, numNodes);
  }

  @Test
  public void testInvalidAddressImport() {
    String url = "/api/customers/" + customer.uuid + "/universes/import";
    ObjectNode bodyJson = Json.newObject()
                              .put("universeName", "importUniv")
                              .put("masterAddresses", "incorrect_format");
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);
    assertBadRequest(result, "Could not parse host:port from masterAddresseses: incorrect_format");
  }

  @Test
  public void testInvalidStateImport() {
    String url = "/api/customers/" + customer.uuid + "/universes/import";
    ObjectNode bodyJson = Json.newObject()
                              .put("universeName", "importUniv")
                              .put("masterAddresses", MASTER_ADDRS)
                              .put("currentState",
                                   ImportUniverseFormData.State.IMPORTED_TSERVERS.name());
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);
    assertBadRequest(result, "Valid universe uuid needs to be set.");
  }
}