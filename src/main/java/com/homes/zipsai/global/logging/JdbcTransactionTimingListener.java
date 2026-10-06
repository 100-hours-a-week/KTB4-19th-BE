package com.homes.zipsai.global.logging;

import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

@Component
final class JdbcTransactionTimingListener implements TransactionExecutionListener {
    @Override
    public void beforeBegin(TransactionExecution transaction) {
        JdbcTimingContext.beginTransaction(transaction, System.nanoTime());
    }

    @Override
    public void afterBegin(TransactionExecution transaction, Throwable failure) {
        if (failure != null) {
            JdbcTimingContext.failTransactionBegin(transaction, System.nanoTime());
        }
    }

    @Override
    public void afterCommit(TransactionExecution transaction, Throwable failure) {
        JdbcTimingContext.clearTransaction(transaction);
    }

    @Override
    public void afterRollback(TransactionExecution transaction, Throwable failure) {
        JdbcTimingContext.clearTransaction(transaction);
    }
}
