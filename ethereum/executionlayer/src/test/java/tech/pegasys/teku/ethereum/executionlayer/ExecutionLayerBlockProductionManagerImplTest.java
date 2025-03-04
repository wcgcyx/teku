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

package tech.pegasys.teku.ethereum.executionlayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static tech.pegasys.teku.ethereum.executionlayer.ExecutionLayerBlockProductionManagerImpl.BLOBS_BUNDLE_BUILDER_DUMMY;

import java.util.Optional;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tech.pegasys.teku.ethereum.executionclient.BuilderClient;
import tech.pegasys.teku.ethereum.executionclient.schema.Response;
import tech.pegasys.teku.ethereum.executionlayer.ExecutionLayerManagerImpl.Source;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.logging.EventLogger;
import tech.pegasys.teku.infrastructure.metrics.StubMetricsSystem;
import tech.pegasys.teku.infrastructure.metrics.TekuMetricCategory;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.builder.SignedBuilderBid;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayload;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayloadContext;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayloadHeader;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayloadResult;
import tech.pegasys.teku.spec.datastructures.execution.FallbackData;
import tech.pegasys.teku.spec.datastructures.execution.FallbackReason;
import tech.pegasys.teku.spec.datastructures.execution.HeaderWithFallbackData;
import tech.pegasys.teku.spec.datastructures.execution.versions.eip4844.BlobsBundle;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.util.DataStructureUtil;

class ExecutionLayerBlockProductionManagerImplTest {

  private final ExecutionClientHandler executionClientHandler = mock(ExecutionClientHandler.class);

  private final BuilderClient builderClient = Mockito.mock(BuilderClient.class);

  private Spec spec = TestSpecFactory.createMinimalBellatrix();

