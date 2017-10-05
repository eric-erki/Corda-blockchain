package net.corda.core.internal

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.concurrent.CordaFuture
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.node.ServiceHub
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.UntrustworthyData
import org.slf4j.Logger

/** This is an internal interface that is implemented by code in the node module. You should look at [FlowLogic]. */
interface FlowStateMachine<R> {
    @Suspendable
    fun getFlowInfo(otherParty: Party, sessionFlow: FlowLogic<*>): FlowInfo

    @Suspendable
    fun initiateFlow(otherParty: Party, sessionFlow: FlowLogic<*>): FlowSession

    @Suspendable
    fun <T : Any> sendAndReceive(receiveType: Class<T>,
                                 otherParty: Party,
                                 payload: Any,
                                 sessionFlow: FlowLogic<*>,
                                 retrySend: Boolean = false): UntrustworthyData<T>

    @Suspendable
    fun <T : Any> receive(receiveType: Class<T>, otherParty: Party, sessionFlow: FlowLogic<*>): UntrustworthyData<T>

    @Suspendable
    fun send(otherParty: Party, payload: Any, sessionFlow: FlowLogic<*>)

    @Suspendable
    fun waitForLedgerCommit(hash: SecureHash, sessionFlow: FlowLogic<*>): SignedTransaction

    fun checkFlowPermission(permissionName: String, extraAuditData: Map<String, String>)

    fun recordAuditEvent(eventType: String, comment: String, extraAuditData: Map<String, String>)

    @Suspendable
    fun flowStackSnapshot(flowClass: Class<out FlowLogic<*>>): FlowStackSnapshot?

    @Suspendable
    fun persistFlowStackSnapshot(flowClass: Class<out FlowLogic<*>>)

    val serviceHub: ServiceHub
    val logger: Logger
    val id: StateMachineRunId
    val resultFuture: CordaFuture<R>
    val flowInitiator: FlowInitiator
    val ourIdentityAndCert: PartyAndCertificate

    /**
     * Suspends until the following condition holds: for each session in the specified [mappings] a message has been received, and it is of type [mappings[session]].
     *
     * Remember that when receiving data from other parties the data should not be trusted until it's been thoroughly
     * verified for consistency and that all expectations are satisfied, as a malicious peer may send you subtly
     * corrupted data in order to exploit your code.
     *
     * @return a [Map] containing the objects received, wrapped in an [UntrustworthyData], by the [FlowSession]s who sent them.
     */
    @Suspendable
    fun receiveAll(mappings: Map<FlowSession, Class<out Any>>, sessionFlow: FlowLogic<*>): Map<FlowSession, UntrustworthyData<Any>>
}
