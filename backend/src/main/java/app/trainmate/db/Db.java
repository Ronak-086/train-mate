package app.trainmate.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * A deliberately small JDBC helper. SQL stays visible and testable against a real database.
 * Outside a transaction each call borrows a pooled connection; inside {@link #tx} they share one.
 */
public final class Db {
    @FunctionalInterface
    public interface RowMapper<T> { T map(ResultSet rs) throws SQLException; }

    @FunctionalInterface
    public interface TxWork<T> { T run(Db tx) throws SQLException; }

    private final DataSource ds;
    private final Connection bound;

    public Db(DataSource ds) { this(ds, null); }

    private Db(DataSource ds, Connection bound) {
        this.ds = ds;
        this.bound = bound;
    }

    public <T> List<T> list(String sql, RowMapper<T> mapper, Object... args) {
        return withConn(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    List<T> out = new ArrayList<>();
                    while (rs.next()) out.add(mapper.map(rs));
                    return out;
                }
            }
        });
    }

    /** First row, or null. */
    public <T> T one(String sql, RowMapper<T> mapper, Object... args) {
        List<T> rows = list(sql, mapper, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int update(String sql, Object... args) {
        return withConn(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, args);
                return ps.executeUpdate();
            }
        });
    }

    /** Runs several statements atomically. */
    public <T> T tx(TxWork<T> work) {
        if (bound != null) throw new IllegalStateException("Already in a transaction");
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(new Db(ds, c));
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    // ---- helpers ----------------------------------------------------------------------

    @FunctionalInterface
    private interface ConnWork<T> { T run(Connection c) throws SQLException; }

    private <T> T withConn(ConnWork<T> work) {
        try {
            if (bound != null) return work.run(bound);
            try (Connection c = ds.getConnection()) {
                return work.run(c);
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) ps.setNull(i + 1, Types.NULL);
            else if (a instanceof Instant t) ps.setObject(i + 1, OffsetDateTime.ofInstant(t, ZoneOffset.UTC));
            else ps.setObject(i + 1, a);
        }
    }

    public static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    public static Integer integer(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    /** A failed database call. Carries the SQLSTATE so callers can recognise e.g. unique violations (23505). */
    @SuppressWarnings("serial")
    public static final class DbException extends RuntimeException {
        private final String sqlState;

        public DbException(SQLException cause) {
            super(cause.getMessage(), cause);
            this.sqlState = cause.getSQLState();
        }

        public String sqlState() { return sqlState; }
        public boolean isUniqueViolation() { return "23505".equals(sqlState); }
    }
}