  private DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);

  private final StubMetricsSystem stubMetricsSystem = new StubMetricsSystem();

  private final EventLogger eventLogger = mock(EventLogger.class);

  private final BuilderCircuitBreaker builderCircuitBreaker = mock(BuilderCircuitBreaker.class);
  private ExecutionLayerManagerImpl executionLayerManager;
  private ExecutionLayerBlockProductionManagerImpl blockProductionManager;

  @BeforeEach
  public void setup() {
    this.executionLayerManager = createExecutionLayerChannelImpl(true, false);
    this.blockProductionManager =
        new ExecutionLayerBlockProductionManagerImpl(executionLayerManager);
  }

  @Test
  public void pre4844_builderOffline() throws Exception {
    setBuilderOffline();

    final ExecutionPayloadContext executionPayloadContext =
        dataStructureUtil.randomPayloadExecutionContext(false, true);
    final UInt64 slot = executionPayloadContext.getForkChoiceState().getHeadBlockSlot();
    final BeaconState state = dataStructureUtil.randomBeaconState(slot);

    final ExecutionPayload payload =
        prepareEngineGetPayloadResponse(executionPayloadContext, UInt256.ZERO, slot);

    final ExecutionPayloadHeader header =
        spec.getGenesisSpec()
            .getSchemaDefinitions()
            .toVersionBellatrix()
            .orElseThrow()
            .getExecutionPayloadHeaderSchema()
            .createFromExecutionPayload(payload);

    final ExecutionPayloadResult executionPayloadResult =
        blockProductionManager.initiateBlockProduction(executionPayloadContext, state, true);
    assertThat(executionPayloadResult.getExecutionPayloadContext())
        .isEqualTo(executionPayloadContext);
    assertThat(executionPayloadResult.getExecutionPayloadFuture()).isEmpty();
    assertThat(executionPayloadResult.getBlobsBundleFuture()).isEmpty();

    // we expect local engine header as result
    final HeaderWithFallbackData expectedResult =
        HeaderWithFallbackData.create(
            header, new FallbackData(payload, FallbackReason.BUILDER_NOT_AVAILABLE));
    SafeFuture<HeaderWithFallbackData> headerWithFallbackDataFuture =
        executionPayloadResult.getExecutionPayloadHeaderFuture().orElseThrow();
    assertThat(headerWithFallbackDataFuture.get()).isEqualTo(expectedResult);
    verifyFallbackToLocalEL(slot, executionPayloadContext, expectedResult);

    assertThat(blockProductionManager.getCachedPayloadResult(slot))
        .contains(executionPayloadResult);
    // wrong slot
    assertThat(blockProductionManager.getCachedPayloadResult(slot.plus(1))).isEmpty();

    SafeFuture<ExecutionPayload> unblindedPayload =
        blockProductionManager.getUnblindedPayload(
            dataStructureUtil.randomSignedBlindedBeaconBlock(slot));
    assertThat(unblindedPayload.get()).isEqualTo(payload);

    // wrong slot, we will hit builder client by this call
    final SignedBeaconBlock signedBlindedBeaconBlock =
        dataStructureUtil.randomSignedBlindedBeaconBlock(slot.plus(1));
    assertThatThrownBy(() -> blockProductionManager.getUnblindedPayload(signedBlindedBeaconBlock));
    verify(builderClient).getPayload(signedBlindedBeaconBlock);
  }

  @Test
  public void pre4844_builderOnline() throws Exception {
    setBuilderOnline();

    final ExecutionPayloadContext executionPayloadContext =
        dataStructureUtil.randomPayloadExecutionContext(false, true);
    final UInt64 slot = executionPayloadContext.getForkChoiceState().getHeadBlockSlot();
    final BeaconState state = dataStructureUtil.randomBeaconState(slot);

    // we expect result from the builder
    final ExecutionPayloadHeader header =
        prepareBuilderGetHeaderResponse(executionPayloadContext, false);
    prepareEngineGetPayloadResponse(executionPayloadContext, UInt256.ZERO, slot);
    final HeaderWithFallbackData expectedResult = HeaderWithFallbackData.create(header);

    final ExecutionPayloadResult executionPayloadResult =
        blockProductionManager.initiateBlockProduction(executionPayloadContext, state, true);
    assertThat(executionPayloadResult.getExecutionPayloadContext())
        .isEqualTo(executionPayloadContext);
    assertThat(executionPayloadResult.getExecutionPayloadFuture()).isEmpty();
    assertThat(executionPayloadResult.getBlobsBundleFuture()).isEmpty();
    SafeFuture<HeaderWithFallbackData> headerWithFallbackDataFuture =
        executionPayloadResult.getExecutionPayloadHeaderFuture().orElseThrow();
    assertThat(headerWithFallbackDataFuture.get()).isEqualTo(expectedResult);

    // we expect both builder and local engine have been called
    verifyBuilderCalled(slot, executionPayloadContext);
    verifyEngineCalled(executionPayloadContext, slot);

    final SignedBeaconBlock signedBlindedBeaconBlock =
        dataStructureUtil.randomSignedBlindedBeaconBlock(slot);

    final ExecutionPayload payload = prepareBuilderGetPayloadResponse(signedBlindedBeaconBlock);

    // we expect result from the builder
    assertThat(blockProductionManager.getUnblindedPayload(signedBlindedBeaconBlock))
        .isCompletedWithValue(payload);

    // we expect both builder and local engine have been called
    verify(builderClient).getPayload(signedBlindedBeaconBlock);
    verifyNoMoreInteractions(executionClientHandler);
    verifySourceCounter(Source.BUILDER, FallbackReason.NONE);
  }

  @Test
  public void pre4844_noBuilder() throws Exception {
    setBuilderOnline();

    final ExecutionPayloadContext executionPayloadContext =
        dataStructureUtil.randomPayloadExecutionContext(false, true);
    final UInt64 slot = executionPayloadContext.getForkChoiceState().getHeadBlockSlot();
    final BeaconState state = dataStructureUtil.randomBeaconState(slot);

    final ExecutionPayload payload =
        prepareEngineGetPayloadResponse(executionPayloadContext, UInt256.ZERO, slot);

    final ExecutionPayloadResult executionPayloadResult =
        blockProductionManager.initiateBlockProduction(executionPayloadContext, state, false);
    assertThat(executionPayloadResult.getExecutionPayloadContext())
        .isEqualTo(executionPayloadContext);
    assertThat(executionPayloadResult.getExecutionPayloadHeaderFuture()).isEmpty();
    assertThat(executionPayloadResult.getBlobsBundleFuture()).isEmpty();

    final ExecutionPayload executionPayload =
        executionPayloadResult.getExecutionPayloadFuture().orElseThrow().get();
    assertThat(executionPayload).isEqualTo(payload);

    assertThat(blockProductionManager.getCachedPayloadResult(slot))
        .contains(executionPayloadResult);

    // we will hit builder client by this call
    final SignedBeaconBlock signedBlindedBeaconBlock =
        dataStructureUtil.randomSignedBlindedBeaconBlock(slot);
    assertThatThrownBy(() -> blockProductionManager.getUnblindedPayload(signedBlindedBeaconBlock));
    verify(builderClient).getPayload(signedBlindedBeaconBlock);
  }

  @Test
  public void post4844_builderOffline() throws Exception {
    setup4844();
    setBuilderOffline();

    final ExecutionPayloadContext executionPayloadContext =
        dataStructureUtil.randomPayloadExecutionContext(false, true);
    final UInt64 slot = executionPayloadContext.getForkChoiceState().getHeadBlockSlot();
    final BeaconState state = dataStructureUtil.randomBeaconState(slot);

    final ExecutionPayload payload =
        prepareEngineGetPayloadResponse(executionPayloadContext, UInt256.ZERO, slot);

    final ExecutionPayloadHeader header =
        spec.getGenesisSpec()
            .getSchemaDefinitions()
            .toVersionEip4844()
            .orElseThrow()
            .getExecutionPayloadHeaderSchema()
            .createFromExecutionPayload(payload);

    final ExecutionPayloadResult executionPayloadResult =
        blockProductionManager.initiateBlockAndBlobsProduction(
            executionPayloadContext, state, true);
    assertThat(executionPayloadResult.getExecutionPayloadContext())
        .isEqualTo(executionPayloadContext);
    assertThat(executionPayloadResult.getExecutionPayloadFuture()).isEmpty();

    assertThat(executionPayloadResult.getBlobsBundleFuture().orElseThrow())
        .isEqualTo(BLOBS_BUNDLE_BUILDER_DUMMY);

    // we expect local engine header as result
    final HeaderWithFallbackData expectedResult =
        HeaderWithFallbackData.create(
            header, new FallbackData(payload, FallbackReason.BUILDER_NOT_AVAILABLE));
    SafeFuture<HeaderWithFallbackData> headerWithFallbackDataFuture =
        executionPayloadResult.getExecutionPayloadHeaderFuture().orElseThrow();
    assertThat(headerWithFallbackDataFuture.get()).isEqualTo(expectedResult);
    verifyFallbackToLocalEL(slot, executionPayloadContext, expectedResult);

    assertThat(blockProductionManager.getCachedPayloadResult(slot))
        .contains(executionPayloadResult);

    SafeFuture<ExecutionPayload> unblindedPayload =
        blockProductionManager.getUnblindedPayload(
            dataStructureUtil.randomSignedBlindedBeaconBlock(slot));
    assertThat(unblindedPayload.get()).isEqualTo(payload);

    verifyNoMoreInteractions(builderClient);
    verifyNoMoreInteractions(executionClientHandler);
  }

  @Test
  public void post4844_builderOnline() throws Exception {
    setup4844();
    setBuilderOnline();

    final ExecutionPayloadContext executionPayloadContext =
        dataStructureUtil.randomPayloadExecutionContext(false, true);
    final UInt64 slot = executionPayloadContext.getForkChoiceState().getHeadBlockSlot();
    final BeaconState state = dataStructureUtil.randomBeaconState(slot);

    // we expect result from the builder
    final ExecutionPayloadHeader header =
        prepareBuilderGetHeaderResponse(executionPayloadContext, false);
    prepareEngineGetPayloadResponse(executionPayloadContext, UInt256.ZERO, slot);
    final HeaderWithFallbackData expectedResult = HeaderWithFallbackData.create(header);

    final ExecutionPayloadResult executionPayloadResult =
        blockProductionManager.initiateBlockAndBlobsProduction(
            executionPayloadContext, state, true);
    assertThat(executionPayloadResult.getExecutionPayloadContext())
        .isEqualTo(executionPayloadContext);
    assertThat(executionPayloadResult.getExecutionPayloadFuture()).isEmpty();

    assertThat(executionPayloadResult.getBlobsBundleFuture().orElseThrow())
        .isEqualTo(BLOBS_BUNDLE_BUILDER_DUMMY);

    SafeFuture<HeaderWithFallbackData> headerWithFallbackDataFuture =
        executionPayloadResult.getExecutionPayloadHeaderFuture().orElseThrow();
    assertThat(headerWithFallbackDataFuture.get()).isEqualTo(expectedResult);

    // we expect both builder and local engine have been called
    verifyBuilderCalled(slot, executionPayloadContext);
    verifyEngineCalled(executionPayloadContext, slot);

    final SignedBeaconBlock signedBlindedBeaconBlock =
        dataStructureUtil.randomSignedBlindedBeaconBlock(slot);

    final ExecutionPayload payload = prepareBuilderGetPayloadResponse(signedBlindedBeaconBlock);

    // we expect result from the builder
    assertThat(blockProductionManager.getUnblindedPayload(signedBlindedBeaconBlock))
        .isCompletedWithValue(payload);

    // we expect both builder and local engine have been called
    verify(builderClient).getPayload(signedBlindedBeaconBlock);
    verifyNoMoreInteractions(executionClientHandler);
    verifySourceCounter(Source.BUILDER, FallbackReason.NONE);
  }

  @Test
  public void post4844_noBuilder() throws Exception {
    setup4844();
    setBuilderOnline();

    final ExecutionPayloadContext executionPayloadContext =
        dataStructureUtil.randomPayloadExecutionContext(false, true);
    final UInt64 slot = executionPayloadContext.getForkChoiceState().getHeadBlockSlot();
    final BeaconState state = dataStructureUtil.randomBeaconState(slot);

    final ExecutionPayload payload =
        prepareEngineGetPayloadResponse(executionPayloadContext, UInt256.ZERO, slot);
    final BlobsBundle blobsBundle =
        prepareEngineGetBlobsBundleResponse(executionPayloadContext, slot);

    final ExecutionPayloadResult executionPayloadResult =
        blockProductionManager.initiateBlockAndBlobsProduction(
            executionPayloadContext, state, false);
    assertThat(executionPayloadResult.getExecutionPayloadContext())
        .isEqualTo(executionPayloadContext);
    assertThat(executionPayloadResult.getExecutionPayloadHeaderFuture()).isEmpty();

    assertThat(executionPayloadResult.getBlobsBundleFuture().orElseThrow().get())
        .isEqualTo(blobsBundle);

    final ExecutionPayload executionPayload =
        executionPayloadResult.getExecutionPayloadFuture().orElseThrow().get();
    assertThat(executionPayload).isEqualTo(payload);

    assertThat(blockProductionManager.getCachedPayloadResult(slot))
        .contains(executionPayloadResult);

    // we will hit builder client by this call
    final SignedBeaconBlock signedBlindedBeaconBlock =
        dataStructureUtil.randomSignedBlindedBeaconBlock(slot);
    assertThatThrownBy(() -> blockProductionManager.getUnblindedPayload(signedBlindedBeaconBlock));
    verify(builderClient).getPayload(signedBlindedBeaconBlock);
  }

  private void setup4844() {
    this.spec = TestSpecFactory.createMinimalEip4844();
    this.dataStructureUtil = new DataStructureUtil(spec);
    this.executionLayerManager = createExecutionLayerChannelImpl(true, false);
    this.blockProductionManager =
        new ExecutionLayerBlockProductionManagerImpl(executionLayerManager);
  }

  private ExecutionPayloadHeader prepareBuilderGetHeaderResponse(
      final ExecutionPayloadContext executionPayloadContext, final boolean prepareEmptyResponse) {
    final UInt64 slot = executionPayloadContext.getForkChoiceState().getHeadBlockSlot();

    final SignedBuilderBid signedBuilderBid = dataStructureUtil.randomSignedBuilderBid();

    doAnswer(
            __ -> {
              if (prepareEmptyResponse) {
                return SafeFuture.completedFuture(new Response<>(Optional.empty()));
              }
              return SafeFuture.completedFuture(new Response<>(Optional.of(signedBuilderBid)));
            })
        .when(builderClient)
        .getHeader(
            slot,
            executionPayloadContext
                .getPayloadBuildingAttributes()
                .getValidatorRegistrationPublicKey()
                .orElseThrow(),
            executionPayloadContext.getParentHash());

    return signedBuilderBid.getMessage().getExecutionPayloadHeader();
  }

  private void verifyFallbackToLocalEL(
      final UInt64 slot,
      final ExecutionPayloadContext executionPayloadContext,
      final HeaderWithFallbackData headerWithFallbackData) {
    final FallbackData fallbackData =
        headerWithFallbackData.getFallbackDataOptional().orElseThrow();
    final FallbackReason fallbackReason = fallbackData.getReason();
    final ExecutionPayload executionPayload = fallbackData.getExecutionPayload();
    if (fallbackReason == FallbackReason.BUILDER_HEADER_NOT_AVAILABLE
        || fallbackReason == FallbackReason.BUILDER_ERROR
        || fallbackReason == FallbackReason.LOCAL_BLOCK_VALUE_HIGHER) {
      // we expect both builder and local engine have been called
      verifyBuilderCalled(slot, executionPayloadContext);
    } else {
      // we expect only local engine have been called
      verifyNoInteractions(builderClient);
    }
    verifyEngineCalled(executionPayloadContext, slot);

    final SignedBeaconBlock signedBlindedBeaconBlock =
        dataStructureUtil.randomSignedBlindedBeaconBlock(slot);

    // we expect result from the cached payload
    assertThat(
            executionLayerManager.builderGetPayload(
                signedBlindedBeaconBlock,
                (aSlot) ->
                    Optional.of(
                        new ExecutionPayloadResult(
                            executionPayloadContext,
                            Optional.empty(),
                            Optional.of(SafeFuture.completedFuture(headerWithFallbackData)),
                            Optional.empty()))))
        .isCompletedWithValue(executionPayload);

    // we expect no additional calls
    verifyNoMoreInteractions(builderClient);
    verifyNoMoreInteractions(executionClientHandler);

    verifySourceCounter(Source.BUILDER_LOCAL_EL_FALLBACK, fallbackReason);
  }

  private ExecutionPayload prepareBuilderGetPayloadResponse(
      final SignedBeaconBlock signedBlindedBeaconBlock) {

    final ExecutionPayload payload = dataStructureUtil.randomExecutionPayload();

    when(builderClient.getPayload(signedBlindedBeaconBlock))
        .thenReturn(SafeFuture.completedFuture(new Response<>(payload)));

    return payload;
  }

  private ExecutionPayload prepareEngineGetPayloadResponse(
      final ExecutionPayloadContext executionPayloadContext,
      final UInt256 blockValue,
      final UInt64 slot) {

    final ExecutionPayload payload = dataStructureUtil.randomExecutionPayload();

    when(executionClientHandler.engineGetPayload(executionPayloadContext, slot))
        .thenReturn(SafeFuture.completedFuture(new ExecutionPayloadWithValue(payload, blockValue)));
    return payload;
  }

  private BlobsBundle prepareEngineGetBlobsBundleResponse(
      final ExecutionPayloadContext executionPayloadContext, final UInt64 slot) {
    final BlobsBundle blobsBundle = dataStructureUtil.randomBlobsBundle();

    when(executionClientHandler.engineGetBlobsBundle(executionPayloadContext.getPayloadId(), slot))
        .thenReturn(SafeFuture.completedFuture(blobsBundle));
    return blobsBundle;
  }

  private ExecutionLayerManagerImpl createExecutionLayerChannelImpl(
      final boolean builderEnabled, final boolean builderValidatorEnabled) {
    when(builderCircuitBreaker.isEngaged(any())).thenReturn(false);
    return ExecutionLayerManagerImpl.create(
        eventLogger,
        executionClientHandler,
        builderEnabled ? Optional.of(builderClient) : Optional.empty(),
        spec,
        stubMetricsSystem,
        builderValidatorEnabled
            ? new BuilderBidValidatorImpl(eventLogger)
            : BuilderBidValidator.NOOP,
        builderCircuitBreaker,
        BlobsBundleValidator.NOOP);
  }

  private void updateBuilderStatus(SafeFuture<Response<Void>> builderClientResponse, UInt64 slot) {
    when(builderClient.status()).thenReturn(builderClientResponse);
    // trigger update of the builder status
    executionLayerManager.onSlot(slot);
  }

  private void setBuilderOffline() {
    setBuilderOffline(UInt64.ONE);
  }

  private void setBuilderOffline(final UInt64 slot) {
    updateBuilderStatus(SafeFuture.completedFuture(Response.withErrorMessage("oops")), slot);
    reset(builderClient);
    assertThat(executionLayerManager.getExecutionBuilderModule().isBuilderAvailable()).isFalse();
  }

  private void setBuilderOnline() {
    updateBuilderStatus(SafeFuture.completedFuture(Response.withNullPayload()), UInt64.ONE);
    reset(builderClient);
    assertThat(executionLayerManager.getExecutionBuilderModule().isBuilderAvailable()).isTrue();
  }

  private void verifyBuilderCalled(
      final UInt64 slot, final ExecutionPayloadContext executionPayloadContext) {
    verify(builderClient)
        .getHeader(
            slot,
            executionPayloadContext
                .getPayloadBuildingAttributes()
                .getValidatorRegistrationPublicKey()
                .orElseThrow(),
            executionPayloadContext.getParentHash());
  }

  private void verifyEngineCalled(
      final ExecutionPayloadContext executionPayloadContext, final UInt64 slot) {
    verify(executionClientHandler).engineGetPayload(executionPayloadContext, slot);
  }

  private void verifySourceCounter(final Source source, final FallbackReason reason) {
    final long actualCount =
        stubMetricsSystem
            .getCounter(TekuMetricCategory.BEACON, "execution_payload_source")
            .getValue(source.toString(), reason.toString());
    assertThat(actualCount).isOne();
  }
}
