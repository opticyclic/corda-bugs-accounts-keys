package com.github.opticyclic.corda.demo.accounts.flows

import com.github.opticyclic.corda.demo.accounts.*
import com.github.opticyclic.corda.demo.accounts.contracts.IOUAccountContract
import com.github.opticyclic.corda.demo.accounts.states.IOUAccountState
import com.r3.corda.lib.accounts.contracts.states.AccountInfo
import com.r3.corda.lib.accounts.workflows.services.AccountService
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.packageName
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.utilities.getOrThrow
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.StartedMockNode
import net.corda.testing.node.TestCordapp
import org.testng.annotations.AfterClass
import org.testng.annotations.BeforeClass
import org.testng.annotations.Listeners
import org.testng.annotations.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Listeners(AgentListener::class)
class IOUAccountFlowTests {
    private lateinit var mockNetwork: MockNetwork
    private lateinit var banks: StartedMockNode
    private lateinit var agents: StartedMockNode
    private lateinit var banksAccountService: AccountService
    private lateinit var agentsAccountService: AccountService
    private lateinit var bank1: StateAndRef<AccountInfo>
    private lateinit var bank2: StateAndRef<AccountInfo>
    private lateinit var agent1: StateAndRef<AccountInfo>

    @BeforeClass
    fun setup() {
        mockNetwork = MockNetwork(MockNetworkParameters(
                networkParameters = testNetworkParameters(minimumPlatformVersion = 4),
                cordappsForAllNodes = listOf(
                        TestCordapp.findCordapp(IOUAccountFlow::class.java.packageName),
                        TestCordapp.findCordapp(IOUAccountContract::class.java.packageName),
                        TestCordapp.findCordapp("com.r3.corda.lib.accounts.contracts"),
                        TestCordapp.findCordapp("com.r3.corda.lib.accounts.workflows.flows"),
                        TestCordapp.findCordapp("com.r3.corda.lib.ci")
                )))
        //Name our test nodes for clarity when debugging
        banks = mockNetwork.createPartyNode(legalName = CordaX500Name("Banks Node", "London", "GB"))
        agents = mockNetwork.createPartyNode(legalName = CordaX500Name("Agents Node", "London", "GB"))

        // For real nodes this happens automatically, but we have to manually register the flow for tests.
        listOf(banks, agents).forEach { it.registerInitiatedFlow(IOUAccountResponder::class.java) }

        banksAccountService = banks.accountService()
        agentsAccountService = agents.accountService()

        //Create accounts on the nodes for testing
        bank1 = banksAccountService.createAccount("Bank1").getOrThrow()
        bank2 = banksAccountService.createAccount("Bank2").getOrThrow()
        agent1 = agentsAccountService.createAccount("Agent1").getOrThrow()

        //Share accounts with other nodes
        banksAccountService.shareAccountInfoWithParty(bank1.uuid, agents.identity())
        agentsAccountService.shareAccountInfoWithParty(agent1.uuid, banks.identity())
        mockNetwork.runNetwork()
    }

    @AfterClass
    fun tearDown() {
        mockNetwork.stopNodes()
    }

    @Test
    fun `Save an IOU using accounts on a single node`() {
        val iouValue = 1
        val flow = IOUAccountFlow(iouValue, bank1.uuid, bank2.uuid)
        val signedTx = banks.startFlow(flow).runAndGet(mockNetwork)

        banks.transaction {
            val recordedTx = banks.services.validatedTransactions.getTransaction(signedTx.id)
            val ious = recordedTx!!.tx.outputs
            assertEquals(1, ious.size)

            val recordedState = ious.single().data as IOUAccountState
            assertEquals(iouValue, recordedState.value)
            assertEquals(bank1, banksAccountService.accountInfo(recordedState.lender))
            assertEquals(bank2, banksAccountService.accountInfo(recordedState.borrower))
        }
    }

    @Test
    fun `Save an IOU in accounts across nodes`() {
        val iouValue = 2
        val flow = IOUAccountFlow(iouValue, bank1.uuid, agent1.uuid)
        val signedTx = banks.startFlow(flow).runAndGet(mockNetwork)

        val output = signedTx.tx.outputs.single().data as IOUAccountState

        //Look up the IOU via the transaction and check both accounts are visible to the bank
        banks.transaction {
            val recordedTx = banks.services.validatedTransactions.getTransaction(signedTx.id)
            val ious = recordedTx!!.tx.outputs
            assertEquals(1, ious.size)

            val recordedState = ious.single().data as IOUAccountState
            assertEquals(iouValue, recordedState.value)
            assertEquals(bank1, banksAccountService.accountInfo(recordedState.lender))
            assertEquals(agent1, banksAccountService.accountInfo(recordedState.borrower))
        }
        //Check that the IOU is in the banks vault and both accounts are visible to the bank
        banks.transaction {
            val linearStateCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(output.linearId))
            val ious = banks.services.vaultService.queryBy<IOUAccountState>(linearStateCriteria).states
            assertEquals(1, ious.size)

            val vaultState = ious.single().state.data
            assertEquals(iouValue, vaultState.value)
            assertEquals(bank1, banksAccountService.accountInfo(vaultState.lender))
            assertEquals(agent1, banksAccountService.accountInfo(vaultState.borrower))
        }
        //Check that the IOU is in the agents vault and both accounts are visible to the agent
        agents.transaction {
            val linearStateCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(output.linearId))
            val ious = agents.services.vaultService.queryBy<IOUAccountState>(linearStateCriteria).states
            assertEquals(1, ious.size)

            val vaultState = ious.single().state.data
            assertEquals(iouValue, vaultState.value)
            assertNotNull(agentsAccountService.accountInfo(agent1.uuid))
            assertNotNull(agentsAccountService.accountInfo(bank1.uuid))
        }
    }
}
