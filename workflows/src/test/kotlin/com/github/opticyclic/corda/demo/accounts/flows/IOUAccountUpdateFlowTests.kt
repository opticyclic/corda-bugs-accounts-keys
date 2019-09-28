package com.github.opticyclic.corda.demo.accounts.flows

import com.github.opticyclic.corda.demo.accounts.*
import com.github.opticyclic.corda.demo.accounts.contracts.IOUAccountContract
import com.github.opticyclic.corda.demo.accounts.states.IOUAccountState
import com.r3.corda.lib.accounts.contracts.states.AccountInfo
import com.r3.corda.lib.accounts.workflows.services.AccountService
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
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
import org.testng.Assert
import org.testng.annotations.*

@Listeners(AgentListener::class)
class IOUAccountUpdateFlowTests {
    private lateinit var linearId: UniqueIdentifier
    private lateinit var mockNetwork: MockNetwork
    private lateinit var banks: StartedMockNode
    private lateinit var agents: StartedMockNode
    private lateinit var banksAccountService: AccountService
    private lateinit var agentsAccountService: AccountService
    private lateinit var bank1: StateAndRef<AccountInfo>
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

    @BeforeMethod
    fun `The Bank saves the IOU`() {
        val iouValue = 1
        val flow = IOUAccountFlow(iouValue, bank1.uuid, agent1.uuid)
        val signedTx = banks.startFlow(flow).runAndGet(mockNetwork)

        val banksState = banks.services.vaultService.queryBy<IOUAccountState>().states.first()
        val agentState = agents.services.vaultService.queryBy<IOUAccountState>().states.first()

        Assert.assertEquals(signedTx.id, banksState.ref.txhash)
        Assert.assertEquals(banksState, agentState)
        Assert.assertEquals(banksState.state.data.value, iouValue)

        //Save linearId for dependent tests
        linearId = banksState.state.data.linearId
    }

    @Test
    fun `The Agent updates the IOU amount`() {
        //Verify that we can find both accounts on both nodes first
        Assert.assertNotNull(banksAccountService.accountInfo(bank1.uuid))
        Assert.assertNotNull(banksAccountService.accountInfo(agent1.uuid))
        Assert.assertNotNull(agentsAccountService.accountInfo(bank1.uuid))
        Assert.assertNotNull(agentsAccountService.accountInfo(agent1.uuid))

        //Now update the IOU
        val newIouValue = 5
        val flow = IOUAccountUpdateFlow(linearId, newIouValue)
        val future = agents.startFlow(flow)
        mockNetwork.runNetwork()

        val transaction = future.getOrThrow()
        val linearIdCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
        val agentState = agents.services.vaultService.queryBy<IOUAccountState>(linearIdCriteria).states.single()

        Assert.assertEquals(transaction.id, agentState.ref.txhash)
        Assert.assertEquals(agentState.state.data.value, newIouValue)
    }
}
