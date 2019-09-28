package com.github.opticyclic.corda.demo.accounts.contracts

import com.github.opticyclic.corda.demo.accounts.states.IOUAccountState
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.packageName
import net.corda.testing.core.DummyCommandData
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import net.corda.testing.node.ledger
import org.testng.annotations.Test

class IOUAccountContractTests {
    private val ledgerServices = MockServices(listOf(IOUAccountContract::class.java.packageName))
    private val megaCorp = TestIdentity(CordaX500Name("MegaCorp", "London", "GB"))
    private val miniCorp = TestIdentity(CordaX500Name("MiniCorp", "New York", "US"))
    private val iouValue = 1

    @Test
    fun `transaction must include Create command`() {
        ledgerServices.ledger {
            transaction {
                output(IOUAccountContract.IOU_CONTRACT_ID, IOUAccountState(iouValue, miniCorp.party.owningKey, megaCorp.party.owningKey))
                command(listOf(megaCorp.publicKey, miniCorp.publicKey), DummyCommandData)
                fails()
            }
        }
        ledgerServices.ledger {
            transaction {
                output(IOUAccountContract.IOU_CONTRACT_ID, IOUAccountState(iouValue, miniCorp.party.owningKey, megaCorp.party.owningKey))
                command(listOf(megaCorp.publicKey, miniCorp.publicKey), IOUAccountContract.Commands.Create())
                verifies()
            }
        }
    }

    @Test
    fun `transaction must have no inputs`() {
        ledgerServices.ledger {
            transaction {
                input(IOUAccountContract.IOU_CONTRACT_ID, IOUAccountState(iouValue, miniCorp.party.owningKey, megaCorp.party.owningKey))
                output(IOUAccountContract.IOU_CONTRACT_ID, IOUAccountState(iouValue, miniCorp.party.owningKey, megaCorp.party.owningKey))
                command(listOf(megaCorp.publicKey, miniCorp.publicKey), IOUAccountContract.Commands.Create())
                `fails with`("No inputs should be consumed when issuing an IOU.")
            }
        }
    }

    @Test
    fun `transaction must have one output`() {
        ledgerServices.ledger {
            transaction {
                output(IOUAccountContract.IOU_CONTRACT_ID, IOUAccountState(iouValue, miniCorp.party.owningKey, megaCorp.party.owningKey))
                output(IOUAccountContract.IOU_CONTRACT_ID, IOUAccountState(iouValue, miniCorp.party.owningKey, megaCorp.party.owningKey))
                command(listOf(megaCorp.publicKey, miniCorp.publicKey), IOUAccountContract.Commands.Create())
                `fails with`("Only one output state should be created.")
            }
        }
    }

    @Test
    fun `lender must sign transaction`() {
        ledgerServices.ledger {
            transaction {
                output(IOUAccountContract.IOU_CONTRACT_ID, IOUAccountState(iouValue, miniCorp.party.owningKey, megaCorp.party.owningKey))
                command(miniCorp.publicKey, IOUAccountContract.Commands.Create())
                `fails with`("All of the participants must be signers.")
            }
        }
    }

    @Test
    fun `borrower must sign transaction`() {
        ledgerServices.ledger {
            transaction {
                output(IOUAccountContract.IOU_CONTRACT_ID, IOUAccountState(iouValue, miniCorp.party.owningKey, megaCorp.party.owningKey))
                command(megaCorp.publicKey, IOUAccountContract.Commands.Create())
                `fails with`("All of the participants must be signers.")
            }
        }
    }

    @Test
    fun `lender is not borrower`() {
        ledgerServices.ledger {
            transaction {
                output(IOUAccountContract.IOU_CONTRACT_ID, IOUAccountState(iouValue, megaCorp.party.owningKey, megaCorp.party.owningKey))
                command(listOf(megaCorp.publicKey, miniCorp.publicKey), IOUAccountContract.Commands.Create())
                `fails with`("The lender and the borrower cannot be the same entity.")
            }
        }
    }

    @Test
    fun `cannot create negative-value IOUs`() {
        ledgerServices.ledger {
            transaction {
                output(IOUAccountContract.IOU_CONTRACT_ID, IOUAccountState(-1, miniCorp.party.owningKey, megaCorp.party.owningKey))
                command(listOf(megaCorp.publicKey, miniCorp.publicKey), IOUAccountContract.Commands.Create())
                `fails with`("The IOU's value must be non-negative.")
            }
        }
    }
}
