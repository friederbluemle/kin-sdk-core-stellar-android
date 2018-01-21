package kin.sdk.core.exception;


import android.support.annotation.NonNull;

public class AccountNotFoundException extends OperationFailedException {

    private final String accountId;

    public AccountNotFoundException(@NonNull String accountId) {
        super("Account " + accountId + " was not found");
        this.accountId = accountId;
    }

    @NonNull
    public String getAccountId() {
        return accountId;
    }
}
