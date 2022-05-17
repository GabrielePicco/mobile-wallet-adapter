/*
 * Copyright (c) 2022 Solana Labs, Inc.
 */

package com.solana.mobilewalletadapter.fakedapp

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.solana.mobilewalletadapter.clientlib.protocol.JsonRpc20Client
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient
import com.solana.mobilewalletadapter.clientlib.scenario.Scenario
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationScenario
import com.solana.mobilewalletadapter.common.ProtocolContract
import com.solana.mobilewalletadapter.common.protocol.CommitmentLevel
import com.solana.mobilewalletadapter.common.protocol.PrivilegedMethod
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import kotlin.random.Random

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val mobileWalletAdapterClientMutex = Mutex()

    suspend fun authorize(sender: StartActivityForResultSender) {
        localAssociateAndExecute(sender) { client ->
            doAuthorize(client)
        }
    }

    suspend fun signTransaction(sender: StartActivityForResultSender, numTransactions: Int) {
        val transactions = Array(numTransactions) {
            Random.nextBytes(ProtocolContract.TRANSACTION_MAX_SIZE_BYTES)
        }

        localAssociateAndExecute(sender) { client ->
            doSignTransaction(client, transactions)
        }
    }

    suspend fun authorizeAndSignTransaction(sender: StartActivityForResultSender) {
        val transactions = Array(1) {
            Random.nextBytes(ProtocolContract.TRANSACTION_MAX_SIZE_BYTES)
        }

        localAssociateAndExecute(sender) { client ->
            val authorized = doAuthorize(client)
            if (authorized) {
                doSignTransaction(client, transactions)
            }
        }
    }

    suspend fun signMessage(sender: StartActivityForResultSender, numMessages: Int) {
        val messages = Array(numMessages) {
            Random.nextBytes(16384)
        }

        localAssociateAndExecute(sender) { client ->
            doSignMessage(client, messages)
        }
    }

    suspend fun signAndSendTransaction(sender: StartActivityForResultSender, numTransactions: Int) {
        val messages = Array(numTransactions) {
            Random.nextBytes(ProtocolContract.TRANSACTION_MAX_SIZE_BYTES)
        }

        localAssociateAndExecute(sender) { client ->
            doSignAndSendTransaction(client, messages)
        }
    }

    private suspend fun doAuthorize(client: MobileWalletAdapterClient): Boolean {
        var authorized = false
        try {
            val sem = Semaphore(1, 1)
            // TODO: defer actual send to IO thread
            val future = client.authorizeAsync(Uri.parse("https://solana.com"),
                Uri.parse("favicon.ico"),
                "Solana",
                setOf(PrivilegedMethod.SignTransaction)
            )
            future.notifyOnComplete { sem.release() }
            sem.acquire()
            val result = try {
                @Suppress("BlockingMethodInNonBlockingContext")
                future.get()
            } catch (e: ExecutionException) {
                throw MobileWalletAdapterClient.unpackExecutionException(e)
            }
            Log.d(TAG, "Authorized: $result")
            _uiState.update { it.copy(authToken = result.authToken) }
            authorized = true
        } catch (e: IOException) {
            Log.e(TAG, "IO error while sending authorize", e)
        } catch (e: JsonRpc20Client.JsonRpc20RemoteException) {
            when (e.code) {
                ProtocolContract.ERROR_AUTHORIZATION_FAILED -> Log.e(TAG, "Not authorized", e)
                else -> Log.e(TAG, "Remote exception for authorize", e)
            }
        } catch (e: JsonRpc20Client.JsonRpc20Exception) {
            Log.e(TAG, "JSON-RPC client exception for authorize", e)
        } catch (e: TimeoutException) {
            Log.e(TAG, "Timed out while waiting for authorize result", e)
        } catch (e: CancellationException) {
            Log.e(TAG, "authorize request was cancelled", e)
        }

        return authorized
    }

    private suspend fun doSignTransaction(client: MobileWalletAdapterClient, transactions: Array<ByteArray>) {
        try {
            val sem = Semaphore(1, 1)
            val future = client.signTransactionAsync(uiState.value.authToken!!, transactions)
            future.notifyOnComplete { sem.release() }
            sem.acquire()
            val result = try {
                @Suppress("BlockingMethodInNonBlockingContext")
                future.get()
            } catch (e: ExecutionException) {
                throw MobileWalletAdapterClient.unpackExecutionException(e)
            }
            Log.d(TAG, "Signed transaction(s): $result")
        } catch (e: IOException) {
            Log.e(TAG, "IO error while sending sign_transaction", e)
        } catch (e: MobileWalletAdapterClient.InvalidPayloadException) {
            Log.e(TAG, "Transaction payload invalid", e)
        } catch (e: JsonRpc20Client.JsonRpc20RemoteException) {
            when (e.code) {
                ProtocolContract.ERROR_REAUTHORIZE -> Log.e(TAG, "Reauthorization required", e)
                ProtocolContract.ERROR_AUTHORIZATION_FAILED -> Log.e(TAG, "Auth token invalid", e)
                ProtocolContract.ERROR_NOT_SIGNED -> Log.e(TAG, "User did not authorize signing", e)
                else -> Log.e(TAG, "Remote exception for authorize", e)
            }
        } catch (e: JsonRpc20Client.JsonRpc20Exception) {
            Log.e(TAG, "JSON-RPC client exception for sign_transaction", e)
        } catch (e: TimeoutException) {
            Log.e(TAG, "Timed out while waiting for sign_transaction result", e)
        } catch (e: CancellationException) {
            Log.e(TAG, "sign_transaction request was cancelled", e)
        }
    }

    private suspend fun doSignMessage(client: MobileWalletAdapterClient, messages: Array<ByteArray>) {
        try {
            val sem = Semaphore(1, 1)
            val future = client.signMessageAsync(uiState.value.authToken!!, messages)
            future.notifyOnComplete { sem.release() }
            sem.acquire()
            val result = try {
                @Suppress("BlockingMethodInNonBlockingContext")
                future.get()
            } catch (e: ExecutionException) {
                throw MobileWalletAdapterClient.unpackExecutionException(e)
            }
            Log.d(TAG, "Signed message(s): $result")
        } catch (e: IOException) {
            Log.e(TAG, "IO error while sending sign_message", e)
        } catch (e: MobileWalletAdapterClient.InvalidPayloadException) {
            Log.e(TAG, "Message payload invalid", e)
        } catch (e: JsonRpc20Client.JsonRpc20RemoteException) {
            when (e.code) {
                ProtocolContract.ERROR_REAUTHORIZE -> Log.e(TAG, "Reauthorization required", e)
                ProtocolContract.ERROR_AUTHORIZATION_FAILED -> Log.e(TAG, "Auth token invalid", e)
                ProtocolContract.ERROR_NOT_SIGNED -> Log.e(TAG, "User did not authorize signing", e)
                else -> Log.e(TAG, "Remote exception for sign_message", e)
            }
        } catch (e: JsonRpc20Client.JsonRpc20Exception) {
            Log.e(TAG, "JSON-RPC client exception for sign_message", e)
        } catch (e: TimeoutException) {
            Log.e(TAG, "Timed out while waiting for sign_message result", e)
        } catch (e: CancellationException) {
            Log.e(TAG, "sign_message request was cancelled", e)
        }
    }

    private suspend fun doSignAndSendTransaction(client: MobileWalletAdapterClient, transactions: Array<ByteArray>) {
        try {
            val sem = Semaphore(1, 1)
            val future = client.signAndSendTransactionAsync(uiState.value.authToken!!, transactions,
                CommitmentLevel.Confirmed)
            future.notifyOnComplete { sem.release() }
            sem.acquire()
            val result = try {
                @Suppress("BlockingMethodInNonBlockingContext")
                future.get()
            } catch (e: ExecutionException) {
                throw MobileWalletAdapterClient.unpackExecutionException(e)
            }
            Log.d(TAG, "Signatures: $result")
        } catch (e: IOException) {
            Log.e(TAG, "IO error while sending sign_and_send_transaction", e)
        } catch (e: MobileWalletAdapterClient.InvalidPayloadException) {
            Log.e(TAG, "Transaction payload invalid", e)
        } catch (e: MobileWalletAdapterClient.NotCommittedException) {
            Log.e(TAG, "Commitment not reached for all transactions", e)
        } catch (e: JsonRpc20Client.JsonRpc20RemoteException) {
            when (e.code) {
                ProtocolContract.ERROR_REAUTHORIZE -> Log.e(TAG, "Reauthorization required", e)
                ProtocolContract.ERROR_AUTHORIZATION_FAILED -> Log.e(TAG, "Auth token invalid", e)
                ProtocolContract.ERROR_NOT_SIGNED -> Log.e(TAG, "User did not authorize signing", e)
                else -> Log.e(TAG, "Remote exception for authorize", e)
            }
        } catch (e: JsonRpc20Client.JsonRpc20Exception) {
            Log.e(TAG, "JSON-RPC client exception for sign_and_send_transaction", e)
        } catch (e: TimeoutException) {
            Log.e(TAG, "Timed out while waiting for sign_and_send_transaction result", e)
        } catch (e: CancellationException) {
            Log.e(TAG, "sign_and_send_transaction request was cancelled", e)
        }
    }

    private suspend fun localAssociateAndExecute(
        sender: StartActivityForResultSender,
        uriPrefix: Uri? = null,
        action: suspend (MobileWalletAdapterClient) -> Unit
    ) {
        mobileWalletAdapterClientMutex.withLock {
            val semConnectedOrFailed = Semaphore(1, 1)
            val semTerminated = Semaphore(1, 1)
            var mobileWalletAdapterClient: MobileWalletAdapterClient? = null
            val scenarioCallbacks = object : Scenario.Callbacks {
                override fun onScenarioReady(client: MobileWalletAdapterClient) {
                    mobileWalletAdapterClient = client
                    semConnectedOrFailed.release()
                }

                override fun onScenarioError() = semConnectedOrFailed.release()
                override fun onScenarioComplete() = semConnectedOrFailed.release()
                override fun onScenarioTeardownComplete() = semTerminated.release()
            }

            val localAssociation = LocalAssociationScenario(
                getApplication<Application>().mainLooper,
                scenarioCallbacks,
                uriPrefix
            )
            sender.startActivityForResult(localAssociation.createAssociationIntent())

            localAssociation.start()
            try {
                withTimeout(ASSOCIATION_TIMEOUT_MS) {
                    semConnectedOrFailed.acquire()
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Timed out waiting for local association to be ready", e)
                // Let garbage collection deal with cleanup; if we timed out starting, we might
                // hang if we attempt to close.
                return@withLock
            }

            mobileWalletAdapterClient?.let { client -> action(client) }
                ?: Log.e(TAG, "Local association not ready; skip requested action")

            localAssociation.close()
            try {
                withTimeout(ASSOCIATION_TIMEOUT_MS) {
                    semTerminated.acquire()
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Timed out waiting for local association to close", e)
                return@withLock
            }
        }
    }

    interface StartActivityForResultSender {
        fun startActivityForResult(intent: Intent)
    }

    data class UiState(
        val authToken: String? = null
    ) {
        val hasAuthToken: Boolean get() = (authToken != null)
    }

    companion object {
        private val TAG = MainViewModel::class.simpleName
        private const val ASSOCIATION_TIMEOUT_MS = 10000L
    }
}