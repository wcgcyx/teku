/*
 * Copyright ConsenSys Software Inc., 2022
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.beaconrestapi.v1.debug;

import static org.assertj.core.api.Assertions.assertThat;
import static tech.pegasys.teku.infrastructure.http.ContentTypes.OCTET_STREAM;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_BAD_REQUEST;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_OK;

import java.io.IOException;
import java.util.function.Function;
import okhttp3.Response;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.beaconrestapi.AbstractDataBackedRestAPIIntegrationTest;
import tech.pegasys.teku.beaconrestapi.handlers.v1.debug.GetState;
import tech.pegasys.teku.infrastructure.http.ContentTypes;
import tech.pegasys.teku.infrastructure.json.JsonUtil;
import tech.pegasys.teku.infrastructure.json.types.DeserializableTypeDefinition;
import tech.pegasys.teku.infrastructure.ssz.SszData;
import tech.pegasys.teku.infrastructure.ssz.schema.SszSchema;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;

public class GetStateIntegrationTest extends AbstractDataBackedRestAPIIntegrationTest {

  @Test
  public void shouldGetStateAsJson() throws IOException {
    startRestAPIAtGenesis(SpecMilestone.PHASE0);
    final Response response = get("head", ContentTypes.JSON);
    assertThat(response.code()).isEqualTo(SC_OK);
    final ResponseData<? extends BeaconState> stateResponse = getJsonResponseData(response);
    assertThat(stateResponse).isNotNull();
  }

  @Test
  public void shouldGetStateAsJsonWithoutHeader() throws IOException {
    startRestAPIAtGenesis(SpecMilestone.PHASE0);
    final Response response = get("head");
    assertThat(response.code()).isEqualTo(SC_OK);
    final ResponseData<? extends BeaconState> stateResponse = getJsonResponseData(response);
    assertThat(stateResponse).isNotNull();
  }

  @Test
  public void shouldGetStateAsOctetStream() throws IOException {
    startRestAPIAtGenesis(SpecMilestone.PHASE0);
    final Response response = get("head", OCTET_STREAM);
    assertThat(response.code()).isEqualTo(SC_OK);
    final BeaconState state =
        spec.getGenesisSchemaDefinitions()
            .getBeaconStateSchema()
            .sszDeserialize(Bytes.wrap(response.body().bytes()));
    assertThat(state).isNotNull();
  }

  @Test
  public void shouldRejectAltairStateRequestsForJson() throws IOException {
    startRestAPIAtGenesis(SpecMilestone.ALTAIR);
    final Response response = get("head");
    assertThat(response.code()).isEqualTo(SC_BAD_REQUEST);
  }

  private ResponseData<? extends BeaconState> getJsonResponseData(final Response response)
      throws IOException {
    return JsonUtil.parse(
        response.body().string(),
        typeDefinition(
            spec.forMilestone(SpecMilestone.PHASE0).getSchemaDefinitions().getBeaconStateSchema()));
  }

  public Response get(final String stateIdIdString, final String contentType) throws IOException {
    return getResponse(GetState.ROUTE.replace("{state_id}", stateIdIdString), contentType);
  }

  public Response get(final String stateIdIdString) throws IOException {
    return getResponse(GetState.ROUTE.replace("{state_id}", stateIdIdString));
  }

  private <T extends SszData> DeserializableTypeDefinition<ResponseData<T>> typeDefinition(
      final SszSchema<T> dataSchema) {
    return DeserializableTypeDefinition.<ResponseData<T>, ResponseData<T>>object()
        .initializer(ResponseData::new)
        .finisher(Function.identity())
        .withField(
            "data",
            dataSchema.getJsonTypeDefinition(),
            ResponseData::getData,
            ResponseData::setData)
        .build();
  }

  private static class ResponseData<T> {
    private T data;

    public T getData() {
      return data;
    }

    public void setData(final T data) {
      this.data = data;
    }
  }
}
