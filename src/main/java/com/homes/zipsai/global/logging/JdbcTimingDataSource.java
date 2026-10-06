package com.homes.zipsai.global.logging;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

final class JdbcTimingDataSource extends DelegatingDataSource {
    JdbcTimingDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        long started = System.nanoTime();
        Connection connection;
        try {
            connection = super.getConnection();
        } catch (SQLException | RuntimeException | Error exception) {
            JdbcTimingContext.recordConnectionAcquisition(System.nanoTime() - started, true);
            JdbcTimingContext.recordFailure();
            throw exception;
        }
        JdbcTimingContext.recordConnectionAcquisition(System.nanoTime() - started, false);
        return wrapConnection(connection);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        long started = System.nanoTime();
        Connection connection;
        try {
            connection = super.getConnection(username, password);
        } catch (SQLException | RuntimeException | Error exception) {
            JdbcTimingContext.recordConnectionAcquisition(System.nanoTime() - started, true);
            JdbcTimingContext.recordFailure();
            throw exception;
        }
        JdbcTimingContext.recordConnectionAcquisition(System.nanoTime() - started, false);
        return wrapConnection(connection);
    }

    private Connection wrapConnection(Connection target) {
        try {
            ConnectionHandler handler = new ConnectionHandler(target);
            Connection proxy = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, handler);
            handler.proxy = proxy;
            return proxy;
        } catch (RuntimeException | Error exception) {
            JdbcTimingContext.recordFailure();
            throw exception;
        }
    }

    private static Object invokeTarget(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private static boolean isWrapperMethod(String methodName) {
        return "unwrap".equals(methodName) || "isWrapperFor".equals(methodName);
    }

    private static Object wrapperResult(Object proxy, Object target, Method method, Object[] arguments)
            throws Throwable {
        if (arguments == null || arguments[0] == null) {
            return invokeTarget(target, method, arguments);
        }
        Class<?> wrappedType = (Class<?>) arguments[0];
        if ("isWrapperFor".equals(method.getName())) {
            return wrappedType.isInstance(proxy) || (boolean) invokeTarget(target, method, arguments);
        }
        return wrappedType.isInstance(proxy) ? proxy : invokeTarget(target, method, arguments);
    }

    private static boolean isTransactionBoundary(Method method) {
        return "commit".equals(method.getName()) || "rollback".equals(method.getName());
    }

    private static boolean isPhysicalTransactionBoundary(Method method, Object[] arguments) {
        return isTransactionBoundary(method) && (arguments == null || arguments.length == 0);
    }

    private static boolean isSqlExecution(Method method) {
        return switch (method.getName()) {
            case "execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch",
                    "executeLargeBatch" -> true;
            default -> false;
        };
    }

    private static boolean isStatementPreparation(Method method) {
        return switch (method.getName()) {
            case "createStatement", "prepareStatement", "prepareCall" -> true;
            default -> false;
        };
    }

    private static Statement wrapStatement(Statement target, Connection connection) {
        Class<?> statementType = target instanceof CallableStatement ? CallableStatement.class
            : target instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        InvocationHandler handler = new StatementHandler(target, connection);
        return (Statement) Proxy.newProxyInstance(statementType.getClassLoader(),
            new Class<?>[] {statementType}, handler);
    }

    private static Object invokeTimed(Object target, Method method, Object[] arguments) throws Throwable {
        long started = System.nanoTime();
        boolean failed = false;
        try {
            return invokeTarget(target, method, arguments);
        } catch (Throwable exception) {
            failed = true;
            throw exception;
        } finally {
            long ended = System.nanoTime();
            try {
                if (isPhysicalTransactionBoundary(method, arguments)) {
                    JdbcTimingContext.recordTransactionBoundary(ended, method.getName(), failed);
                }
                JdbcTimingContext.record(ended - started, failed);
            } catch (RuntimeException ignored) {

            }
        }
    }

    private static class ConnectionHandler implements InvocationHandler {
        private final Connection target;
        private Connection proxy;

        ConnectionHandler(Connection target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object ignoredProxy, Method method, Object[] arguments) throws Throwable {
            String methodName = method.getName();
            if ("equals".equals(methodName)) {
                return proxy == arguments[0];
            }
            if ("hashCode".equals(methodName)) {
                return System.identityHashCode(proxy);
            }
            if (isWrapperMethod(methodName)) {
                return wrapperResult(proxy, target, method, arguments);
            }
            if (isTransactionBoundary(method)) {
                return invokeTimed(target, method, arguments);
            }
            Object result;
            try {
                result = invokeTarget(target, method, arguments);
            } catch (Throwable exception) {
                if (isStatementPreparation(method)) {
                    JdbcTimingContext.recordFailure();
                }
                throw exception;
            }
            if (result instanceof Statement statement) {
                return wrapStatement(statement, proxy);
            }
            return result;
        }
    }

    private static class StatementHandler implements InvocationHandler {
        private final Statement target;
        private final Connection connection;

        StatementHandler(Statement target, Connection connection) {
            this.target = target;
            this.connection = connection;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            String methodName = method.getName();
            if ("equals".equals(methodName)) {
                return proxy == arguments[0];
            }
            if ("hashCode".equals(methodName)) {
                return System.identityHashCode(proxy);
            }
            if (isWrapperMethod(methodName)) {
                return wrapperResult(proxy, target, method, arguments);
            }
            if ("getConnection".equals(methodName)) {
                return connection;
            }
            if (isSqlExecution(method)) {
                return invokeTimed(target, method, arguments);
            }
            return invokeTarget(target, method, arguments);
        }
    }
}
