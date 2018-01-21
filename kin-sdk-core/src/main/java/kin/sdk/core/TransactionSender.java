package kin.sdk.core;


import android.support.annotation.NonNull;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import kin.sdk.core.exception.AccountNotFoundException;
import kin.sdk.core.exception.NoKinTrustException;
import kin.sdk.core.exception.OperationFailedException;
import kin.sdk.core.exception.PassphraseException;
import kin.sdk.core.exception.TransactionFailedException;
import org.stellar.sdk.Asset;
import org.stellar.sdk.KeyPair;
import org.stellar.sdk.PaymentOperation;
import org.stellar.sdk.Server;
import org.stellar.sdk.Transaction;
import org.stellar.sdk.responses.AccountResponse;
import org.stellar.sdk.responses.HttpResponseException;
import org.stellar.sdk.responses.SubmitTransactionResponse;
import org.stellar.sdk.responses.SubmitTransactionResponse.Extras.ResultCodes;

class TransactionSender {

    private final Server server;
    private final KeyStore keyStore;
    private final KeyPair issuer;
    private final Asset kinAsset;

    TransactionSender(Server server, KeyStore keyStore, KeyPair issuer, Asset kinAsset) {
        this.server = server;
        this.keyStore = keyStore;
        this.issuer = issuer;
        this.kinAsset = kinAsset;
    }

    /**
     * Transfer amount of kinIssuer from account to the specified public address.
     *
     * @param from the sender {@link Account}
     * @param publicAddress the address to send the kinIssuer to
     * @param amount the amount of kinIssuer to send
     * @return {@link TransactionId} of the transaction
     * @throws PassphraseException if the transaction could not be signed with the passphrase specified
     * @throws OperationFailedException another error occurred
     */
    @NonNull
    TransactionId sendTransaction(@NonNull Account from, @NonNull String publicAddress,
        @NonNull BigDecimal amount)
        throws OperationFailedException, PassphraseException {

        checkAddressNotEmpty(publicAddress);
        checkForNegativeAMount(amount);
        KeyPair addressee = KeyPair.fromAccountId(publicAddress);
        verifyPayToAddress(addressee);
        KeyPair secretSeedKeyPair = keyStore.decryptAccount(from);
        AccountResponse sourceAccount = loadSourceAccount(secretSeedKeyPair);
        checkKinTrust(sourceAccount);
        Transaction transaction = buildTransaction(secretSeedKeyPair, amount, addressee, sourceAccount);
        return sendTransaction(transaction);
    }

    private void checkAddressNotEmpty(@NonNull String publicAddress) throws OperationFailedException {
        if (publicAddress.isEmpty()) {
            throw new OperationFailedException("Addressee not valid - public address can't be null or empty");
        }
    }

    private void checkForNegativeAMount(@NonNull BigDecimal amount) throws OperationFailedException {
        if (amount.signum() == -1) {
            throw new OperationFailedException("Amount can't be negative");
        }
    }

    @NonNull
    private Transaction buildTransaction(@NonNull KeyPair from, @NonNull BigDecimal amount, KeyPair addressee,
        AccountResponse sourceAccount) {

        Transaction transaction = new Transaction.Builder(sourceAccount)
            .addOperation(new PaymentOperation.Builder(addressee, kinAsset, amount.toString()).build())
            .build();
        transaction.sign(from);
        return transaction;
    }

    private void verifyPayToAddress(KeyPair addressee) throws OperationFailedException {
        AccountResponse addresseeAccount;
        try {
            addresseeAccount = server.accounts().account(addressee);

        } catch (HttpResponseException httpError) {
            if (httpError.getStatusCode() == 404) {
                throw new AccountNotFoundException(addressee.getAccountId());
            } else {
                throw new OperationFailedException(httpError);
            }
        } catch (IOException e) {
            throw new OperationFailedException(e);
        }

        checkKinTrust(addresseeAccount);
    }

    private void checkKinTrust(AccountResponse accountResponse) throws NoKinTrustException {
        if (!doesHasKinBalance(accountResponse)) {
            throw new NoKinTrustException(accountResponse.getKeypair().getAccountId());
        }
    }

    private AccountResponse loadSourceAccount(@NonNull KeyPair from) throws OperationFailedException {
        AccountResponse sourceAccount;
        try {
            sourceAccount = server.accounts().account(from);
        } catch (HttpResponseException httpError) {
            if (httpError.getStatusCode() == 404) {
                throw new AccountNotFoundException(from.getAccountId());
            } else {
                throw new OperationFailedException(httpError);
            }
        } catch (IOException e) {
            throw new OperationFailedException(e);
        }
        return sourceAccount;
    }

    private boolean doesHasKinBalance(AccountResponse addresseeAccount) {
        AccountResponse.Balance balances[] = addresseeAccount.getBalances();
        boolean hasTrust = false;
        for (AccountResponse.Balance balance : balances) {
            if (KinConsts.KIN_ASSET_CODE.equals(balance.getAssetCode()) &&
                balance.getAssetIssuer() != null &&
                issuer.getAccountId().equals(balance.getAssetIssuer().getAccountId())) {
                hasTrust = true;
            }
        }
        return hasTrust;
    }

    @NonNull
    private TransactionId sendTransaction(Transaction transaction) throws OperationFailedException {
        try {
            SubmitTransactionResponse response = server.submitTransaction(transaction);
            if (response.isSuccess()) {
                return new TransactionIdImpl(response.getHash());
            } else {
                ArrayList<String> operationsResultCodes = null;
                String transactionResultCode = null;
                if (response.getExtras() != null && response.getExtras().getResultCodes() != null) {
                    ResultCodes resultCodes = response.getExtras().getResultCodes();
                    operationsResultCodes = resultCodes.getOperationsResultCodes();
                    transactionResultCode = resultCodes.getTransactionResultCode();
                }
                throw new TransactionFailedException(transactionResultCode, operationsResultCodes);
            }
        } catch (IOException e) {
            throw new OperationFailedException(e);
        }
    }
}
