package coredevices.pebble.signal

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SignalHomeEngineTest {
    private val connection=HomeConnection("c","Test",HomeConnectorKind.OPENHAB,"https://controller.test")
    private val capability=HomeCapability("unlock","Unlock",listOf(HomeParameter("duration","integer",minimum=1.0,maximum=60.0)))
    private val entity=HomeEntity("c","door","Door",available=true,capabilities=listOf(capability),identity="door-v1")
    private class Store(var state:HomeState):HomePersistence { val writes=mutableListOf<HomeState>();override suspend fun load()=state;override suspend fun save(state:HomeState) { this.state=state;writes+=state } }
    @Test fun confirmationConsumedOnceAndDurableIntentBeforeExactlyOneSend()=runTest {
        val store=Store(HomeState(connections=listOf(connection)));var sends=0
        val transport=object:HomeConnector {
            override suspend fun catalog()=listOf(entity)
            override suspend fun execute(action:HomeAction):HomeDispatchResult { assertEquals(HomeActionStatus.SENDING,store.state.ledger.single().status);sends++;return HomeDispatchResult() }
        }
        val engine=SignalHomeEngine(store,{transport},{1000},{"intent"})
        val entry=engine.prepare(connection,entity,"unlock",mapOf("duration" to "03"))
        assertEquals(HomeActionStatus.AWAITING_CONFIRMATION,entry.status)
        assertEquals("3",entry.action.parameters["duration"])
        assertEquals(HomeActionStatus.AWAITING_CONFIRMATION,engine.dispatch("intent").status);assertEquals(0,sends)
        engine.confirm("intent");assertFailsWith<IllegalArgumentException> { engine.confirm("intent") }
        val a=async { engine.dispatch("intent") };val b=async { engine.dispatch("intent") };a.await();b.await()
        assertEquals(1,sends);assertEquals(HomeActionStatus.ACCEPTED,store.state.ledger.single().status)
    }
    @Test fun exactGrantAllowsAnySupportedActionButRevocationWinsAtDispatch()=runTest {
        val grant=HomeGrant("g","c","door","unlock",mapOf("duration" to "3"),0,identity=entity.identity,connectionBinding=homeConnectionBinding(connection),capabilityBinding=homeCapabilityBinding(capability))
        val store=Store(HomeState(connections=listOf(connection),grants=listOf(grant)))
        val transport=object:HomeConnector { override suspend fun catalog()=listOf(entity);override suspend fun execute(action:HomeAction):HomeDispatchResult=error("revoked grant must not dispatch") }
        val engine=SignalHomeEngine(store,{transport},{1000},{"intent"})
        assertEquals(HomeActionStatus.READY,engine.prepare(connection,entity,"unlock",mapOf("duration" to "3")).status)
        engine.mutateState { it.copy(grants=emptyList()) };assertEquals(HomeActionStatus.EXPIRED,engine.dispatch("intent").status)
    }
    @Test fun changedDeviceOrConnectionCannotReuseConfirmation()=runTest {
        val store=Store(HomeState(connections=listOf(connection)))
        val transport=object:HomeConnector { override suspend fun catalog()=listOf(entity.copy(identity="replacement"));override suspend fun execute(action:HomeAction):HomeDispatchResult=error("must not send") }
        val engine=SignalHomeEngine(store,{transport},{1000},{"intent"});engine.prepare(connection,entity,"unlock",mapOf("duration" to "3"));engine.confirm("intent")
        assertFailsWith<IllegalArgumentException> { engine.dispatch("intent") }
    }
    @Test fun cancelBlocksPendingAndRestartNeverQueuesOldActions()=runTest {
        val store=Store(HomeState(connections=listOf(connection)));var seq=0
        val engine=SignalHomeEngine(store,{error("no network needed")},{1000},{"intent${seq++}"})
        engine.prepare(connection,entity,"unlock",mapOf("duration" to "3"));engine.cancel("intent0")
        assertEquals(HomeActionStatus.CANCELLED,engine.dispatch("intent0").status)
        engine.prepare(connection,entity,"unlock",mapOf("duration" to "3"));engine.confirm("intent1")
        engine.mutateState { it.copy(ledger=it.ledger+it.ledger.last().copy(action=it.ledger.last().action.copy(id="sent"),status=HomeActionStatus.SENDING)) }
        engine.recoverInterrupted();assertEquals(HomeActionStatus.EXPIRED,store.state.ledger[1].status);assertEquals(HomeActionStatus.UNKNOWN,store.state.ledger[2].status)
    }
    @Test fun expiredConfirmationAndInvalidParametersNeverSend()=runTest {
        var now=1000L;val store=Store(HomeState(connections=listOf(connection)));val engine=SignalHomeEngine(store,{error("no network")},{now},{"intent"})
        assertFailsWith<IllegalArgumentException> { engine.prepare(connection,entity,"unlock",mapOf("duration" to "NaN")) }
        assertFailsWith<IllegalArgumentException> { engine.prepare(connection,entity,"unlock",mapOf("duration" to "3","other" to "x")) }
        engine.prepare(connection,entity,"unlock",mapOf("duration" to "3"));now+=120_000
        assertEquals(HomeActionStatus.EXPIRED,engine.confirm("intent").status)
    }
    @Test fun lostResponseIsUnknownAndCannotBeDispatchedAgain()=runTest {
        val store=Store(HomeState(connections=listOf(connection)));var sends=0
        val transport=object:HomeConnector { override suspend fun catalog()=listOf(entity);override suspend fun execute(action:HomeAction):HomeDispatchResult { sends++;throw HomeException("response lost") } }
        val engine=SignalHomeEngine(store,{transport},{1000},{"intent"})
        engine.prepare(connection,entity,"unlock",mapOf("duration" to "3"));engine.confirm("intent")
        assertEquals(HomeActionStatus.UNKNOWN,engine.dispatch("intent").status)
        assertEquals(HomeActionStatus.UNKNOWN,engine.dispatch("intent").status);assertEquals(1,sends)
    }
    @Test fun geepersLostResponseReconcilesDurableIntentReceiptWithoutResend()=runTest {
        val geepers=connection.copy(kind=HomeConnectorKind.GEEPERS)
        val store=Store(HomeState(connections=listOf(geepers)));var sends=0;var receiptReads=0
        val transport=object:HomeConnector {
            override suspend fun catalog()=listOf(entity)
            override suspend fun execute(action:HomeAction):HomeDispatchResult { sends++;throw HomeException("response lost") }
            override suspend fun receipt(id:String):HomeDispatchResult { assertEquals("intent",id);receiptReads++;return HomeDispatchResult(HomeActionStatus.OBSERVED,id,"Device reports completion.") }
        }
        val engine=SignalHomeEngine(store,{transport},{1000},{"intent"})
        engine.prepare(geepers,entity,"unlock",mapOf("duration" to "3"));engine.confirm("intent");engine.dispatch("intent")
        assertEquals("intent",store.state.ledger.single().receiptId)
        assertEquals(HomeActionStatus.OBSERVED,engine.reconcile("intent").status);assertEquals(1,sends);assertEquals(1,receiptReads)
    }
    @Test fun capabilityRevisionChangeInvalidatesEvenConstantEntityIdentity()=runTest {
        val store=Store(HomeState(connections=listOf(connection)))
        val transport=object:HomeConnector { override suspend fun catalog()=listOf(entity.copy(capabilities=listOf(capability.copy(name="Changed definition"))));override suspend fun execute(action:HomeAction):HomeDispatchResult=error("no send") }
        val engine=SignalHomeEngine(store,{transport},{1000},{"intent"})
        engine.prepare(connection,entity,"unlock",mapOf("duration" to "3"));engine.confirm("intent")
        assertFailsWith<IllegalArgumentException> { engine.dispatch("intent") }
    }
    @Test fun deletingTilePreservesCaptureSelection()=runTest {
        val target=HomeTarget("c","door");val store=Store(HomeState(captureTargets=listOf(target),tiles=listOf(HomeTile("t","c","door","Door"))))
        val engine=SignalHomeEngine(store,{error("none")},{0},{"id"});engine.mutateState { it.copy(tiles=emptyList()) }
        assertEquals(listOf(target),engine.currentState().captureTargets)
        assertNotEquals(homeTargetKey("ab","c"),homeTargetKey("a","bc"))
    }
    @Test fun finalSessionGateChecksAfterNetworkReadAndCancelsWithoutSending()=runTest {
        val store=Store(HomeState(connections=listOf(connection)));var allowed=true;var gateCalls=0
        val transport=object:HomeConnector {
            override suspend fun catalog():List<HomeEntity> { allowed=false;return listOf(entity) }
            override suspend fun execute(action:HomeAction):HomeDispatchResult=error("revoked session must not send")
        }
        val engine=SignalHomeEngine(store,{transport},{1000},{"intent"})
        engine.prepare(connection,entity,"unlock",mapOf("duration" to "3"));engine.confirm("intent")
        val result=engine.dispatch("intent") { gateCalls++;allowed }
        assertEquals(HomeActionStatus.CANCELLED,result.status);assertTrue(result.cancelRequested)
        assertEquals(1,gateCalls);assertTrue(store.writes.none { it.ledger.any { e -> e.status==HomeActionStatus.SENDING } })
    }

    @Test fun slowFinalGateCannotExtendConfirmationDeadline()=runTest {
        var now=1000L;val store=Store(HomeState(connections=listOf(connection)))
        val transport=object:HomeConnector {
            override suspend fun catalog()=listOf(entity)
            override suspend fun execute(action:HomeAction):HomeDispatchResult=error("expired confirmation must not send")
        }
        val engine=SignalHomeEngine(store,{transport},{now},{"intent"})
        engine.prepare(connection,entity,"unlock",mapOf("duration" to "3"));engine.confirm("intent")
        assertEquals(HomeActionStatus.EXPIRED,engine.dispatch("intent") { now+=120_000;true }.status)
    }

    @Test fun unavailableDeviceCannotUseConfirmedAction()=runTest {
        val store=Store(HomeState(connections=listOf(connection)))
        val transport=object:HomeConnector {
            override suspend fun catalog()=listOf(entity.copy(available=false))
            override suspend fun execute(action:HomeAction):HomeDispatchResult=error("unavailable device must not send")
        }
        val engine=SignalHomeEngine(store,{transport},{1000},{"intent"})
        engine.prepare(connection,entity,"unlock",mapOf("duration" to "3"));engine.confirm("intent")
        assertFailsWith<IllegalArgumentException> { engine.dispatch("intent") }
        assertTrue(store.writes.none { it.ledger.any { e -> e.status==HomeActionStatus.SENDING } })
    }

}
