package com.github.opticyclic.corda.demo.accounts.flows

import co.paralleluniverse.fibers.Suspendable
import com.github.opticyclic.corda.demo.accounts.contracts.IOUAccountContract
import com.github.opticyclic.corda.demo.accounts.contracts.IOUAccountContract.Companion.IOU_CONTRACT_ID
import com.github.opticyclic.corda.demo.accounts.states.IOUAccountState
import com.r3.corda.lib.accounts.workflows.accountService
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step

/**
 * A simple example of updating a LinearState.
 * This allows the borrower to update the amount of the IOU and assumes we are running on the borrower node
 */
@InitiatingFlow
@StartableByRPC
class IOUAccountUpdateFlow(private val linearId: UniqueIdentifier, private val iouValue: Int) : FlowLogic<SignedTransaction>() {
    /*
     * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each checkpoint is reached.
     * See the 'progressTracker.currentStep' expressions within the call() function.
     */
    companion object {
        object QUERYING : Step("Querying existing state.")
        object BUILDING : Step("Building a new transaction.")
        object SIGNING : Step("Signing the transaction with our private key.")
        object COLLECTING : Step("Collecting the counterparty's signature.") {
            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
        }

        object FINALISING : Step("Obtaining notary signature and recording transaction.") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(
                QUERYING,
                BUILDING,
                SIGNING,
                COLLECTING,
                FINALISING
        )
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {
        //Get the existing policy from the vault.
        progressTracker.currentStep = QUERYING
        val inputCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
        val inputStateAndRef = serviceHub.vaultService.queryBy<IOUAccountState>(inputCriteria).states.single()
        val input = inputStateAndRef.state.data

        progressTracker.currentStep = BUILDING

        //Create the output.
        val outputPolicy = input.copy(value = iouValue)
        val borrowerKey = outputPolicy.borrower
        val lenderKey = outputPolicy.lender

        // Create the command.
        val requiredSigners = listOf(borrowerKey, lenderKey)
        val command = Command(IOUAccountContract.Commands.Amend(), requiredSigners)

        //Build the transaction.
        val notary = inputStateAndRef.state.notary
        val txBuilder = TransactionBuilder(notary)
                .addInputState(inputStateAndRef)
                .addOutputState(outputPolicy, IOU_CONTRACT_ID)
                .addCommand(command)

        //Sign with our node key AND the private key from the Borrower account
        progressTracker.currentStep = SIGNING
        //Due to design the Lender is amending and we must be hosts of the Lender account
        var keysToSignWith = mutableListOf(ourIdentity.owningKey, borrowerKey)

        //Only add the lender account if it is hosted on this node (potentially it might be on a different node)
        val lenderAccount = accountService.accountInfo(lenderKey)
                ?: throw IllegalStateException("Can't find Lender account on this node")
        if (lenderAccount.state.data.host == serviceHub.myInfo.legalIdentitiesAndCerts.first().party) {
            keysToSignWith.add(lenderKey)
        }
        val locallySignedTx = serviceHub.signInitialTransaction(txBuilder, keysToSignWith)

        //We have to do 2 different flows depending on whether the other account is on our node or a different node
        if (lenderAccount.state.data.host == serviceHub.myInfo.legalIdentitiesAndCerts.first().party) {
            //Notarise and record the transaction in just our vault.
            progressTracker.currentStep = FINALISING
            return subFlow(FinalityFlow(locallySignedTx, emptyList()))
        } else {
            //Send the state to the counterparty and get it back with their signature.
            progressTracker.currentStep = COLLECTING
            val lenderSession = initiateFlow(lenderAccount.state.data.host)
            val lenderSignature = subFlow(CollectSignatureFlow(locallySignedTx, lenderSession, borrowerKey))
            val fullySignedTx = locallySignedTx.withAdditionalSignatures(lenderSignature)
            //Notarise and record the transaction in both parties' vaults.
            progressTracker.currentStep = FINALISING
            return subFlow(FinalityFlow(fullySignedTx, listOf(lenderSession)))
        }
    }
}

@InitiatedBy(IOUAccountUpdateFlow::class)
class IOUAccountUpdateResponder(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val signTransactionFlow = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an IOU transaction." using (output is IOUAccountState)
                val iou = output as IOUAccountState
                "IOUs with a value over 100 are not accepted." using (iou.value <= 100)
            }
        }
        val txId = subFlow(signTransactionFlow).id

        return subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = txId))
    }
}
