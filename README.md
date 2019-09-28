# Corda Bugs - Accounts Keys

This demonstrates a fundamental problem of the Accounts SDK and how it handles PublicKeys.

Run `./gradlew test` to see the failing test.

The contract tests pass and creating an IOU test passes, however, updating the IOU fails.

`com.github.opticyclic.corda.demo.accounts.flows.IOUAccountUpdateFlowTests` fails with:

     java.lang.IllegalStateException: Can't find Lender account on this node
        at com.github.opticyclic.corda.demo.accounts.flows.IOUAccountUpdateFlow.call(IOUAccountUpdateFlow.kt:86) ~[main/:?]
        at com.github.opticyclic.corda.demo.accounts.flows.IOUAccountUpdateFlow.call(IOUAccountUpdateFlow.kt:25) ~[main/:?]

This is further confusing because before the test runs it asserts that it **can** find the Account on the node.

The different between the two account lookups is that before the test it looks up by UUID and during the flow it uses the PublicKey from the state to look up the account.
	  