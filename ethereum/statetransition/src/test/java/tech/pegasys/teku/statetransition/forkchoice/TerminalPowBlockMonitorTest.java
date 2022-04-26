/*
 * Copyright 2021 ConsenSys AG.
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

package tech.pegasys.teku.statetransition.forkchoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static tech.pegasys.teku.infrastructure.async.SafeFuture.completedFuture;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tech.pegasys.teku.core.ChainBuilder;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.async.StubAsyncRunner;
import tech.pegasys.teku.infrastructure.logging.EventLogger;
import tech.pegasys.teku.infrastructure.time.StubTimeProvider;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.config.SpecConfigBuilder.BellatrixBuilder;
import tech.pegasys.teku.spec.config.SpecConfigLoader;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBlockAndState;
import tech.pegasys.teku.spec.datastructures.execution.PowBlock;
import tech.pegasys.teku.spec.executionengine.ExecutionEngineChannel;
import tech.pegasys.teku.spec.executionengine.TransitionConfiguration;
import tech.pegasys.teku.spec.logic.common.block.AbstractBlockProcessor;
import tech.pegasys.teku.spec.util.DataStructureUtil;
import tech.pegasys.teku.storage.client.RecentChainData;
import tech.pegasys.teku.storage.storageSystem.InMemoryStorageSystemBuilder;
import tech.pegasys.teku.storage.storageSystem.StorageSystem;

public class TerminalPowBlockMonitorTest {
  private static final UInt64 BELLATRIX_FORK_EPOCH = UInt64.ONE;
  private static final UInt256 TTD = UInt256.valueOf(10_000_000);
  private static final Bytes32 TERMINAL_BLOCK_HASH = Bytes32.random();
  private static final UInt64 TERMINAL_BLOCK_EPOCH = UInt64.valueOf(2);
  private static final UInt64 TIME_IN_PAST = UInt64.valueOf(123);

  private final ExecutionEngineChannel executionEngine = mock(ExecutionEngineChannel.class);
  private final StubTimeProvider timeProvider = StubTimeProvider.withTimeInSeconds(10_000);
  private final StubAsyncRunner asyncRunner = new StubAsyncRunner(timeProvider);
  private final ForkChoiceNotifier forkChoiceNotifier = mock(ForkChoiceNotifier.class);
  private final EventLogger eventLogger = Mockito.mock(EventLogger.class);

  private Spec spec;
  private DataStructureUtil dataStructureUtil;
  private StorageSystem storageSystem;
  private RecentChainData recentChainData;
  private TerminalPowBlockMonitor terminalPowBlockMonitor;
  private TransitionConfiguration localTransitionConfiguration;

  @BeforeAll
  public static void initSession() {
    AbstractBlockProcessor.blsVerifyDeposit = false;
  }

  @AfterAll
  public static void resetSession() {
    AbstractBlockProcessor.blsVerifyDeposit = true;
  }

  private void setUpTerminalBlockHashConfig() {
    setUpCommon(
        bellatrixBuilder ->
            bellatrixBuilder
                .bellatrixForkEpoch(BELLATRIX_FORK_EPOCH)
                .terminalBlockHash(TERMINAL_BLOCK_HASH)
                .terminalBlockHashActivationEpoch(TERMINAL_BLOCK_EPOCH));

    when(executionEngine.exchangeTransitionConfiguration(localTransitionConfiguration))
        .thenReturn(
            SafeFuture.completedFuture(
                new TransitionConfiguration(
                    localTransitionConfiguration.getTerminalTotalDifficulty(),
                    localTransitionConfiguration.getTerminalBlockHash(),
                    dataStructureUtil.randomUInt64())));
  }

  private void setUpTTDConfig() {
    setUpCommon(
        bellatrixBuilder ->
            bellatrixBuilder.bellatrixForkEpoch(BELLATRIX_FORK_EPOCH).terminalTotalDifficulty(TTD));

    when(executionEngine.exchangeTransitionConfiguration(localTransitionConfiguration))
        .thenReturn(SafeFuture.completedFuture(localTransitionConfiguration));
  }

  private void setUpCommon(Consumer<BellatrixBuilder> bellatrixBuilder) {
    spec =
        TestSpecFactory.createBellatrix(
            SpecConfigLoader.loadConfig(
                "minimal",
                phase0Builder ->
                    phase0Builder
                        .altairBuilder(altairBuilder -> altairBuilder.altairForkEpoch(UInt64.ZERO))
                        .bellatrixBuilder(bellatrixBuilder)));
    dataStructureUtil = new DataStructureUtil(spec);
    storageSystem = InMemoryStorageSystemBuilder.buildDefault(spec);
    storageSystem.chainUpdater().initializeGenesis(false);
    recentChainData = storageSystem.recentChainData();

    localTransitionConfiguration =
        new TransitionConfiguration(
            spec.getGenesisSpecConfig()
                .toVersionBellatrix()
                .orElseThrow()
                .getTerminalTotalDifficulty(),
            spec.getGenesisSpecConfig().toVersionBellatrix().orElseThrow().getTerminalBlockHash(),
            UInt64.ZERO);

    terminalPowBlockMonitor =
        new TerminalPowBlockMonitor(
            executionEngine,
            spec,
            recentChainData,
            forkChoiceNotifier,
            asyncRunner,
            eventLogger,
            timeProvider);

    terminalPowBlockMonitor.onNodeSyncStateChanged(true);
  }

  private void goToSlot(UInt64 slot) {
    storageSystem
        .chainUpdater()
        .updateBestBlock(storageSystem.chainUpdater().advanceChainUntil(slot));
  }

  private void doMerge(Bytes32 terminalBlockHash) {
    SignedBlockAndState newBlockWithExecutionPayloadAtopTerminalBlock =
        storageSystem
            .chainUpdater()
            .chainBuilder
            .generateBlockAtSlot(
                recentChainData.getHeadSlot().plus(1),
                ChainBuilder.BlockOptions.create().setTerminalBlockHash(terminalBlockHash));

    storageSystem.chainUpdater().updateBestBlock(newBlockWithExecutionPayloadAtopTerminalBlock);
  }

  @Test
  void shouldNotFailWhenCurrentSlotInBellatrixMilestoneButHeadStateIsFromEarlierMilestone() {
    setUpTTDConfig();

    // Current epoch is in bellatrix, but state is still genesis from phase0.
    storageSystem
        .chainUpdater()
        .setCurrentSlot(spec.computeStartSlotAtEpoch(BELLATRIX_FORK_EPOCH).plus(1));

    // Terminal block has been reached
    final Bytes32 headBlockHash = dataStructureUtil.randomBytes32();
    final Bytes32 headBlockParentHash = dataStructureUtil.randomBytes32();
    when(executionEngine.getPowChainHead())
        .thenReturn(
            completedFuture(new PowBlock(headBlockHash, headBlockParentHash, TTD, TIME_IN_PAST)));
    when(executionEngine.getPowBlock(headBlockParentHash))
        .thenReturn(
            completedFuture(
                Optional.of(
                    new PowBlock(
                        headBlockParentHash,
                        dataStructureUtil.randomBytes32(),
                        TTD.subtract(10),
                        TIME_IN_PAST))));

    terminalPowBlockMonitor.start();

    asyncRunner.executeQueuedActions();
    verify(forkChoiceNotifier).onTerminalBlockReached(headBlockHash);
  }

  @Test
  public void shouldPerformTerminalBlockDetectionByTTD() {
    Bytes32 headBlockHash;
    Bytes32 headBlockParentHash;

    setUpTTDConfig();

    terminalPowBlockMonitor.start();

    // NOT YET BELLATRIX FORK - should not notify
    goToSlot(UInt64.ONE);

    assertThat(terminalPowBlockMonitor.isRunning()).isTrue();
    assertThat(asyncRunner.hasDelayedActions()).isTrue();

    asyncRunner.executeQueuedActions();

    verify(executionEngine, times(0)).getPowChainHead();
    verify(forkChoiceNotifier, times(0)).onTerminalBlockReached(any());

    // AT BELLATRIX FORK, TTD not reached - should not send
    headBlockHash = dataStructureUtil.randomBytes32();

    goToSlot(BELLATRIX_FORK_EPOCH.times(spec.getGenesisSpecConfig().getSlotsPerEpoch()));

    when(executionEngine.getPowChainHead())
        .thenReturn(
            completedFuture(
                new PowBlock(
                    headBlockHash,
                    dataStructureUtil.randomBytes32(),
                    TTD.subtract(10),
                    TIME_IN_PAST)));

    asyncRunner.executeQueuedActions();

    verify(executionEngine, times(1)).getPowChainHead();
    verify(forkChoiceNotifier, times(0)).onTerminalBlockReached(any());

    // AT BELLATRIX FORK, TTD reached - should notify
    headBlockHash = dataStructureUtil.randomBytes32();
    headBlockParentHash = dataStructureUtil.randomBytes32();
    when(executionEngine.getPowChainHead())
        .thenReturn(
            completedFuture(new PowBlock(headBlockHash, headBlockParentHash, TTD, TIME_IN_PAST)));
    when(executionEngine.getPowBlock(headBlockParentHash))
        .thenReturn(
            completedFuture(
                Optional.of(
                    new PowBlock(
                        headBlockParentHash,
                        dataStructureUtil.randomBytes32(),
                        TTD.subtract(10),
                        TIME_IN_PAST))));

    asyncRunner.executeQueuedActions();

    verify(eventLogger).terminalPowBlockDetected(headBlockHash);
    verify(executionEngine, times(1)).getPowBlock(headBlockParentHash);
    verify(executionEngine, times(2)).getPowChainHead();
    verify(forkChoiceNotifier, times(1)).onTerminalBlockReached(headBlockHash);

    // Terminal Block - should not notify
    asyncRunner.executeQueuedActions();

    verify(executionEngine, times(3)).getPowChainHead();
    verifyNoMoreInteractions(executionEngine);

    // new different Terminal Block with wrong parent TTD - should not notify
    headBlockHash = dataStructureUtil.randomBytes32();
    headBlockParentHash = dataStructureUtil.randomBytes32();
    when(executionEngine.getPowChainHead())
        .thenReturn(
            completedFuture(
                new PowBlock(headBlockHash, headBlockParentHash, TTD.add(10), TIME_IN_PAST)));
    when(executionEngine.getPowBlock(headBlockParentHash))
        .thenReturn(
            completedFuture(
                Optional.of(
                    new PowBlock(
                        headBlockParentHash,
                        dataStructureUtil.randomBytes32(),
                        TTD,
                        TIME_IN_PAST))));

    asyncRunner.executeQueuedActions();

    verify(executionEngine, times(1)).getPowBlock(headBlockParentHash);
    verify(executionEngine, times(4)).getPowChainHead();
    verify(forkChoiceNotifier, times(0)).onTerminalBlockReached(headBlockHash);

    // new different Terminal Block with correct parent TTD - should notify
    headBlockHash = dataStructureUtil.randomBytes32();
    headBlockParentHash = dataStructureUtil.randomBytes32();
    when(executionEngine.getPowChainHead())
        .thenReturn(
            completedFuture(
                new PowBlock(headBlockHash, headBlockParentHash, TTD.add(10), TIME_IN_PAST)));
    when(executionEngine.getPowBlock(headBlockParentHash))
        .thenReturn(
            completedFuture(
                Optional.of(
                    new PowBlock(
                        headBlockParentHash,
                        dataStructureUtil.randomBytes32(),
                        TTD.subtract(10),
                        TIME_IN_PAST))));

    asyncRunner.executeQueuedActions();

    verify(eventLogger).terminalPowBlockDetected(headBlockHash);
    verify(executionEngine, times(1)).getPowBlock(headBlockParentHash);
    verify(executionEngine, times(5)).getPowChainHead();
    verify(forkChoiceNotifier, times(1)).onTerminalBlockReached(headBlockHash);

    // MERGE Completed - should stop
    doMerge(headBlockHash);

    asyncRunner.executeQueuedActions();

    assertThat(terminalPowBlockMonitor.isRunning()).isFalse();

    // final check
    verifyNoMoreInteractions(executionEngine);
    verifyNoMoreInteractions(eventLogger);
  }

  @Test
  public void shouldNotSelectTTDBlockWithTimestampInFuture() {
    Bytes32 headBlockHash;
    Bytes32 headBlockParentHash;

    setUpTTDConfig();

    terminalPowBlockMonitor.start();

    // AT BELLATRIX FORK, TTD reached but block in future - should not notify
    goToSlot(BELLATRIX_FORK_EPOCH.times(spec.getGenesisSpecConfig().getSlotsPerEpoch()));
    headBlockHash = dataStructureUtil.randomBytes32();
    headBlockParentHash = dataStructureUtil.randomBytes32();
    final UInt64 timeInFuture = timeProvider.getTimeInSeconds().plus(1);
    when(executionEngine.getPowChainHead())
        .thenReturn(
            completedFuture(new PowBlock(headBlockHash, headBlockParentHash, TTD, timeInFuture)));
    when(executionEngine.getPowBlock(headBlockParentHash))
        .thenReturn(
            completedFuture(
                Optional.of(
                    new PowBlock(
                        headBlockParentHash,
                        dataStructureUtil.randomBytes32(),
                        TTD.subtract(10),
                        TIME_IN_PAST))));

    asyncRunner.executeQueuedActions();

    verify(eventLogger, never()).terminalPowBlockDetected(headBlockHash);
    verify(forkChoiceNotifier, never()).onTerminalBlockReached(headBlockHash);
  }

  @Test
  void shouldPerformTerminalBlockDetectionByTerminalBlockHash() {
    setUpTerminalBlockHashConfig();

    terminalPowBlockMonitor.start();

    // NOT YET BELLATRIX FORK - should not notify
    goToSlot(UInt64.ONE);

    assertThat(terminalPowBlockMonitor.isRunning()).isTrue();
    assertThat(asyncRunner.hasDelayedActions()).isTrue();

    asyncRunner.executeQueuedActions();

    verify(executionEngine, times(0)).getPowBlock(any());
    verify(forkChoiceNotifier, times(0)).onTerminalBlockReached(any());

    // AT BELLATRIX FORK, Terminal Bloch Epoch not reached - should not notify
    goToSlot(BELLATRIX_FORK_EPOCH.times(spec.getGenesisSpecConfig().getSlotsPerEpoch()));

    asyncRunner.executeQueuedActions();

    verify(executionEngine, times(0)).getPowBlock(any());
    verify(forkChoiceNotifier, times(0)).onTerminalBlockReached(any());

    // AT Terminal Bloch Epoch, Terminal Block Hash not found - should not notify
    goToSlot(TERMINAL_BLOCK_EPOCH.times(spec.getGenesisSpecConfig().getSlotsPerEpoch()));
    when(executionEngine.getPowBlock(TERMINAL_BLOCK_HASH))
        .thenReturn(completedFuture(Optional.empty()));

    asyncRunner.executeQueuedActions();

    verify(executionEngine, times(1)).getPowBlock(TERMINAL_BLOCK_HASH);
    verify(forkChoiceNotifier, times(0)).onTerminalBlockReached(any());

    // AT Terminal Bloch Epoch, Terminal Block Hash found - should notify
    when(executionEngine.getPowBlock(TERMINAL_BLOCK_HASH))
        .thenReturn(
            completedFuture(
                Optional.of(
                    new PowBlock(
                        TERMINAL_BLOCK_HASH,
                        dataStructureUtil.randomBytes32(),
                        UInt256.ONE,
                        TIME_IN_PAST))));

    asyncRunner.executeQueuedActions();

    verify(eventLogger).terminalPowBlockDetected(TERMINAL_BLOCK_HASH);
    verify(executionEngine, times(2)).getPowBlock(TERMINAL_BLOCK_HASH);
    verify(forkChoiceNotifier, times(1)).onTerminalBlockReached(TERMINAL_BLOCK_HASH);

    // MERGE Completed - should stop
    doMerge(TERMINAL_BLOCK_HASH);
    asyncRunner.executeQueuedActions();
    assertThat(terminalPowBlockMonitor.isRunning()).isFalse();

    // final check
    verifyNoMoreInteractions(executionEngine);
    verifyNoMoreInteractions(eventLogger);
  }

  @Test
  void shouldImmediatelyStopWhenMergeCompleted() {
    setUpTerminalBlockHashConfig();
    goToSlot(BELLATRIX_FORK_EPOCH.times(spec.getGenesisSpecConfig().getSlotsPerEpoch()));
    doMerge(TERMINAL_BLOCK_HASH);

    terminalPowBlockMonitor.start();

    asyncRunner.executeQueuedActions();

    assertThat(terminalPowBlockMonitor.isRunning()).isFalse();
  }

  @Test
  void shouldNotPerformCheckIfSyncing() {
    setUpTTDConfig();

    terminalPowBlockMonitor.start();

    terminalPowBlockMonitor.onNodeSyncStateChanged(false);

    goToSlot(BELLATRIX_FORK_EPOCH.times(spec.getGenesisSpecConfig().getSlotsPerEpoch()));

    asyncRunner.executeQueuedActions();

    verifyNoMoreInteractions(executionEngine);
  }

  @Test
  void shouldCalculateTtdEta() {

    setUpTTDConfig();

    final UInt256 tdDiff = TTD.divide(5);

    terminalPowBlockMonitor.start();

    final UInt64 bellatrixSlot =
        BELLATRIX_FORK_EPOCH.times(spec.getGenesisSpecConfig().getSlotsPerEpoch());

    goToSlot(BELLATRIX_FORK_EPOCH.times(spec.getGenesisSpecConfig().getSlotsPerEpoch()));

    pollTtd(TTD.subtract(tdDiff.multiply(5)));
    pollTtd(TTD.subtract(tdDiff.multiply(4)));
    pollTtd(TTD.subtract(tdDiff.multiply(3)));
    pollTtd(TTD.subtract(tdDiff.multiply(2)));
    pollTtd(TTD.subtract(tdDiff.multiply(1)));

    verify(eventLogger)
        .terminalPowBlockTtdEta(
            TTD, Duration.ofSeconds(spec.getSecondsPerEth1Block(bellatrixSlot)));
    verifyNoMoreInteractions(eventLogger);
  }

  private void pollTtd(final UInt256 ttd) {
    when(executionEngine.getPowChainHead())
        .thenReturn(
            completedFuture(
                new PowBlock(
                    dataStructureUtil.randomBytes32(),
                    dataStructureUtil.randomBytes32(),
                    ttd,
                    TIME_IN_PAST)));
    asyncRunner.executeQueuedActions();
  }
}
