package net.corda.finance.flows

import net.corda.core.contracts.*
import net.corda.core.identity.Party
import net.corda.core.messaging.startFlow
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.finance.DOLLARS
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.getCashBalance
import net.corda.finance.issuedBy
import net.corda.testing.core.*
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.driver
import net.corda.testing.driver.internal.InProcessImpl
import net.corda.testing.internal.IntegrationTest
import net.corda.testing.internal.IntegrationTestSchemas
import net.corda.testing.internal.toDatabaseSchemaName
import org.assertj.core.api.Assertions.assertThat
import org.junit.ClassRule
import org.junit.Test

class CashSelectionTest : IntegrationTest() {
    companion object {
        @ClassRule
        @JvmField
        val databaseSchemas = IntegrationTestSchemas(*listOf(ALICE_NAME, BOB_NAME, DUMMY_BANK_A_NAME, DUMMY_NOTARY_NAME)
                .map { it.toDatabaseSchemaName() }.toTypedArray())
    }

    @Test
    fun `unconsumed cash states`() {
        driver(DriverParameters(startNodesInProcess = true, extraCordappPackagesToScan = listOf("net.corda.finance"))) {
            val node = startNode().getOrThrow() as InProcessImpl
            val issuerRef = OpaqueBytes.of(0)
            val issuedAmount = 1000.DOLLARS

            node.rpc.startFlow(::CashIssueFlow, issuedAmount, issuerRef, defaultNotaryIdentity).returnValue.getOrThrow()

            val availableBalance = node.rpc.getCashBalance(issuedAmount.token)

            assertThat(availableBalance).isEqualTo(issuedAmount)

            val exitedAmount = 300.DOLLARS
            node.rpc.startFlow(::CashExitFlow, exitedAmount, issuerRef).returnValue.getOrThrow()

            val availableBalanceAfterExit = node.rpc.getCashBalance(issuedAmount.token)

            assertThat(availableBalanceAfterExit).isEqualTo(issuedAmount - exitedAmount)
        }
    }

    @Test
    fun `select cash states issued by single transaction and give change`() {
        driver(DriverParameters(startNodesInProcess = true, extraCordappPackagesToScan = listOf("net.corda.finance"))) {
            val node = startNode().getOrThrow() as InProcessImpl
            val nodeIdentity = node.services.myInfo.singleIdentity()

            val coins = listOf(3.DOLLARS, 2.DOLLARS, 1.DOLLARS).map { it.issuedBy(nodeIdentity.ref(1)) }

            //create single transaction with 3 cash outputs
            val issuance = TransactionBuilder(null as Party?)
            coins.map { issuance.addOutputState(TransactionState(Cash.State(it, nodeIdentity), "net.corda.finance.contracts.asset.Cash", defaultNotaryIdentity)) }
            issuance.addCommand(Cash.Commands.Issue(), nodeIdentity.owningKey)

            val transaction = node.services.signInitialTransaction(issuance, nodeIdentity.owningKey)
            node.database.transaction {
                node.services.recordTransactions(transaction)
            }

            val issuedAmount = coins.reduce { sum, element -> sum + element }.withoutIssuer()

            val availableBalance = node.rpc.getCashBalance(issuedAmount.token)

            assertThat(availableBalance).isEqualTo(issuedAmount)

            val exitedAmount = 3.01.DOLLARS
            node.rpc.startFlow(::CashExitFlow, exitedAmount, OpaqueBytes.of(1)).returnValue.getOrThrow()

            val availableBalanceAfterExit = node.rpc.getCashBalance(issuedAmount.token)

            assertThat(availableBalanceAfterExit).isEqualTo(issuedAmount - exitedAmount)
        }
    }
}