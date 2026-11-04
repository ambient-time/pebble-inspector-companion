package coredevices.pebble.signal

import kotlin.test.*

class SignalHomeRetentionTest {
    private fun entry(n:Int,status:HomeActionStatus=HomeActionStatus.OBSERVED,parameters:Map<String,String> = emptyMap()) = HomeLedgerEntry(
        HomeAction("intent-$n","c","lamp","on",parameters,0),status,0,120_000)

    @Test fun retainsRecentAndLiveButArchivesEveryOlderOutcome() {
        val live=entry(1001,HomeActionStatus.SENDING)
        val state=HomeState(ledger=(1..1000).map(::entry)+live)
        val plan=SignalHomeRetention.project(state,1000)
        assertEquals(129,plan.state.ledger.size)
        assertTrue(live in plan.state.ledger)
        assertEquals(872,plan.archived.size)
        assertEquals(872L,plan.state.archivedIntents)
        assertEquals(state.ledger.toSet(),(plan.state.ledger+plan.archived).toSet())
    }

    @Test fun liveSaturationIsTemporaryAndExpiredReviewNeverBecomesExecutable() {
        val state=HomeState(ledger=(1..65).map { entry(it,HomeActionStatus.AWAITING_CONFIRMATION) })
        assertFailsWith<HomeException> { SignalHomeRetention.project(state,1000) }
        val after=SignalHomeRetention.project(state,120_000)
        assertTrue(after.state.ledger.all { it.status==HomeActionStatus.EXPIRED })
        val cancelled=state.copy(ledger=state.ledger.map { if(it.action.id=="intent-1")it.copy(status=HomeActionStatus.CANCELLED)else it })
        assertEquals(65,SignalHomeRetention.project(cancelled,1000).state.ledger.size)
    }

    @Test fun byteBudgetArchivesTerminalDetailsIntactAndPinSurvivesPressure() {
        val parameters=(1..20).associate { "parameter-$it" to "x".repeat(512) }
        val state=HomeState(ledger=(1..128).map { entry(it,parameters=parameters) })
        val plan=SignalHomeRetention.project(state,1000,setOf("intent-1"))
        assertTrue(plan.state.ledger.size<128)
        assertTrue(plan.state.ledger.any { it.action.id=="intent-1" })
        assertEquals(128,plan.state.ledger.size+plan.archived.size)
        assertTrue(plan.archived.all { it.action.parameters==parameters })
    }
}
